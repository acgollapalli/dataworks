(ns dataworks.internal
  (:require
   [clojure.core.async :refer [go go-loop chan close!
                               alt! timeout <! >!] :as async]
   [clojure.pprint :refer [pprint] :as p]
   [dataworks.authentication :as auth]
   [dataworks.common :refer :all]
   [dataworks.time-utils :refer :all]
   [dataworks.heartbeat :as heartbeat]
   [dataworks.db.app-db :refer :all]
   [dataworks.internals :refer [internal-ns]]
   [dataworks.stream-utils :refer [consumer-instance
                                   consume! produce!]]
   [mount.core :refer [defstate] :as mount]
   [tick.alpha.api :as tick]
   [yada.yada :refer [as-resource] :as yada]))

;; An internal does a thing, waits a period of time, then does
;; it again. An internal is a function, though inherently not a
;; pure one. An internal is a recursive function, which is
;; called upon its return value. You do not need to tell the
;; function to recur, it is wrapped in a loop at runtime.

;; An initial value may be specified to start the internal.

;; If an internal returns a map, with the key :next-run and a
;; value of a tick duration or java instant then the internal
;; will be run after that duration or at that point in time,
;; other wise, it does so according to the :next-run specified
;; in the creation of the internal, which can be a duration or
;; an instant.

;; Example: TODO


(def internal-map
  (atom {}))

(defn evals? [{:internal/keys [name function] :as params}]
  (println "evalidating" name)
  (binding [*ns* internal-ns]
    (try (eval function)
         (catch Exception e
           {:status :failure
            :message :unable-to-evalidate-function
            :details (.getMessage e)}))))

(defn evalidate [params]
  (if-vector-conj params
    "params"
    (->? params
         evals?
         function?
         one-arg?)))

(defn get-millis [t]
  (tick/millis (tick/between (tick/now)
                             (consume-time t))))

(defn db-fy [params]
  (if-vector-first params
    db-fy
    (let [{:keys [name function init]} params]
      {:crux.db/id (keyword "internal" name)
       :internal/name name
       :internal/function function
       :internal/init init})))

(defn run-next [{:keys [next-run] :as params} channel]
  (go
      (do
        (<! (timeout (get-millis next-run)))
        (>! channel params))))

(defn create-new-alert
  [{:keys [next-run] :as params} name]
  (submit-tx [[:crux.tx/put
               {:crux.db/id (keyword "internal.alert" name)
                :alert/name name
                :alert/params params
                :responsibility/node heartbeat/node-id}
               (tick/inst (consume-time next-run))]]))

(defn delete-old-alert [name]
  (submit-tx [[:crux.tx/delete
                 (keyword "internal.alert" name)
                 (tick/inst (tick/now))]]))

(defn check-alerts
  [name channel]
  (when-let [alert (entity (keyword "internal.alert" name))]
    (delete-old-alert name)
    (go (>! (:alert/params alert)))))

(defn handle-event
  [param name channel]
  (fn [{:keys [next-run] :as params}]
    (delete-old-alert name)
    (create-new-alert params name)
    (if (> (get-millis next-run) 100)
      (run-next params channel))))

(defn new-internal
  [[{:internal/keys [name ]} function]]
  {:channel (chan)
   :function
   (fn [channel]
     (go-loop [n 0]
       (alt! channel ([param]
                      (when param
                        (produce!
                         (str "internal." name)
                         (function param))
                        (recur n)))
             (timeout 1) ([]
                           (consume!
                            (consumer-instance
                             (str "internal." name)
                             (handle-event name channel)))
                          (if (>= 10 n)
                            (do
                              (check-alerts)
                              (recur 0))
                            (recur (inc n)))))))})

(defn add-internal!
  ([params]
   (apply add-internal! (evalidate params)))
  ([{:internal/keys [name init] :as params} function]
  (if-let [{:keys [channel]} ((keyword name) @internal-map)]
    (close! channel))
  (swap! internal-map
         (fn [i-map]
           (assoc i-map
                  (keyword name)
                  (new-internal params function))))
  (let [{:keys [channel function]}
        (get @internal-map (keyword name))]
    (function channel))
   params))

(defn apply-internal! [params]
  (apply add-internal! params))

(defn init-internal!
  [{:internal/keys [init] :as params}]
  (let [{:keys [channel]} (get @internal-map (keyword name))]
    (go (>! channel init)))
  {:status :success
   :message :internal-added
   :details params})

(defn create-internal! [internal]
  (->? internal
       (blank-field? :name :function :init)
       valid-name?
       (parseable? :function :init)
       (function-already-exists? :internal)
       db-fy
       evalidate
       added-to-db?
       apply-internal!
       init-internal!))

(defn update-internal! [path-name internal]
  (->? internal
       (updating-correct-function? path-name)
       (blank-field? :function :init)
       (parseable? :function :init)
       (add-current-stored-function :internal)
       (has-params? :internal:name :init)
       (valid-update? :internal :function)
       db-fy
       evalidate
       added-to-db?
       apply-internal!
       init-internal!))

(defn start-internals! []
  (do (println "Starting Internals!")
      (let [trs (get-stored-functions :internal)
            status (map add-internal! trs)]
        (if (every? #(= (:status %) :success) status)
          (println "Internals Started!")
          (println "Internals Failed to Start:"
                   (map :name status))))))

(defstate internal-state
  :start
  (start-internals!)
  :stop
  (do
    (map (fn [{:keys [channel]}]
           (close! channel))
         @internal-map)
    (reset! internal-map {})))

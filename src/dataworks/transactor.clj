(ns dataworks.transactor
  (:require
   [cheshire.core :as cheshire]
   [clj-http.client :as client]
   [clojure.core.async :refer [>! <! go go-loop chan close! alt! timeout] :as async]
   [clojure.pprint :refer [pprint] :as p]
   [dataworks.db.app-db :refer [app-db]]
   [dataworks.db.user-db :refer [user-db]]
   [dataworks.transactors :refer [transactor-ns]]
   [monger.collection :as mc]
   [monger.operators :refer :all]
   [monger.conversion :refer [to-object-id]]
   [monger.result :as result]
   [monger.json]
   [tick.alpha.api :as time]
   [yada.yada :refer [as-resource] :as yada]))

(def transactor-map
  (atom {}))

(defn get-transactors []
  (do (println "Getting Transactors!")
      (let [trs (mc/find-maps app-db "transactors")]
        ;;(println (str "Transactors Received: " (count trs)))
        trs)))

(defn get-transactor [id]
  (mc/find-one-as-map app-db "transactors" {:_id (to-object-id id)}))


(defn new-transactor
  ([func]
  {:channel (chan)
   :handler (fn [channel]
              (go-loop []
                (if-let [value (<! channel)]
                    ;;(if (> (count (first (:arglists (meta func)))) 1)
                    ;;(func value channel)
                 (do  (func value) ;; TODO Add transactor logging.
                      (recur))
                 "chanel closed"
                    ;;)
                 )))})
  ([func t-out t-out-fn]
  {:channel (chan)
   :handler (fn [channel]
              (go-loop []
                (alt! (timeout t-out) ([] (do
                                            (t-out-fn)
                                            (recur)))
                      channel ([value]
                               (if (nil? value)
                                 "channel closed"
                                 (do (func value)
                                     (recur)))))))}))

(defn apply-transactor! [{:keys [channel handler]}]
  (handler channel))

(defn evalidate [f]
  (binding [*ns* transactor-ns]
    ;;(pprint (read-string f))
    (eval (read-string f))))

(defn get-millis [t]
  (if (pos-int? t)
    t
    (if (string? t)
      (let [t-evald (evalidate t)]
        (if (pos-int? t-evald)
          t-evald
          (if (= java.time.Duration (type t-evald))
            (time/millis t-evald)
            1000)))))) ;; TODO add proper error handling

(defn add-transactor! [{:keys [name func] :as params}]
  (do
    ;;(println (str "Adding Transactor " name))
    ;;(pprint func)
    (swap! transactor-map
           (fn [t-map]
             (assoc t-map
                    (keyword name)
                    (let [t-out (:timeout params)
                          t-out-fn (:timeout-fn params)]
                      (if (and (nil? t-out) (nil? t-out-fn))
                        (new-transactor (evalidate func))
                        (new-transactor (evalidate func)
                                        (get-millis t-out)
                                        (evalidate t-out-fn)))))))
    (apply-transactor! ((keyword name) @transactor-map))))

;; TODO Add transactor validation
(defn create-transactor! [params]
  (if-let [tran (mc/insert-and-return app-db
                                      "transactors"
                                      params)]
      (add-transactor! params)))

(defn transact! [tname value]
  (go
    (>! (get-in @transactor-map [(keyword tname) :channel])
        value)))

;;TODO this isn't right. Need to add DB update to this function
(defn update-transactor! [id {:keys [name func] :as params}]
  (let [update  (mc/update-by-id app-db "transactors" (to-object-id id)
                                 {$set (reduce (fn [m [k v]]
                                                 (assoc m k v))
                                               {}
                                               params)})
        channel (get-in @transactor-map [(keyword name) :channel])]
    (if (result/acknowledged? update)
      (do
        (dosync
         (close! channel)
         (add-transactor! params))
        "success")
      "failure")))

(defn start-transactors! []
  (do
    (println "Starting Transactors!")
    (let [trs (get-transactors)
          started-trs (map add-transactor! trs)]
      (if (= (count trs)
             (count started-trs))
        (println "Transactors Started!")
        (println "Transactors Failed to Start.")))))

;; TODO ADD AUTHENTICATION!!!!!!!!!!!!!!!!!1111111111111
(def transactors
  (yada/resource
   {:id :transactors
    :description "this is the resource that returns all transactor documents"
    ;;:access-control {:realm "developer"
    ;;                 :scheme ????
    ;;                 :verify ????}
    :methods {:get {:response (fn [ctx] (get-transactors))}
              :post
              {:consumes #{"application/json"}
               :response
               (fn [ctx]
                 (let [body (ctx :body)]
                   (create-transactor! body)))}}}))

;; TODO ADD AUTHENTICATION!!!!!!!!!!!!!!!!!1111111111111
(def transactor
  (yada/resource
   {:id :transactor
    :description "resource for individual transactor"
    :parameters {:path {:id String}} ;; do I need plurumatic's schema thing?
    ;;:access-control {:realm "developer"
    ;;                 :scheme ????
    ;;                 :verify ????}
    :methods {:get
              {:produces "application/json"
               :response
               (fn [ctx]
                 (let [id (get-in ctx [:request :route-params :id])]
                   (get-transactor id)))}
              :post
              {:consumes #{"application/json"}
               :produces "application/json"
               :response
               (fn [ctx]
                 (let [id (get-in ctx [:request :route-params :id])
                       body (ctx :body)]
                   {:update-status  ;; TODO make this less shitty
                    (update-transactor! id body)}))}}}))

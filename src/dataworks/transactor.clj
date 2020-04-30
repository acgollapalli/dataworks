(ns dataworks.transactor
  (:require
   [clojure.core.async :refer [go
                               close!
                               chan
                               tap] :as async]
   [clojure.pprint :refer [pprint]]
   [dataworks.utils.stream :as stream]
   [dataworks.app-graph :refer [stream!
                                node-state]]
   [dataworks.db.app-db :refer [get-stored-function
                                get-stored-functions
                                add-current-stored-function
                                function-already-exists?
                                added-to-db?
                                entity]]
   [dataworks.authentication :as auth]
   [dataworks.utils.common :refer :all]
   [dataworks.utils.time :refer :all]
   [dataworks.transactors :refer [transactor-ns
                                  transactor-map]]
   [mount.core :refer [defstate] :as mount]))

;; A transactor does a thing when called.
;; A transactor is a function, though inherently not a pure one.
;; You may specify arguments for a transactor.
;; A transactor is called via the transact! call, in which the
;; name of the function is the first argument, and any arguments
;; for the transactor are the subsequent arguments.
;; ex: (transact! :your-transactor arg1 arg2)
;; The transactor name may be a clojure keyword or a string.
;; The transact! call is available to all other stored functions.

;; TODO Add validation here.
(defn evals? [{:transactor/keys [name function] :as params}]
  (println "evalidating" name)
  (binding [*ns* transactor-ns]
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
                       function?)))

(defn add-transactor!
  ([{:transactor/keys [name function] :as params}]
   (if-let [f (evalidate params)]
     (apply add-transactor! f)))
  ([{:transactor/keys [name] :as params} f]
   (swap! transactor-map #(assoc % (keyword name) f))
   {:status :success
    :message :transactor-added
    :details params}))

(defn apply-transactor! [params]
  (stream! :kafka/dataworks.internal.functions
           (select-keys (first params)
                        [:crux.db/id
                         :stored-function/type]))
  (apply add-transactor! params))

(defn db-fy
  [params]
  (if-vector-first params
                   db-fy
                   {:crux.db/id (keyword "transactor" (:name params))
                    :transactor/name (keyword (:name params))
                    :transactor/function (:function params)
                    :stored-function/type :transactor}))

(defn create-transactor! [transactor]
  (->? transactor
       (blank-field? :name :function)
       valid-name?
       (parseable? :function)
       (function-already-exists? :transactor)
       db-fy
       (dependencies? :transactor)
       evalidate
       added-to-db?
       apply-transactor!))

(defn update-transactor! [path-name transactor]
  (->? transactor
       (updating-correct-function? path-name)
       (blank-field? :function)
       (parseable? :function)
       (add-current-stored-function :transactor)
       (has-params? :transactor :name)
       (valid-update? :transactor :function)
       db-fy
       (dependencies? :transactor)
       evalidate
       added-to-db?
       apply-transactor!))

(defn start-transactors! []
  (do (println "Starting Transactors!")
      (let [trs (get-stored-functions :transactor)
            status (map add-transactor! trs)]
        (if (every? #(= (:status %) :success) status)
          (println "Transactors Started!")
          (println "Transactors Failed to Start:"
                   (map :name status))))))

(defstate transactor-chan
  :start
  (let [c (chan
           10
           (comp
            (filter
             #(= (:stored-function/type %)
                 (keyword :transactor)))
            (map  ;; TODO add error handling.
             (fn [{:crux.db/keys [id]}]
               (add-transactor! (entity id))))))]
    (stream/take-while c)
    (tap (get-in
          node-state
          [:stream/dataworks.internal.functions
           :output])
          c))
  :stop
  (close! transactor-chan))

;;(defstate transactor-state
;;  :start (start-transactors!)
;;  :stop (reset! transactor-map {}))

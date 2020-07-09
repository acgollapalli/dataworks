(ns dataworks.db.app-db
  (:require
   [clojure.java.io :as io]
   [dataworks.utils.common :refer :all]
   [crux.api :as crux]
   [tick.alpha.api :as tick]
   [mount.core :refer [defstate]]))

(def internal-kafka-settings
  (if-let [settings (try
                      (-> "config.edn"
                        slurp
                        read-string
                        :internal-kafka-settings)
                      (catch Exception _ nil))]
    settings
    {:crux.kafka/bootstrap-servers "localhost:9092"
     :crux.kafka/tx-topic (str "dataworks-internal."
                               "crux-transaction-log")
     :crux.kafka/doc-topic "dataworks-internal.crux-docs"
     :crux.kv/db-dir "internal-data"}))

(defstate app-db
  :start
  (let [db (crux/start-node
            (merge
             {:crux.node/topology '[crux.kafka/topology
                                    crux.kv.rocksdb/kv-store]}
             internal-kafka-settings))]
    (println "synchronizing app-db")
    (crux/sync db (tick.alpha.api/new-duration 3 :seconds))
    db)
  :stop
  (.close app-db))

(defn submit-tx
  "Shorthand for crux/submit-tx, using app-db"
  [transactions]
  (crux/submit-tx app-db transactions))

(defn query
  "Shorthand for crux/q using app-db"
  ([query]
   (crux/q (crux/db app-db) query))
  ([valid-time query]
   (crux/q (crux/db app-db valid-time) query))
  ([valid-time transaction-time query]
   (crux/q (crux/db app-db
                    valid-time
                    transaction-time)
           query)))

(defn entity
  "Shorthand for crux/entity using app-db"
  [entity-id]
  (crux/entity (crux/db app-db) entity-id))

(defn get-stored-function
  ([eid]
   (let [db (crux/db app-db)]
     (crux/entity db eid)))
  ([name function-type]
   (get-stored-function
    (get-entity-param name function-type))))

(defn get-stored-functions
  ([]
   (map (comp get-stored-function first)
        (crux/q (crux/db app-db)
                {:find '[e]
                 :where [['e :stored-function/type]]})))
  ([function-type]
   (map (comp get-stored-function first)
        (crux/q (crux/db app-db)
                {:find '[e]
                 :where [['e :stored-function/type
                          function-type]]}))))

(defn function-already-exists?
  [{:crux.db/keys [id] :stored-function/keys [type] :as params}]
  (println "checking for duplicate" id)
  (if (get-stored-function id)
    {:status :failure
     :message (generate-message type "%-already-exists")}
    params))

(defn add-current-stored-function
  "Takes the map received by the endpoints for creation
   and modification of stored functions and returns a
   vector containing that map, as well as a map of the
   current stored function. Useful for threading through
   functions which require both the new stored function
   and the current stored function for comparison."
  [{:crux.db/keys [id] :as params}]
  (if-let [current (get-stored-function id)]
    (assoc params :current/function current)
    {:status :failure
     :message :stored-function-does-not-exist
     :details (str id " doesn't exist yet. "
                   "You have to create it before you "
                   "can update it.")}))

(defn added-to-db?
  [{:current/keys [function]
    :stored-function/keys [type]
    :as params}]
  (println "adding-to-db")
  (let [db-fn (select-ns-keys params type :stored-function :crux.db)]
    (try
      (let [tx (if function
                 [:crux.tx/put db-fn]
                 [:crux.tx/cas function db-fn])]
        (crux/await-tx app-db
                       (crux/submit-tx app-db [tx])
                       #time/duration "PT30S"))
      params
      (catch Exception e
        {:status :failure
         :message :db-failed-to-update
         :details (.getMessage e)}))))

;;(defn get-dependencies
;;  "Get a dependency graph for a function. (not used by app anymore)"
;;  [function]
;;  (query {:find '[d1 d2]
;;          :where '[(depends d1 d0)
;;                   [d1 :stored-function/dependencies d2]]
;;          :args [{'d0 function}]
;;          :rules '[[(depends d1 d2)
;;                    [d1 :stored-function/dependencies d2]]
;;                   [(depends d1 d2)
;;                    [d1 :stored-function/dependencies x]
;;                    (depends x d2)]]}))
;;
;;(defn get-all-dependencies
;;  "Get the dependency graph for all functions. not used by app anymore."
;;  []
;;  (query {:find '[d1 d2]
;;          :where '[[d1 :stored-function/dependencies d2]]}))

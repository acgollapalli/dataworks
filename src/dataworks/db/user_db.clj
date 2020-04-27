(ns dataworks.db.user-db
  (:require
   [clojure.java.io :as io]
   [dataworks.utils.common :refer [read-string]]
   [crux.api :as crux]
   [mount.core :refer [defstate]]
   [tick.alpha.api :as time]))

(def kafka-settings
  (if-let [settings   (-> "config.edn"
                          slurp
                          read-string
                          :kafka-settings)]
    settings
    {:crux.kafka/bootstrap-servers "localhost:9092"
     :crux.kafka/tx-topic "dataworks.crux-transaction-log"
     :crux.kafka/doc-topic "dataworks.crux-docs"}))

(defstate user-db
  :start
  (crux/start-node
   (merge
    {:crux.node/topology '[crux.kafka/topology
                           crux.kv.rocksdb/kv-store]}
    kafka-settings))
  :stop
  (.close user-db))

(defn submit-tx
  "Shorthand for crux/submit-tx, using user-db"
  [transactions]
  (crux/submit-tx user-db transactions))

(defn query
  "Shorthand for crux/q using user-db"
  ([query]
   (crux/q (crux/db user-db) query))
  ([valid-time query]
   (crux/q (crux/db user-db valid-time) query))
  ([valid-time transaction-time query]
   (crux/q (crux/db user-db
                    valid-time
                    transaction-time)
           query)))

(defn entity
  "Shorthand for crux/entity using user-db"
  [entity-id]
  (crux/entity (crux/db user-db) entity-id))

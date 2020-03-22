(ns dataworks.db.user-db
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [crux.api :as crux]
   [mount.core :refer [defstate]]
   [tick.alpha.api :as time]))

;;(defn db-uri []
;;  (-> "config.edn"
;;      slurp
;;      edn/read-string
;;      :user-db-params))

(defstate user-db
  :start
  (crux/start-node {:crux.node/topology '[crux.standalone/topology]
                    :crux.kv/db-dir (str (io/file "user-db" "db"))})
  :stop
  (.close user-db))

(defn submit-tx
  "Shorthand for crux/submit-tx"
  [transactions]
  (crux/submit-tx user-db transactions))

(defn query
  "Shortahand for crux/q"
  ([query]
   (crux/q (crux/db user-db) query))
  ([valid-time query]
   (crux/q (crux/db user-db valid-time) query))
  ([valid-time transaction-time query]
   (crux/q (crux/db user-db valid-time transaction-time) query)))

(defn entity
  "Shorthand for crux/entity"
  [entity-id]
  (crux/entity (crux/db user-db) entity-id))

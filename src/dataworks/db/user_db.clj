(ns dataworks.db.user-db
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [crux.api :as crux]
   [mount.core :refer [defstate]]))

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

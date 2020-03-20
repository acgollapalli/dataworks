(ns dataworks.db.app-db
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [crux.api :as crux]
   [mount.core :refer [defstate]]))

;;(defn db-uri []
;;  (-> "config.edn"
;;      slurp
;;      edn/read-string
;;      :app-db-params))

(defstate app-db
  :start
  (crux/start-node {:crux.node/topology '[crux.standalone/topology]
                    :crux.kv/db-dir (str (io/file "app-db" "db"))})
  :stop
  (.close app-db))

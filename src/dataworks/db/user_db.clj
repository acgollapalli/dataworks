(ns dataworks.db.user-db
  (:require
   [monger.core :as mg]
   [monger.credentials :as mcr]
   [mount.core :refer [defstate]]
   [clojure.java.io :as io]
   [clojure.edn :as edn]))

(defn db-uri []
  (-> "config.edn"
      slurp
      edn/read-string
      :app-db-uri))


(defstate user-db
  ;; TODO ADD CREDENTIALS!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!11111111111111111
  :start
  (:db (mg/connect-via-uri (db-uri))))

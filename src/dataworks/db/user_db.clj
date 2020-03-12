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
      :user-db-uri))

(defstate db*
  :start
  (mg/connect-via-uri (db-uri))
  :stop
  (-> db* :conn mg/disconnect))

(defstate user-db
  :start
   (:db db*))

(ns dataworks.db.app-db
  (:require
   [monger.core :as mg]
   [monger.credentials :as mcr]))

(def app-db
  ;; TODO ADD CREDENTIALS!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!11111111111111111
  (mg/get-db (mg/connect) "Dataworks_AppData"))

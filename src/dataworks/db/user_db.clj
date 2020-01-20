(ns dataworks.db.user-db
  (:require
   [monger.core :as mg]
   [monger.credentials :as mcr]))

(def user-db
  ;; TODO ADD CREDENTIALS!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!11111111111111111
  (mg/get-db (mg/connect) "Dataworks_UserData"))

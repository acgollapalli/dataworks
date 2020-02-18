(ns dataworks.db.user-db
  (:require
   [monger.core :as mg]
   [monger.credentials :as mcr]
   [mount.core :refer [defstate]]))

(defstate user-db
  ;; TODO ADD CREDENTIALS!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!11111111111111111
  :start
  (mg/get-db (mg/connect) "Dataworks_UserData"))

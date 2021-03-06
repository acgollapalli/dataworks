(ns dataworks.auth.user
  (:require
   [clojure.edn :as edn]
   [dataworks.utils.auth :refer [login-resource
                                 register-resource
                                 admin-resource]]
   [dataworks.db.user-db :refer [user-db]]))

(def secret
  (try
    (-> "config.edn"
        slurp
        edn/read-string
        :user/jwt-secret)
    (catch Exception _ nil)))

(def login
  (login-resource user-db secret))

(def register
  (register-resource user-db))

(def admin
  (admin-resource user-db secret))

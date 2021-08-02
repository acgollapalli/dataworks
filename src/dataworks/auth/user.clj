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

(defn login
  []
  (login-resource user-db secret))

(defn register
  []
  (register-resource user-db))

(defn admin
  []
  (admin-resource user-db secret))

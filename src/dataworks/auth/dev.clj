(ns dataworks.auth.dev
  (:require
   [clojure.edn :as edn]
   [dataworks.db.app-db :refer [app-db]]
   [dataworks.utils.auth :refer :all]
   [dataworks.utils.common :refer [get-entity-param]]))

(def secret
  (try
    (-> "config.edn"
        slurp
        edn/read-string
        :dev/jwt-secret)
    (catch Exception _ nil)))

(def dev-authentication
  (bearer-auth "Developer" secret))

(defn make-authorize-by-fn
  [function-type]
  (make-authorize (get-entity-param :developer function-type)))

(defn login
  []
  (login-resource app-db secret))

(defn register
  []
  (register-resource app-db))

(defn admin
  []
  (admin-resource app-db secret))

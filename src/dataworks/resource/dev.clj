(ns dataworks.resource.dev
  (:require
   [dataworks.utils.common :refer :all]
   [clojure.edn :as edn]
   [dataworks.auth.dev :as auth]
   [dataworks.utils.auth :as auth-utils]
   [dataworks.db.app-db :refer [get-stored-function
                                get-stored-functions]]
   [dataworks.collector :refer [create-collector!
                                update-collector!]]
   [dataworks.collectors :refer [atomic-routes
                                 resource-map]]
   [dataworks.stream :refer [create-stream!
                               update-stream!]]
   [dataworks.transactor :refer [create-transactor!
                                 update-transactor!]]
   [dataworks.transformer :refer [create-transformer!
                                  update-transformer!]]
   [yada.yada :refer [resource as-resource]]
   [mount.core :refer [defstate]]))

(defn create! [function-type body]
  ((function-type
    {:collector create-collector!
     :stream create-stream!
     :transactor create-transactor!
     :transformer create-transformer!})
   body))

(defn update! [function-type name body]
  ((function-type
    {:collector update-collector!
     :stream update-stream!
     :transactor update-transactor!
     :transformer update-transformer!})
   name
   body))

(defn creation-resource [function-type]
  (resource
   {:id (get-entity-param function-type "create")
    :description (str "resource for new or all"
                      (stringify-keyword function-type) "s")
    :authentication auth/dev-authentication
    :authorization (auth/make-authorize-by-fn function-type)
    :methods {:get
              {:produces "application/edn"
               :response (fn [ctx]
                           (get-stored-functions
                            function-type))}
              :post
              {:consumes #{"application/edn"}
               :produces "application/edn"
               :response
               (fn [ctx]
                 (let [body (:body ctx)]
                   (if-failure-response
                     ctx
                     (create! function-type body)
                     406)))}}}))

(defn update-resource [function-type]
  (resource
   {:id (get-entity-param function-type "update")
    :description (str "resource for existing individual "
                      (stringify-keyword function-type) "s")
    :authentication auth/dev-authentication
    :authorization (auth/make-authorize-by-fn function-type)
    :path-info? true
    :methods {:get
              {:produces "application/edn"
               :response
               (fn [ctx]
                 (let [name (get-in ctx [:request :path-info])]
                   (get-stored-function name function-type)))}
              :post
              {:consumes #{"application/edn"}
               :produces "application/edn"
               :response
               (fn [ctx]
                 (let [name (get-in ctx [:request :path-info])
                       body (:body ctx)]
                   (if-failure-response
                     ctx
                     (update! function-type name body)
                     406)))}}}))

(def port
  (try
    (-> "config.edn"
        slurp
        edn/read-string
        :dev/port)
    (catch Exception _ nil)))

(defstate routes
  :start
  (if (and auth/secret port)
    ["/"
     [["api/"
       {"collector" (creation-resource :collector)
        "collector/" (update-resource :collector)
        "transactor" (creation-resource :transactor)
        "transactor/" (update-resource :transactor)
        "transformer" (creation-resource :transformer)
        "transformer/" (update-resource :transformer)
        "stream" (creation-resource :stream)
        "stream/" (update-resource :stream)
        "register" (auth/register)
        "login" (auth/login)
        "admin/user-roles/" (auth/admin)}]
      [true (as-resource nil)]]]
    (println "Must specify both :dev/jwt-secret"
             "and :dev/port to start dev server.")))

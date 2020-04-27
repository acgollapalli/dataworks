(ns dataworks.resource
  (:require
   [bidi.bidi :as bidi]
   [clojure.pprint :refer [pprint] :as p]
   [dataworks.utils.common :refer :all]
   [dataworks.authentication :as auth]
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
   [yada.yada :refer [resource as-resource]]))

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
    :authorization auth/dev-authorization
    :methods {:get
              {:produces "application/json"
               :response (fn [ctx]
                           (get-stored-functions
                            function-type))}
              :post
              {:consumes #{"application/json"}
               :produces "application/json"
               :response
               (fn [ctx]
                 (let [body (:body ctx)]
                   (create! function-type body)))}}}))

(defn update-resource [function-type]
  (resource
   {:id (get-entity-param function-type "update")
    :description (str "resource for existing individual "
                      (stringify-keyword function-type) "s")
    :authentication auth/dev-authentication ;; TODO hierarchical
    :authorization auth/dev-authorization   ;;      role auth
    :path-info? true
    :methods {:get
              {:produces "application/json"
               :response
               (fn [ctx]
                 (let [name (get-in ctx [:request :path-info])]
                   (get-stored-function name function-type)))}
              :post
              {:consumes #{"application/json"}
               :produces "application/json"
               :response
               (fn [ctx]
                 (let [name (get-in ctx [:request :path-info])
                       body (:body ctx)]
                   (update! function-type name body)))}}}))

(def user-sub
  (fn [ctx]
    (let [path-info (get-in ctx [:request :path-info])]
      (if-let [path (bidi/match-route
                     ["" @atomic-routes] path-info)]
        (@resource-map (:handler path))
        (as-resource nil)))))

(def user-resource
  (resource
   {:id :user
    :path-info? true
    :sub-resource user-sub}))

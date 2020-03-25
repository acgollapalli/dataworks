(ns dataworks.resource
  (:require
   [bidi.bidi :as bidi]
   [dataworks.common :refer :all]
   [dataworks.authentication :as auth]
   [dataworks.collector :refer [create-collector!
                                update-collector!
                                atomic-routes
                                resource-map]]
   [dataworks.internal :refer [create-internal!
                               update-internal!]]
   [dataworks.transactor :refer [create-transactor!
                               update-transactor!]]
   [yada.yada :refer [resource]]))

(defn create! [function-type body]
  ((function-type
   {:collector create-collector!
    :internal create-internal!
    :transactor create-transactor!})
   body))

(defn update! [function-type name body]
  ((function-type
   {:collector update-collector!
    :internal update-internal!
    :transactor update-transactor!})
   name
   body))

(defn creation-resource [function-type]
  (resource
   {:id (get-entity-param function-type "create")
    :description (str "resource for new or all"
                      (stringify-keyword function-type) "s")
    ;;:authentication auth/dev-authentication
    ;;:authorization auth/dev-authorization
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
    :parameters {:path {:id String}} ;; do I need plurumatic's schema thing?
    ;;:authentication auth/dev-authentication
    ;;:authorization auth/dev-authorization
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
      (when-let [path (bidi/match-route
                       ["" @atomic-routes] path-info)]
        (@resource-map (:handler path))))))

(def user-resource
  (resource
   {:id :user
    :path-info? true
    :sub-resource user-sub}))

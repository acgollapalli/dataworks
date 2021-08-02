(ns dataworks.resource.user
  (:require
   [bidi.bidi :as bidi]
   [clojure.edn :as edn]
   [yada.yada :refer [resource as-resource]]
   [dataworks.collectors :refer [atomic-routes resource-map]]
   [dataworks.auth.user :as auth]
   [mount.core :refer [defstate]]))

(defn match-route
  [path-info]
  (bidi/match-route ["" @atomic-routes] path-info))

(def user-sub
  (fn [ctx]
    (let [path-info (get-in ctx [:request :path-info])]
      (if-let [path (match-route path-info)]
        (@resource-map (:handler path))
        (as-resource nil)))))

(defn path-param-interceptor
  ([{:keys [request] :as ctx}]
   (assoc-in ctx
             [:parameters :path]
             (:route-params (match-route (:path-info request))))))

(def user-resource
  (yada.handler/append-interceptor
   (yada.yada/handler
    (resource
     {:id :user
      :path-info? true
      :sub-resource user-sub}))
   yada.swagger-parameters/parse-parameters
   path-param-interceptor))

(defstate port
  :start
  (try
    (-> "config.edn"
        slurp
        edn/read-string
        :user/port)
    (catch Exception _ nil)))

(defstate routes
  :start
  (if (and auth/secret port)
    ["/"
     {"api/" user-resource
      "register" (auth/register)
      "login"  (auth/login)
      "admin/user-roles/" (auth/admin)
      true (as-resource nil)}]
    (println "Must specify both :user/jwt-secret"
             "and :user/port to start user server.")))

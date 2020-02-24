(ns dataworks.authentication
  (:require
   [buddy.hashers :as hash]
   [buddy.sign.jwt :as jwt]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as st]
   [dataworks.db.app-db :refer [app-db]]
   [monger.collection :as mc]
   [monger.operators :refer :all]
   [monger.conversion :refer [to-object-id]]
   [monger.json]
   [tick.alpha.api :as time]
   [yada.yada :refer [as-resource] :as yada]))

(def secret
  (-> "config.edn"
      slurp
      edn/read-string
      :jwt-secret))

(defn create-token [{:keys [user roles]}]
  {:token
    (jwt/sign
     {:claims (pr-str {:user user :roles roles})
      :timeout (str (time/+
                     (time/date-time)
                     (time/new-duration 30 :minutes)))}
     secret)})

(defn token-verify [yada-syntax-map]
  (let [token (:yada.syntax/value yada-syntax-map)
        tkn (jwt/unsign token secret)
        timeout (:timeout tkn)
        claims (edn/read-string (:claims tkn))]
    (when (time/<= (time/date-time) (time/date-time timeout))
          claims)))

(defn authenticate [ctx token scheme]
  (token-verify token))

(defn authorize [ctx authenticate authorization]
  (let [claim-roles (edn/read-string (:roles authenticate))
        auth-roles (:custom/roles authorization)
        roles (st/intersection claim-roles auth-roles)]
    (if (empty? roles) nil roles)
    )
  )

(def dev-authentication
  {:realm "Developer"
   :scheme "Bearer"
   ;;:verify token-verify
   :authenticate authenticate})

(def dev-authorization
  {:authorize authorize
   :custom/roles #{:developer :admin}})

(defn get-user [user]
  (mc/find-one-as-map app-db "users" {:user user}))

(defn add-user [{:keys [user pass email roles]}]
  (dissoc
   (mc/insert-and-return app-db "users" {:user user
                                         :pass (hash/derive pass) ;;TODO remove hashed pass from response
                                         :email email
                                         :roles (pr-str roles)})
   :pass :_id))

(defn check-cred [{:keys [user pass]}]
  (if-let [user-doc (get-user user)]
    (if (= (hash/check pass (:pass user-doc)))
      (create-token user-doc)
      {:error "Incorrect Password"})
    {:error (str "User: " user " Not Found")}))

(def login
  (yada/resource
   {:id :login
    :description "This let's you log in"
    :methods {:post
              {:consumes #{"application/json"}
               :produces "application/json"
               :response
               (fn [ctx]
                 (let [body (:body ctx)]
                   (check-cred body)))}}}))

(defn new-user [{:keys [user pass email] :as params}]
  (if (mc/empty? app-db "users")
    (add-user (assoc params :roles #{:admin :developer}))
    (if (empty? (mc/find-maps app-db "users" {:user user}))
      (add-user (assoc params :roles #{}))
      {:error (str "username: " user "is taken.")})))

(def register
  (yada/resource
   {:id :register
    :description "This let's you create an account"
    :methods {:post
              {:consumes #{"application/json"}
               :produces "application/json"
               :response
               (fn [ctx]
                 (let [body (:body ctx)]
                   (new-user body)))}}}))

(ns dataworks.authentication
  (:require
   [buddy.hashers :as hash]
   [buddy.sign.jwt :as jwt]
   [clojure.edn :as edn]
   [clojure.set :as st]
   [crux.api :as crux]
   [dataworks.db.app-db :refer [app-db]]
   [tick.alpha.api :as tick]
   [yada.yada :as yada]))

(def secret
  (-> "config.edn"
      slurp
      edn/read-string
      :jwt-secret))

(defn create-token [{:keys [user roles]}]
  {:token
    (jwt/sign
     {:claims (pr-str {:user user :roles roles})
      :timeout (str (tick/+
                     (tick/date-time)
                     (tick/new-duration 30 :minutes)))}
     secret)})

(defn token-verify [yada-syntax-map]
  (let [token (:yada.syntax/value yada-syntax-map)
        tkn (jwt/unsign token secret)
        timeout (:timeout tkn)
        claims (edn/read-string (:claims tkn))]
    (when (tick/<= (tick/date-time) (tick/date-time timeout))
          claims)))

(defn authenticate [ctx token scheme]
  (token-verify token))

(defn authorize [ctx authenticate authorization]
  (let [claim-roles (edn/read-string (:roles authenticate))
        auth-roles (:custom/roles authorization)
        roles (st/intersection claim-roles auth-roles)]
    (if (empty? roles) nil roles)))

(def dev-authentication
  {:realm "Developer"
   :scheme "Bearer"
   ;;:verify token-verify
   :authenticate authenticate})

(def dev-authorization
  {:authorize authorize
   :custom/roles #{:developer :admin}})

(defn get-user [user]
  (crux/entity app-db (keyword "user" user)))


(defn add-user [{:keys [user pass email roles display-name]}]
  (crux/submit-tx app-db
                  [[:crux.tx/put
                    {:crux.db/id (keyword "user" user)
                     :user/user-name user
                     :user/display-name display-name
                     :user/email email
                     :user/roles roles
                     :user/pass (hash/derive pass)}]])
  (dissoc (get-user user)
          :user/pass))

(defn check-cred [{:keys [user pass]}]
  (if-let [user-doc (get-user user)]
    (if (hash/check pass (:pass user-doc))
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

(defn new-user [{:keys [user] :as params}]
  (if (empty?
       (crux/q (crux/db app-db)
               '{:find [e]
                 :where [[e :crux.db/id e]
                         [(clojure.string/starts-with? e ":user/")]]}))
    (add-user (assoc params :roles #{:admin :developer}))
    (if-not (get-user user)
      (add-user (assoc params :roles #{}))
      {:error (str "username: " user " is taken.")})))

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

(ns dataworks.authentication
  (:require
   [buddy.hashers :as hash]
   [buddy.sign.jwt :as jwt]
   [clojure.edn :as edn]
   [clojure.set :as st]
   [clojure.pprint :refer [pprint]]
   [crux.api :as crux]
   [dataworks.db.app-db :refer [app-db
                                entity
                                submit-tx
                                query]]
   [tick.alpha.api :as tick]
   [yada.yada :as yada]))

(def secret
  (-> "config.edn"
      slurp
      edn/read-string
      :jwt-secret))

(defn create-token [{:user/keys [user-name roles]}]
  {:token
    (jwt/sign
     {:claims (pr-str {:user user-name :roles roles})
      :timeout (str (tick/+
                     (tick/date-time)
                     (tick/new-period 30 :days)))}
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

(defn get-roles [roles]
  (let [namespaces (into #{} (map namespace roles))
        add-role (fn [roles nsp role]
                   (if (contains? namespaces nsp)
                     (conj roles role)
                     roles))]
    (-> roles
        (add-role "user" :user/all)
        (add-role "developer" :developer/all)
        (add-role "admin" :admin/all))))

(defn authorize
  [& roles]
  (fn
    [ctx authenticate authorization]
    (let [claim-roles (:roles authenticate)
        auth-roles (get-roles (into #{} roles))
        roles (st/intersection claim-roles auth-roles)]
    (if (empty? roles) nil roles))))

(def dev-authentication
  {:realm "Developer"
   :scheme "Bearer"
   :authenticate authenticate})

(def dev-authorization
  ;; Eventually Hierarchical role auth should be a thing
  {:authorize (authorize :developer/all)})

(defn get-user [user]
  (entity (keyword "user" user)))


(defn add-user [{:keys [user pass email roles display-name]}]
  (submit-tx [[:crux.tx/put
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
    (if (hash/check pass (:user/pass user-doc))
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
       (query
               '{:find [e]
                 :where [[e :crux.db/id e]
                         [(clojure.string/starts-with?
                           e ":user/")]]}))
    (add-user (assoc params :roles #{:admin/all
                                     :developer/all
                                     :user/all}))
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

(def admin-user-roles
  ;; TODO build role hierarchies.
  (yada/resource
   {:id :admin
    :description "This let's you administrate accounts"
    :authentication {:realm "Admin"
                     :scheme "Bearer"
                     :authenticate authenticate}
    :authorization {:authorize (authorize :admin/all)}
    :path-info? true
    :methods {:get
              {:produces "application/json"
               :response
               (fn [ctx]
                 (let [user (get-in ctx [:request :path-info])]
                   (dissoc (get-user user)
                           :user/pass)))}
              :post
              {:consumes #{"application/json"}
               :produces "application/json"
               :response
               (fn [ctx]
                 (let [user (get-in ctx [:request :path-info])
                       {:keys [roles]} (:body ctx)
                       current (get-user user)]
                   (if current
                     (try (submit-tx
                           [[:crux.tx/cas
                             current
                             (assoc current
                                    :user/roles
                                    (into #{}
                                          (map keyword)
                                          roles))]])
                          {:status :success
                           :message :user-roles-updated}
                          (catch Exception e
                            {:status :failure
                             :message (.getMessage e)}))
                     {:status :failure
                      :message :user-not-found})))}}}))

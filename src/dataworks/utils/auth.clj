(ns dataworks.utils.auth
  (:require
   [buddy.hashers :as hash]
   [buddy.sign.jwt :as jwt]
   [clojure.edn :as edn]
   [clojure.set :as st]
   [crux.api :as crux]
   [tick.alpha.api :as tick]
   [yada.yada :as yada]))

(defn create-token [{:user/keys [user-name roles]} secret]
  {:token
   (jwt/sign
    {:claims {:user user-name :roles roles}
     :timeout (str (tick/+
                    (tick/date-time)
                    (tick/new-period 30 :days)))}
    secret)})

(defn token-verify [yada-syntax-map secret]
  (let [token (:yada.syntax/value yada-syntax-map)
        tkn (jwt/unsign token secret)
        timeout (:timeout tkn)
        claims (set (:claims tkn))]
    (when (tick/<= (tick/date-time) (tick/date-time timeout))
      claims)))

(defn get-roles
  "Takes a set of keywords representing roles.
   When a role is a namespaced keyword, like :your-namespace/your-role,
   adds :your-namespace/all to list of roles."
  [roles]
  (into #{:admin/all :developer/all}
      (comp
       (mapcat (juxt identity #(some-> % namespace (keyword "all"))))
       (filter some?))
      roles))

(defn bearer-auth
  [realm secret]
  {:realm realm
   :scheme "Bearer"
   :authenticate (fn [ctx token scheme]
                   (token-verify token secret))})

(defn make-authorize
  "Used to "
  [& roles]
  {:authorize
   (fn [ctx authenticate authorization]
     (let [claim-roles (->> authenticate vec (into {}) :roles (map keyword) set)
           auth-roles (get-roles (into #{} roles))
           roles (st/intersection claim-roles auth-roles)]
       (if (empty? roles) nil roles)))})

(defn get-user [db user]
  (crux/entity (crux/db db) (keyword "user" user)))

(defn add-user [db {:keys [user pass email roles display-name]}]
  (crux/submit-tx db
                  [[:crux.tx/put
                    {:crux.db/id (keyword "user" user)
                     :user/user-name user
                     :user/display-name display-name
                     :user/email email
                     :user/roles roles
                     :user/pass (hash/derive pass)}]])
  (dissoc (get-user db user) :user/pass))

(defn check-cred [db {:keys [user pass]} secret]
  (if-let [user-doc (get-user db user)]
    (if (hash/check pass (:user/pass user-doc))
      (create-token user-doc secret)
      {:error "Incorrect Password"})
    {:error (str "User: " user " Not Found")}))

(defn login-resource
  [db secret]
  (yada/resource
   {:id :login
    :description "This let's you log in"
    :methods {:post
              {:consumes #{"application/json"}
               :produces "application/json"
               :response
               (fn [ctx]
                 (let [body (:body ctx)]
                   (check-cred db body secret)))}}}))

(defn new-user [db {:keys [user] :as params}]
  (if (empty?
       (crux/q (crux/db db)
        '{:find [e]
          :where [[e :crux.db/id e]
                  [(clojure.string/starts-with?
                    e ":user/")]]}))
    (add-user db (assoc params :roles #{:admin/all}))
    (if-not (get-user db user)
      (add-user db (assoc params :roles #{}))
      {:error (str "username: " user " is taken.")})))

(defn register-resource
  [db]
  (yada/resource
   {:id :register
    :description "This let's you create an account"
    :methods {:post
              {:consumes #{"application/json"}
               :produces "application/json"
               :response
               (fn [ctx]
                 (let [body (:body ctx)]
                   (new-user db body)))}}}))

(defn admin-resource
  [db secret]
  ;; TODO build role hierarchies.
  (yada/resource
   {:id :admin
    :description "This let's you administrate accounts"
    :authentication (bearer-auth "Admin" secret)
    :authorization (make-authorize)
    :path-info? true
    :methods {:get
              {:produces "application/json"
               :response
               (fn [ctx]
                 (let [user (get-in ctx [:request :path-info])]
                   (get-user db user)))}
              :post
              {:consumes #{"application/json"}
               :produces "application/json"
               :response
               (fn [ctx]
                 (let [user (get-in ctx [:request :path-info])
                       {:keys [roles]} (:body ctx)
                       current (get-user user)]
                   (if current
                     (try (crux/submit-tx db
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

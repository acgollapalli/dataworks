(ns dataworks.transactor
  (:require
   [cheshire.core :as cheshire]
   [clj-http.client :as client]
   [clojure.core.async :refer [go] :as async]
   [clojure.pprint :refer [pprint] :as p]
   [crux.api :as crux]
   [dataworks.db.app-db :refer [app-db]]
   [dataworks.authentication :as auth]
   [dataworks.transactors :refer [transactor-ns]]
   [mount.core :refer [defstate] :as mount]
   [tick.alpha.api :as tick]
   [yada.yada :refer [as-resource] :as yada]))

;; A transactor does a thing when called.
;; A transactor is a function, though inherently not a pure one.
;; You may specify arguments for a transactor.
;; A transactor is called via the transact! call, in which the name of the
;; function is the first argument, and any arguments for the transactor are
;; the subsequent arguments. ex: (transact! :your-transactor arg1 arg2)
;; The transactor name may be a clojure keyword or a string.
;; The transact! call is available to all other stored functions.
;;
;; Example:
;;
;; POST to app/transactor
;; {
;;  "name": "text",
;;  "func": "(fn [body]
;;             (let [twilio-sid \"YOUR-TWILIO-SID\"
;;                   twilio-token \"YOUR-TWILIO-TOKEN\"
;;                   hello-body {:Body (str body)
;;                               :From \"TWILIO-PHONE-NUMBER\"
;;                               :To \"YOUR-PHONE-NUMBER\"}]
;;                  (client/post (str \"https://api.twilio.com/2010-04-01/Accounts/\"
;;                                    twilio-sid
;;                                    \"/Messages.json\")
;;                  {:basic-auth [twilio-sid twilio-token] :form-params hello-body})))"
;; }

(def transactor-map
  (atom {}))

;; TODO Add validation here.
(defn evalidate [func]
  (binding [*ns* transactor-ns]
    (let [f (eval func)]
      (if (fn? f)
        f))))

(defn get-transactors []
  (do (println "Getting Transactors!")
      (map #(crux/entity (crux/db app-db) (first %))
           (crux/q (crux/db app-db)
                   '{:find [e]
                     :where [[e :stored-function/type :transactor]
                             [e :transactor/name n]
                             [(keyword? n)]
                             [e :transactor/func f]]}))))

(defn get-transactor [name] ;;TODO maybe wrap this in something??
  (crux/entity (crux/db app-db) (keyword "transactor" name)))

(defn add-transactor!
  ([{:transactor/keys [name func] :as params}]
   (if-let [f (evalidate func)]
     (add-transactor! name f)))
  ([name f]
   (swap! transactor-map #(assoc % (keyword name) f))))

(defn new-transactor! [{:transactor/keys [name func] :as params}]
  (if-let [f (evalidate func)]
    (if (crux/submit-tx app-db [[:crux.tx/put params]])
      (do (add-transactor! name f)
          {:status :success
           :message :transactor-added
           :details params})
      {:status :failure
       :message :db-failed-to-update})
    {:status :failure
     :message :function-failed-to-evalidate}))

(defn db-fy
  ([{:keys [name func]}]
  {:crux.db/id (keyword "transactor" name)
   :transactor/name (keyword name)
   :transactor/func func
   :stored-function/type :transactor})
  ([path-name {:keys [name func] :as params}]
   (if name
     (db-fy params)
     (db-fy (assoc params :name path-name)))))

(defn create-transactor! [{:keys [name] :as params}]
  (if (get-transactor name)
    {:status :failure
     :message :transactor-already-exists}
    (new-transactor! (db-fy params))))

;;TODO Add validation step of function
(defn update-transactor! [name params]
  (let [current-transactor (get-transactor name)
        new-transactor (db-fy name params)]
    (cond (not= (:crux.db/id current-transactor)
                (:crux.db/id new-transactor))
          {:status :failure
           :message :name-param-does-not-match-path}
          (= (:transactor/func current-transactor)
             (:transactor/func new-transactor))
          {:status :failure
           :message :no-change-from-current-func}
          :else (new-transactor! params))))

(defn start-transactors! []
  (do (println "Starting Transactors!")
      (let [trs (get-transactors)
            started-trs (map add-transactor! trs)]
        (if (= (count trs)
               (count started-trs))
          (println "Transactors Started!")
          (println "Transactors Failed to Start.")))))

(defstate transactor-state
  :start (start-transactors!)
  :stop (reset! transactor-map {}))

(defn transact! [tname & args]
  (go (((keyword tname) @transactor-map) args)))

(def transactors
  (yada/resource
   {:id :transactors
    :description "this is the resource that returns all transactor documents"
    ;;:authentication auth/dev-authentication
    ;;:authorization auth/dev-authorization
    :methods {:get
              {:produces "application/json"
               :response (fn [ctx] (get-transactors))}
              :post
              {:consumes #{"application/json"}
               :response
               (fn [ctx]
                 (let [body (:body ctx)]
                   (create-transactor! body)))}}}))

(def transactor
  (yada/resource
   {:id :transactor
    :description "resource for individual transactor"
    ;;:authentication auth/dev-authentication
    ;;:authorization auth/dev-authorization
    :methods {:get
              {:produces "application/json"
               :response
               (fn [ctx]
                 (let [name (get-in ctx [:request :path-info])]
                   (if-let [transactor (get-transactor name)]
                     {:status :success
                      :message :transactor-retrieved
                      :details transactor}
                     {:status :failure
                      :message :transactor-not-found})))}
              :post
              {:consumes #{"application/json"}
               :produces "application/json"
               :response
               (fn [ctx]
                 (let [name (get-in ctx [:request :path-info])
                       body (:body ctx)]
                    (update-transactor! name body)))}}}))

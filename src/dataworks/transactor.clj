(ns dataworks.transactor
  (:require
   [cheshire.core :as cheshire]
   [clj-http.client :as client]
   [clojure.core.async :refer [go] :as async]
   [clojure.pprint :refer [pprint] :as p]
   [dataworks.db.app-db :refer [app-db]]
   [dataworks.db.user-db :refer [user-db]]
   [dataworks.authentication :as auth]
   [dataworks.transactors :refer [transactor-ns]]
   [monger.collection :as mc]
   [monger.operators :refer :all]
   [monger.conversion :refer [to-object-id]]
   [monger.result :as result]
   [monger.json]
   [mount.core :refer [defstate] :as mount]
   [tick.alpha.api :as time]
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

(defn evalidate [f]
  (binding [*ns* transactor-ns]
    (eval (read-string f))))

(defn get-transactors []
  (do (println "Getting Transactors!")
      (let [trs (mc/find-maps app-db "transactors")]
        trs)))

(defn get-transactor [id]
  (mc/find-one-as-map app-db "transactors" {:name (keyword id)}))

(defn add-transactor! [{:keys [name func] :as params}]
  (do
    (swap! transactor-map
           (fn [t-map]
             (assoc t-map
                    (keyword name)
                    (evalidate func))))))

(defn create-transactor! [params]
  (if-let [tran (mc/insert-and-return app-db
                                      "transactors"
                                      params)]
      (add-transactor! params)))

;;TODO Add validation step of function
(defn update-transactor! [id params]
  (let [update  (mc/update "transactors"
                           {:name (keyword id)}
                           params)]
    (if (result/acknowledged? update)
      (do
        (add-transactor! params)
        "success")
      "failure")))

(defn start-transactors! []
  (do
    (println "Starting Transactors!")
    (let [trs (get-transactors)
          started-trs (map add-transactor! trs)]
      (if (= (count trs)
             (count started-trs))
        (println "Transactors Started!")
        (println "Transactors Failed to Start.")))))

(defstate transactor-state
  :start
  (start-transactors!)
  :stop
  (reset! transactor-map {}))

(defn transact! [tname & args]
  (go
    (((keyword tname) @transactor-map ) args)))

(def transactors
  (yada/resource
   {:id :transactors
    :description "this is the resource that returns all transactor documents"
    :authentication auth/dev-authentication
    :authorization auth/dev-authorization
    ;;:access-control auth/developer
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
    :parameters {:path {:id String}} ;; do I need plurumatic's schema thing?
    :authentication auth/dev-authentication
    :authorization auth/dev-authorization
    ;;:access-control auth/developer
    :methods {:get
              {:produces "application/json"
               :response
               (fn [ctx]
                 (let [id (get-in ctx [:request :route-params :id])]
                   (get-transactor id)))}
              :post
              {:consumes #{"application/json"}
               :produces "application/json"
               :response
               (fn [ctx]
                 (let [id (get-in ctx [:request :route-params :id])
                       body (:body ctx)]
                   {:update-status  ;; TODO make this less shitty
                    (update-transactor! id body)}))}}}))

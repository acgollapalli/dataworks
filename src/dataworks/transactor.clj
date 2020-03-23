(ns dataworks.transactor
  (:require
   [cheshire.core :as cheshire]
   [clj-http.client :as client]
   [clojure.core.async :refer [go] :as async]
   [clojure.pprint :refer [pprint] :as p]
   [crux.api :as crux]
   [dataworks.db.app-db :refer [app-db]]
   [dataworks.authentication :as auth]
   [dataworks.common :refer :all]
   [dataworks.transactors :refer [transactor-ns]]
   [mount.core :refer [defstate] :as mount]
   [tick.alpha.api :as tick]
   [yada.yada :refer [as-resource] :as yada]))

;; A transactor does a thing when called.
;; A transactor is a function, though inherently not a pure one.
;; You may specify arguments for a transactor.
;; A transactor is called via the transact! call, in which the
;; name of the function is the first argument, and any arguments
;; for the transactor are the subsequent arguments.
;; ex: (transact! :your-transactor arg1 arg2)
;; The transactor name may be a clojure keyword or a string.
;; The transact! call is available to all other stored functions.

(def transactor-map
  (atom {}))

;; TODO Add validation here.
(defn evalidate [{:keys [function]}]
  (println "evalidating" function)
  (binding [*ns* transactor-ns]
    (try (eval function)
         (catch Exception e
           {:status :failure
            :message :unable-to-evalidate-function
            :details (.getMessage e)}))))

(defn function? [func]
  (if (fn? func)
    func
    {:status :failure
     :message :function-param-does-not-evaluate-to-function}))
(defn fn-conj [func params]
  [params func])

(defn evalidated? [params]
  (println "evalidating")
  (let [vec-params (if (vector? params)
                     params
                     [params])
        conj-params #(conj vec-params %)]
    (->? vec-params
         first
         evalidate
         function?
         conj-params)))

(defn add-transactor!
  ([{:transactor/keys [name function] :as params}]
   (if-let [f (evalidate function)]
     (add-transactor! params f)))
  ([{:transactor/keys [name]} f]
   (swap! transactor-map #(assoc % (keyword name) f))))

(defn apply-transactor! [params]
  (apply add-transactor! params))

(defn db-fy
  ([{:keys [name function]}]
   {:crux.db/id (keyword "transactor" name)
    :transactor/name (keyword name)
    :transactor/func function
    :stored-function/type :transactor})
  ([path-name {:keys [name function] :as params}]
   (if name
     (db-fy params)
     (db-fy (assoc params :name path-name)))))

(defn create-transactor! [transactor]
  (->? transactor
       (blank-field? :name :function)
       valid-name?
       (parseable? :function)
       (function-already-exists? :transactor)
       evalidated?
       (general-added-to-db? db-fy)
       apply-transactor!
       ))

(defn update-transactor! [path-name transactor]
  (->? transactor
       (updating-correct-function? path-name)
       (blank-field? :function)
       (parseable? :function)
       (add-current-stored-function :transactor)
       (has-params? :transactor :name)
       (general-valid-update? :transactor :function)
       evalidated?
       (general-added-to-db? db-fy)
       apply-transactor!))

(defn start-transactors! []
  (do (println "Starting Transactors!")
      (let [trs (get-stored-functions :transactor)
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
    :description "this is the resource that returns
                  all transactor documents"
    ;;:authentication auth/dev-authentication
    ;;:authorization auth/dev-authorization
    :methods {:get
              {:produces "application/json"
               :response (fn [ctx]
                           (get-stored-functions :transactor))}
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
                   (if-let [transactor (get-stored-function
                                        name
                                        :transactor)]
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
                 (let [name (get-in ctx [:request])
                       body (:body ctx)]
                   (update-transactor! name body)))}}}))

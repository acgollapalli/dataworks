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
(defn evals? [{:keys [name function] :as params}]
  (println "evalidating" name)
  (binding [*ns* transactor-ns]
    (try (eval function)
         (catch Exception e
           {:status :failure
            :message :unable-to-evalidate-function
            :details (.getMessage e)}))))

(defn function? [function]
  (if (fn? function)
    function
    {:status :failure
     :message :function-param-does-not-evaluate-to-function}))

(defn evalidate [params]
  (if-vector-conj params
    evalidate
    (->? params
         evals?
         function?)))

(defn add-transactor!
  ([{:transactor/keys [name function] :as params}]
   (if-let [f (evalidate function)]
     (add-transactor! params f)))
  ([{:transactor/keys [name] :as params} f]
   (swap! transactor-map #(assoc % (keyword name) f))
   {:status :success
    :message :transactor-added
    :details params}))

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
       evalidate
       (added-to-db? db-fy)
       apply-transactor!))

(defn update-transactor! [path-name transactor]
  (->? transactor
       (updating-correct-function? path-name)
       (blank-field? :function)
       (parseable? :function)
       (add-current-stored-function :transactor)
       (has-params? :transactor :name)
       (valid-update? :transactor :function)
       evalidate
       (added-to-db? db-fy)
       apply-transactor!))

(defn start-transactors! []
  (do (println "Starting Transactors!")
      (let [trs (get-stored-functions :transactor)
            status (map add-transactor! trs)]
        (if (= (count trs)
               (count @transactor-map))
          (println "Transactors Started!")
          (println "Transactors Failed to Start:"
                   (map :name status))))))

(defstate transactor-state
  :start (start-transactors!)
  :stop (reset! transactor-map {}))

(defn transact! [tname & args]
  (go (((keyword tname) @transactor-map) args)))

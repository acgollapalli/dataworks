(ns dataworks.transformer
  (:require
   [clojure.core.async :refer [close!
                               chan
                               tap]]
   [dataworks.utils.common :refer :all]
   [dataworks.db.app-db :refer :all]
   [dataworks.utils.stream :as stream]
   [dataworks.app-graph :refer [stream!
                                node-state]]
   [dataworks.transformers :refer [transformer-ns
                                   transformer-map]]
   [mount.core :refer [defstate]]))

;; TODO Add validation here.
(defn evals? [{:transformer/keys [name function] :as params}]
  (println "evalidating" name)
  (binding [*ns* transformer-ns]
    (try (eval function)
         (catch Exception e
           {:status :failure
            :message :unable-to-evalidate-function
            :details (.getMessage e)}))))

(defn evalidate [params]
  (if-vector-conj params
                  "params"
                  (->? params
                       evals?
                       function?)))

(defn add-transformer!
  ([params]
   (let [f (evalidate params)]
     (if (not= (:status f) :failure)
       (apply add-transformer! f)
       f))) ;;... to pay respects
  ([{:transformer/keys [name] :as params} f]
   (swap! transformer-map #(assoc % (keyword name) f))
   {:status :success
    :message :transformer-added
    :details params}))

(defn apply-transformer! [params]
  (stream! :kafka/dataworks.internal.functions
           (select-keys (first params)
                        [:crux.db/id
                         :stored-function/type]))
  (apply add-transformer! params))

(defn db-fy
  [params]
  (if-vector-first params
                   db-fy
                   {:crux.db/id (get-entity-param (:name params) :transformer)
                    :transformer/name (keyword (:name params))
                    :transformer/function (:function params)
                    :stored-function/type :transformer}))

(defn create-transformer! [transformer]
  (->? transformer
       (blank-field? :name :function)
       valid-name?
       (parseable? :function)
       (function-already-exists? :transformer)
       db-fy
       (dependencies? :transformer)
       evalidate
       added-to-db?
       apply-transformer!))

(defn update-transformer! [path-name transformer]
  (->? transformer
       (updating-correct-function? path-name)
       (blank-field? :function)
       (parseable? :function)
       (add-current-stored-function :transformer)
       (valid-update? :transformer :function)
       db-fy
       (dependencies? :transformer)
       evalidate
       added-to-db?
       apply-transformer!))

(defn start-transformers! []
  (do (println "Starting Transformers!")
      (let [trs (get-stored-functions :transformer)
            do-this (clojure.pprint/pprint trs)
            status (map add-transformer! trs)]
        (if (every? #(= (:status %) :success) status)
          (println "Transformers Started!")
          (println "Transformers Failed to Start:"
                   (map :name status))))))

(defstate transformer-chan
  :start
  (let [c (chan
           10
           (comp
            (filter
             #(= (:stored-function/type %)
                 (keyword :transformer)))
            (map  ;; TODO add error handling.
             (fn [{:crux.db/keys [id]}]
               (clojure.pprint/pprint (entity id))
               (add-transformer! (entity id))))))]
    (stream/take-while c)
    (tap (get-in
          node-state
          [:stream/dataworks.internal.functions
           :output])
          c))
  :stop
  (close! transformer-chan))

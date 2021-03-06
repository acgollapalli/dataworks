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
  (let [f (->? params evals? function?)]
    (if (= :failure (:status f))
      f
      (assoc params :eval/function f))))

(defn add-transformer!
  [{:transformer/keys [name] :eval/keys [function] :as params}]
  (if function
    (do
      (swap! transformer-map #(assoc % (keyword name) function))
      {:status :success
       :message :transformer-added
       :details (select-ns-keys params :transformer)})
    (->? params evalidate add-transformer!)))

(defn apply-transformer! [params]
  (stream! :kafka/dataworks.internal.functions
           (select-keys params
                        [:crux.db/id
                         :stored-function/type]))
  (add-transformer! params))

(defn create-transformer! [transformer]
  (->? transformer
       (set-ns :transformer)
       (missing-field? :name)
       (blank-field? :function)
       valid-name?
       (parseable? :function)
       function-already-exists?
       evalidate
       added-to-db?
       apply-transformer!))

(defn update-transformer! [path-name transformer]
  (->? transformer
       (set-ns :transformer)
       (updating-correct-function? path-name)
       valid-name?
       (blank-field? :function)
       (parseable? :function)
       add-current-stored-function
       (valid-update? :function)
       evalidate
       added-to-db?
       apply-transformer!))

(defn start-transformers! []
  (do (println "Starting Transformers!")
      (let [trs (get-stored-functions :transformer)
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
               (add-transformer! (entity id))))))]
    (stream/take-while c)
    (tap (get-in
          node-state
          [:stream/dataworks.internal.functions
           :output])
          c))
  :stop
  (close! transformer-chan))

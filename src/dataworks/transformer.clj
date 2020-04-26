(ns dataworks.transformer
  (:require
   [clojure.core.async :refer [<! >! go-loop sub]]
   [dataworks.utils.common :refer :all]
   [dataworks.db.app-db :refer :all]
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
  ([{:transformer/keys [name function] :as params}]
   (if-let [f (evalidate params)]
     (apply add-transformer! f)))
  ([{:transformer/keys [name] :as params} f]
   (swap! transformer-map #(assoc % (keyword name) f))
   {:status :success
    :message :transformer-added
    :details params}))

(defn apply-transformer! [params]
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
       dependencies?
       evalidate
       added-to-db?
       apply-transformer!))

(defn update-transformer! [path-name transformer]
  (->? transformer
       (updating-correct-function? path-name)
       (blank-field? :function)
       (parseable? :function)
       (add-current-stored-function :transformer)
       (has-params? :transformer :name)
       (valid-update? :transformer :function)
       db-fy
       dependencies?
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
                   (map :name status))))
      (let [transformer-chan (chan)]
        (sub function-pub :transformer transformer-chan)
        (go-loop []
          (let [{:keys [id]} (<! transformer-chan)]
            (add-transformer! (get-stored-function id))
            (doseq
             [[function type] (query {:find '[e type]
                                      :where
                                      [['e
                                        :stored-function/dependencies
                                        id]
                                       ['e :stored-function/type 'type]]})]
              (>! function-chan {:crux.db/id function
                                 :stored-function/type type})))
          (recur)))))

(defstate transformer-state
  :start (start-transformers!)
  :stop (reset! transformer-map {}))

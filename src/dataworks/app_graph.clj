(ns dataworks.app-graph
  (:require
   [clojure.core.async :refer [>! close! go]]
   [dataworks.db.app-db :refer [entity
                                get-dependencies]]
   [dataworks.utils.kafka :refer [consumer-instance]]
   [dataworks.utils.common :refer [order-nodes]]
   [dataworks.utils.stream :as stream]
   [mount.core :refer [defstate]]))

(def nodes
  (atom {}))

(defn stream!
  "This function is the dataworks-app stream function.
   It only accesses the internal nodes, rather than the ones
   that exist in dataworks.streams"
  [stream data]
  (if-let [channel (get-in @nodes [stream :input])]
    (go (>! channel data))))

(defn fn-stream
  [fn-type start-fn]
  {:stream/name (keyword "stream" fn-type)
   :stream/buffer 10
   :stream/upstream
   #{:stream/internal.functions}
   :stream/transducer (comp
                       (filter
                        #(= (:stored-function/type
                             %)
                            (keyword fn-type)))
                       (map  ;; TODO add error handling.
                        (fn [{:crux.db/keys [id]}]
                          (start-fn (entity id)))))})

(def streams
  (concat
   (list
    ;; let's us know when functions need to be updated
    {:stream/name :kafka/dataworks.internal.functions
     :stream/buffer 10
     :stream/instance (consumer-instance
                       "dataworks.internal.functions"
                       nil
                       :edn
                       {"group.id"
                        (str (java.util.UUID/randomUUID))})
     :stream/transducer (map :value)}

    {:stream/name :stream/internal.functions
     :stream/upstream #{:kafka/dataworks.internal.functions}
     :stream/buffer 1000
     :stream/transducer (map
                         (fn [{:crux.db/keys [id]}]
                           (doseq [dep (order-nodes
                                        name
                                        (get-dependencies
                                         id))]
                             (stream!
                              ;; will this cause deps to go
                              ;; out of order if there are more
                              ;; that 1000 deps to handle?
                              :stream/internal.functions
                              dep))))})
   ;; Below we create streams for each of our stored function
   ;; types, so they know when a function has been updated on
   ;; another node.
   (map fn-stream
        (list
         "collector"
         "stream"
         "transactor"
         "transformer")
        (list
         dataworks.collector/add-collector!
         dataworks.stream/start-stream!
         dataworks.transactor/add-transactor!
         dataworks.transformer/add-transformer!))))

(def edges
  (into []
        (map stream/get-edges)
        streams))

(defstate node-state
  :start
  (reset! nodes
          (into {}
                (map
                 (juxt
                  :stream/name
                  (fn [{:stream/keys [name buffer transducer
                                      error-handler] :as stream}]
                    (stream/get-node stream buffer transducer
                                     error-handler))))
                streams))
  :stop
  (map close!
       (apply concat
              (map stream/channel-filter
                   (map vals (vals @nodes))))))

(defstate edge-state
  :start
  (stream/apply-graph! @nodes edges))

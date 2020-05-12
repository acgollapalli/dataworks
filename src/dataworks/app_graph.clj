(ns dataworks.app-graph
  (:require
   [clojure.core.async :refer [>! close! go]]
   [dataworks.utils.function :refer [start-function-xform]]
   [dataworks.utils.kafka :refer [consumer-instance]]
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

(def streams
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

   {:stream/name :stream/dataworks.internal.functions
    :stream/upstream #{:kafka/dataworks.internal.functions}
    :stream/buffer 1000
    :stream/transducer start-function-xform}))

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
                  (fn [{:stream/keys [buffer transducer
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

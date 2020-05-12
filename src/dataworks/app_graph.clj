(ns dataworks.app-graph
  (:require
   [clojure.core.async :refer [>! close! go]]
   [dataworks.utils.kafka :refer [consumer-instance]]
   [dataworks.utils.common :refer [print-cont]]
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
    :stream/buffer 10
    :stream/transducer (map print-cont)}))

(def edges
  (into []
        (comp (map stream/get-edges)
              cat)
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
  (stream/apply-graph! edges @nodes))

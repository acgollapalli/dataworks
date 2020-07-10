(ns dataworks.app-graph
  (:require
   [clojure.core.async :refer [>! close! go untap-all]]
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

(defstate streams
  :start
  (list
   ;; let's us know when functions need to be updated
   {:stream/name :kafka/dataworks.internal.functions
    :eval/buffer 10
    :eval/instance (consumer-instance
                    {:topic "dataworks.internal.functions"
                     :name (str (java.util.UUID/randomUUID))
                     :format :edn})
    :eval/transducer (map :value)}

   {:stream/name :stream/dataworks.internal.functions
    :stream/upstream #{:kafka/dataworks.internal.functions}
    :eval/buffer 10
    :eval/transducer (map print-cont)}))

(defstate edges
  :start
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
                  (fn [stream]
                    (stream/get-node stream))))
                streams))
  :stop
  (map close!
       (apply concat
              (map stream/channel-filter
                   (map vals (vals @nodes))))))

(defstate edge-state
  :start
  (stream/apply-graph! edges node-state)
  :stop
  (map (comp untap-all :output) (vals node-state)))

(ns dataworks.app-graph
  (:require
   [clojure.core.async :refer [>! <! go-loop chan close!
                               sliding-buffer dropping-buffer
                               take! go mult put! tap]]
   [dataworks.utils.kafka :refer [consumer-instance]]
   [dataworks.stream :as stream]
   [mount.core :refer [defstate]]))

(defn fn-stream
  [fn-type]
  {:stream/name (keyword "stream" fn-type)
   :stream/buffer 10
   :stream/upstream
   #{:kafka/dataworks.internal.functions}
   :stream/transducer (filter
                       #(= (:stored-function/type
                            %)
                           (keyword fn-type)))})



(def streams
  (concat
   (list
    ;; let's us know when functions need to be updated
    {:stream/name :kafka/dataworks.internal.functions
     :stream/buffer 10
     :stream/instance (consumer-instance
                       "dataworks.internal.functions"
                       dataworks.heartbeat/uuid)}
    ;; coordinates internals
    {:stream/name :stream/responsibilities}
    {:stream/name :stream/internal.events
     :stream/upstream #{:stream/responsibilities
                        :kafka/dataworks.internal.internals}})
   ;; heartbeat goea here
   ;; Below we create streams for each of our stored function
   ;; types, so they know when a function has been updated on
   ;; another node.
   (map fn-stream
        (list
         "collector"
         "stream"
         "transactor"
         "transformer"))))

(defn get-node
  [stream buffer transducer error-handler instance]
  (case (namespace name)
    "kafka" (stream/handle-topic stream
                          buffer
                          transducer
                          error-handler
                          instance)
    "stream" (stream/handle-stream stream
                            buffer
                            transducer
                            error-handler)))

(defstate nodes
  :start
  (into {}
        (map
         (juxt
          :stream/name
          (fn [{:stream/keys [name buffer transducer
                              error-handler] :as stream}]
            (get-node stream buffer transducer
                             error-handler))))
        streams)
  :stop
  (map close!
       (apply concat
              (map stream/channel-filter
                   (map vals (vals nodes))))))

(defstate edges
  :start
  (into []
        (map stream/get-edges)
        streams))



(defn stream!
  "This function is the dataworks-app stream function.
   It only accesses the internal nodes, rather than the ones
   that exist in dataworks.streams"
  [stream data]
  (if-let [channel (get-in nodes [stream :input])]
    (go (>! channel data))))

(ns dataworks.utils.stream
  (:require
   [clojure.core.async :refer [go put! take! <! mult
                               tap go-loop chan alt!
                               timeout >!]]
   [dataworks.utils.common :refer :all]
   [dataworks.utils.kafka :require [consumer-instance
                                    consume-records
                                    produce!] :as kafka]))

(defn get-edges
  [{:stream/keys [name upstream]}]
  (map
   (comp
    (partial into [])
    (partial conj (list name)))
   upstream))

(defn construct-graph
  [nodes]
  (into []
        (comp
         (map get-edges)
         cat)))

(defn query-graph
  "gets all edges immediately connected or downstream
   from stream node."
  [stream graph]
  (loop [streams #{stream} subgraph graph]
    (let [working-set (filter #(streams (first %)) subgraph)]
      (println working-set)
      (if (empty? working-set)
        (into [] (filter (fn [edge]
                           (or (streams (first edge))
                               (streams (second edge))))
                         graph))
        (recur (apply
                conj
                streams
                (map second working-set))
               (filter #(nil? (streams (first %)))
                       subgraph))))))

(defn apply-graph!
  [graph nodes]
  (map (fn [[input output]]
         (tap (get-in nodes [input :output])
              (get-in nodes [output :input])))
       graph))

(defn handle-topic
  "When the namespace of the stream name is kafka.
   Represents a kafka topic.
   Please note that any supplied transducer is only used on
   the consumer side, rather than the producer side."
  ([{:stream/keys [name format] :as stream}
    buffer transducer error-handler]
   (handle-topic stream buffer transducer error-handler
                (apply kafka/consumer-instance
                       (if-conj [(clojure.core/name name)
                                 "dataworks"]
                                   format))))
  ([{:stream/keys [name format]} buffer
    transducer error-handler instance]
    (let [write (chan buffer transducer error-handler)
          read (chan buffer)
          topic (clojure.core/name name)]
      ;; consumes kafka-topic and puts it on channel
      (go-loop [msg (kafka/consume-records instance)]
        (if-not (empty? msg)
          (when (>! write (first msg))
            (recur (next msg)))
          (recur (kafka/consume-records instance))))
      ;; produces to kafka-topic from channel
      (go-loop [result (<! read)]
        (when result
          (apply kafka/produce!
                 (if-conj [topic
                           result]
                   format))
          (recur (<! read))))
      ;; the channels to put in our node graph
      {:core write
       :input read
       :output (mult write)})))

(defn handle-stream
  "When the namespace of the stream name is stream...
   represents a node in a dataflow graph."
  [params buffer transducer error-handler]
  (try
    (let [write (chan buffer (comp transducer (filter some?)) error-handler)]
      {:input write
       :output (mult write)})
    (catch Exception e
      (failure (:cause (Throwable->map e))))))

(defn get-node
  ;; TODO fix this to use maps instead of argument lists
  ([{:stream/keys [name] :as stream} buffer transducer
    error-handler]
   (get-node stream buffer transducer error-handler nil))
  ([{:stream/keys [name] :as stream} buffer
    transducer error-handler instance]
   (case (namespace name)
     "kafka" (apply handle-topic
                    (if-conj
                        (list
                         stream
                         buffer
                         transducer
                         error-handler)
                      instance))
     "stream" (handle-stream stream
                             buffer
                             transducer
                             error-handler)
     (failure :namespace-must-be-kafka-or-stream))))

(defn channel-filter
  [objects]
  (filter
   #(= clojure.core.async.impl.channels.ManyToManyChannel
       (class %))
   objects))


(defn take-while
  [channel]
  (go-loop []
    (alt! (timeout 1000) ([] (recur))
          channel ([x]
                   (when (some? x)
                     ;; TODO add some logging here
                     (recur))))))

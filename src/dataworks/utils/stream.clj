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
  (reduce (fn [m [input output]]
            (assoc m
                   output
                   (tap (get-in nodes [input :output])
                        (get-in nodes [output :input]))))
          {}
          graph))

(defn handle-topic
  "When the namespace of the stream name is kafka.
   Represents a kafka topic.
   Please note that any supplied transducer is only used on
   the consumer side, rather than the producer side."
  [{:stream/keys [name format]
    :eval/keys [buffer transducer error-handler instance]}]
  (let [write (chan buffer transducer error-handler)
        read (chan buffer)
        topic (clojure.core/name name)
        instance (if instance
                   instance
                   (kafka/consumer-instance
                    {:topic (clojure.core/name name)
                     :name "dataworks"
                     :format format}))]
    ;; consumes kafka-topic and puts it on channel
    (go-loop [msg (kafka/consume-records instance)]
      (if-not (empty? msg)
        (when (>! write (first msg))
          (recur (next msg)))
        (recur (kafka/consume-records instance))))
    ;; produces to kafka-topic from channel
    (go-loop [result (<! read)]
      (when result
        (if format
          (kafka/produce! topic result format)
          (kafka/produce! topic result))
        (recur (<! read))))
    ;; the channels to put in our node graph
    {:core write
     :input read
     :output (mult write)}))

(defn handle-stream
  "When the namespace of the stream name is stream...
   represents a node in a dataflow graph."
  [{:eval/keys [buffer transducer error-handler]}]
  (try
    (let [write (chan buffer
                      (comp transducer (filter some?))
                      error-handler)]
      {:input write
       :output (mult write)})
    (catch Exception e
      (failure (:cause (Throwable->map e))))))

(defn get-node
  ;; TODO fix this to use maps instead of argument lists
  ([{:stream/keys [name] :as stream}]
   (case (namespace name)
     "kafka" (handle-topic stream)
     "stream" (handle-stream stream)
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
    (let [x (<! channel)]
      (when (some? x)
        ;; TODO add some logging here
        (recur)))))

(ns dataworks.streams
  (:require
   [clojure.core.async :refer [>! <! go-loop chan close! pipe
                               sliding-buffer dropping-buffer
                               take! go mult mix put! tap
                               admix]]
   [dataworks.common :refer :all]
   [dataworks.db.app-db :refer :all]
   [mount.core :refer [defstate]]
   [tick.alpha.api :as tick])
  (:import
   org.apache.kafka.clients.consumer.KafkaConsumer
   [org.apache.kafka.clients.producer
    KafkaProducer ProducerRecord]
   [crux.kafka.edn EdnSerializer EdnDeserializer]
   [crux.kafka.json JsonSerializer JsonDeserializer]))

;; With thanks to perkss for his excellent kafka tutorial repo.

;; contains core channels for streams
;; {:stream/node {:input }}
(def nodes
  (atom {}))

(def edges
  (atom []))

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
  [graph]
  (map (fn [[input output]]
         (tap (get-in nodes [input :output])
              (get-in nodes [output :input])))
       graph))

(defn update-graph!
  [name]
  (apply-graph! (query-graph name @edges)))

(defn evals? [function]
  (try (eval function)
       (catch Exception e
         (failure (:cause (Throwable->map e))))))

(defn transducer? [function]
  (if (fn? (function '()))
    function
    (failure (:cause :transducer-must-be-transducer))))

(defn evalidate-transducer
  [transducer]
  (->? transducer
       evals?
       transducer?))

(defn evalidate-error-handler
  [error-handler]
  (evals? error-handler))

(def kafka-settings
  (if-let [settings (-> "config.edn"
                        slurp
                        read-string
                        :kafka-settings
                        :crux.kafka/kafka-properties-map)]
    settings
    {"bootstrap.servers" "localhost:9092"}))

(defn consumer-instance
  "Create the consumer instance to consume
  from the provided kafka topic name"
  ([consumer-topic name]
   (consumer-instance consumer-topic name :edn))
  ([consumer-topic name deserializer]
   (let [deserializers {:edn EdnDeserializer
                        :json JsonDeserializer}
         consumer-props
         (merge
          kafka-settings
          {"group.id" (str "dataworks/" name)
           "key.deserializer" (deserializer deserializers)
           "value.deserializer" (deserializer deserializers)
           "auto.offset.reset" "earliest"
           "enable.auto.commit" "true"})]
     (doto (KafkaConsumer. consumer-props)
       (.subscribe [consumer-topic])))))

(defn consume-record
  [record]
  {:key (keyword (.key record))
   :value (.value record)
   :timestamp (tick/instant
               (java.util.Date.
                (.timestamp record)))
   :topic (.topic record)})

;; You need to call this in a while-loop or go-loop.
;; go-loop is preferable.
(defn consume-records
  [instance]
  (let [records (.poll instance #time/duration "PT0.1S")]
    (map consume-record records))
  (.commitAsync instance))

(defn producer-instance
  "Create the kafka producer to send edn"
  []
  (let [producer-props
        (merge kafka-settings
               {"value.serializer" EdnSerializer
                "key.serializer" EdnSerializer})]
    (KafkaProducer. producer-props)))

(defn json-producer-instance
  "Create the kafka producer to send json"
  []
  (let [producer-props
        (merge kafka-settings
               {"value.serializer" JsonSerializer
                "key.serializer" JsonSerializer})]
    (KafkaProducer. producer-props)))

;; Producers are happily thread-safe, and Kafka docs say that
;; running one producer among multiple threads is best, so
;; that's what we do!
(defstate producers
  :start
  {:edn (producer-instance)
   :json (json-producer-instance)}
  :stop
  (map #(.close (last %)) producers))

(defn produce!
  ([topic message]
   (produce! topic message :edn))
  ([topic message format]
   (let [instance (format producers)]
     (if instance
       (go (.send instance
                  (ProducerRecord. topic
                                   message)))
       (produce! topic message)))))

(defn handle-topic
  "When the namespace of the stream name is kafka.
   Represents a kafka topic.
   Please note that any supplied transducer is only used on
   the consumer side, rather than the producer side."
  [{:stream/keys [name format]}
   buffer transducer error-handler]
  (try
    (let [write (apply chan
                       (if-conj '()
                                buffer
                                transducer
                                error-handler))
          read (chan buffer)
          topic (clojure.core/name name)
          instance (apply consumer-instance
                          (if-conj (list
                                    topic
                                    "dataworks")
                                   format))]
      (go-loop [] ;; consumes kafka-topic and puts it on channel
        (when (every?
               true?
               (map
                (partial put! write)
                (consume-records instance)))
          (recur)))
      (go-loop [] ;; produces to kafka-topic from channel
        (let [result (<! read)]
          (when result
              (apply produce!
                     (if-conj (list
                               topic
                               result)
                              format))
              (recur))))
      {:core write
       :input read
       :output (mult write)})
    (catch Exception e
      (failure (:cause (Throwable->map e))))))

(defn handle-stream
  "When the namespace of the stream name is stream...
   represents a node in a dataflow graph."
  [params buffer transducer error-handler]
  (try
    (let [write (apply chan (if-conj (list)
                                     buffer
                                     transducer
                                     error-handler))]
      {:input write
       :output (mult write)})
    (catch Exception e
      (failure (:cause (Throwable->map e))))))

(defn db-fy
  "Create a map suitable for being a document in our db"
  [params]
  (if-vector-first
   params
   db-fy
   (let [{:keys [name buffer transducer
                 error-handler upstream]} params
         nodes-upstream (if upstream
                          (set (map keyword upstream)))]
     (if-assoc
      {:crux.db/id (keyword name)
       :stream/name (keyword name)}
      :stream/upstream nodes-upstream
      :stream/buffer buffer
      :stream/transducer transducer
      :stream/error-handler error-handler))))

(defn get-node
  [stream buffer transducer error-handler]
  (case (namespace name)
    "kafka" (handle-topic stream
                          buffer
                          transducer
                          error-handler)
    "stream" (handle-stream stream
                            buffer
                            transducer
                            error-handler)
    (failure :namespace-must-be-kafka-or-stream)))

(defn add-stream!
  "Add stream to streams."
  [[{:stream/keys [name] :as stream}
    [buffer transducer error-handler]]]
  (let [ns (namespace name)
        node (get-node)
        subgraph (get-edges stream)]
    (if (not= (:status node) :failure)
      (do ;; does not need to be dosync
        (swap! nodes #(assoc % name nodes))
        (swap! edges
               (comp (partial clojure.set/union subgraph)
                     (partial filter #(not= (second %) name))))
        name)
      node)))

(defn channel-filter
  [objects]
  (filter
   #(= clojure.core.async.impl.channels.ManyToManyChannel
       (class %))
   objects))

(defn update-stream!
  "close old stream and add new one"
  [[{:stream/keys [name] :as stream}
    params]]
  (map close!
       (channel-filter (vals (name @nodes))))
  (add-stream! [stream params]))

(defn validate-buffer
  [params]
  (if-vector-conj
      params
      "params"
      (let [{:stream/keys [buffer]} params]
        (if (int? buffer)
          buffer
          (let [b ((first (keys buffer))
                   {:sliding-buffer sliding-buffer
                    :dropping-buffer dropping-buffer})]
            (if b
              [(b (first (vals buffer)))]
              (failure :invalid-buffer buffer)))))))

(defn transducer-has-buffer?
  "To use a transducer (transducer) on a core.async channel,
   the channel must have a buffer."
  [params]
  (let [{:stream/keys [buffer transducer]} (first params)
        validated-params (last params)]
      (if transducer
        (if buffer
          (conj (drop-last params)
                (conj validated-params
                      (evalidate-transducer transducer)))
          (failure :must-specify-buffer-to-use-transducer))
        params)))

(defn error-handler-has-transducer?
  "To use a error-handler (error-handler) on a core.async
   channel, the channel must have a transducer."
  [params]
  (let [{:stream/keys [transducer error-handler]} (first params)
        validated-params (last params)]
      (if error-handler
        (if transducer
          (conj (drop-last params)
                (conj validated-params
                      (evalidate-error-handler error-handler)))
          (failure
           :must-specify-transducer-to-use-error-handler))
        params)))

(defn create-stream
  [stream]
  (->? stream
       (blank-field? :name)
       valid-name?
       (parseable? :transducer :error-handler)
       (function-already-exists? :stream)
       db-fy
       validate-buffer
       transducer-has-buffer?
       error-handler-has-transducer?
       added-to-db?
       add-stream!
       update-graph!))

(defn update-stream
  [stream]
  (->? stream
       (add-current-stored-function :collector)
       (has-parsed-params? :transducer :error-handler)
       (function-already-exists? :stream)
       db-fy
       validate-buffer
       transducer-has-buffer?
       error-handler-has-transducer?
       added-to-db?
       update-stream!
       update-graph!))

(defn start-stream!
  [stream]
  (->? stream
       validate-buffer
       transducer-has-buffer?
       error-handler-has-transducer?
       add-stream!))

(defstate stream-state
  ;; Start/stop the go-loop that restarts stopped streams.
  ;; This should probably return a value, but we don't use
  ;; it anywhere except on startup.
  :start
  (do
    (map (start-stream! (get-stored-functions :stream)))
    (apply-graph! @edges))
  :stop
  (do
    (map close!
       (apply concat
              (map channel-filter
                   (map vals (vals nodes)))))
    (reset! nodes {})
    (reset! edges [])))

(ns dataworks.streams
  (:require
   [clojure.core.async :refer [>! <! go-loop chan close! pipe
                               sliding-buffer dropping-buffer
                               take! go mult mix put! tap
                               admix]]
   [dataworks.common :refer :all]
   [mount.core :refer [defstate]]
   [tick.alpha.api :as tick])
  (:import
   org.apache.kafka.clients.consumer.KafkaConsumer
   [org.apache.kafka.clients.producer
    KafkaProducer ProducerRecord]
   [crux.kafka.edn EdnSerializer EdnDeserializer]
   [crux.kafka.json JsonSerializer JsonDeserializer]))

;; With thanks to perkss for his excellent kafka tutorial repo.

(def streams
  (atom {}))

(defstate stream-chan
  :start (chan 1000)
  :stop (close! stream-chan))

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

(defn start-streams
  [])

(defn validate-buffer
  [buff]
  (if (int? buffer)
    buffer
    ((first (keys buffer))
     {:sliding-buffer sliding-buffer
      :dropping-buffer dropping-buffer}
     (first (vals buffer)))))

(defn create-channel
  [buffer transducer error-handler]
  (apply chan
         (if-conj '()
                  (validate-buffer buffer)
                  (if buffer transducer)
                  (if transducer error-handler))))

(defn handle-topic
  "When the namespace of the stream name is kafka...
   represents a kafka topic."
  [{:stream/keys [name buffer format]}
   transducer
   error-handler]
  (try
    (let [write (apply chan
                       (if-conj '()
                                buffer
                                transducer
                                error-handler))
          read (chan buffer)
          topic (clojure.core/name name)
          instance (apply consumer-instance
                          (if-conj '()
                                   topic
                                   "dataworks"
                                   format))]
      (go-loop []
        (if (every?
             true?
             (map
              (partial put! write)
              (consume-records instance)))
          (recur)
          (>! stream-chan name)))
      ;; TODO add producer go-loop here.
      (go-loop []
        (let [result (<! read)]
          (if result
            (do
              (apply produce!
                     (if-conj '()
                              topic
                              result
                              format))
              (recur))
            (>! stream-chan name))))
      {:core-input write
       :core-output read
       :input (mix read)
       :output (mult write)})
    (catch Exception e
      (Throwable->map e))))

(defn handle-stream
  "When the namespace of the stream name is stream...
   represents a node in a dataflow graph."
  [{:stream/keys [name buffer format in out]}
   transducer
   error-handler]
  (try
    (let [write (create-channel buffer
                                transducer
                                error-handler)
          read (chan (if (int? buffer)
                       (sliding-buffer buffer)
                       buffer))]
      (when in
        (tap (get-in streams in :output) write))
      (when out ;; validated to be a kafka topic
        (admix read (get-in streams out :input)))
      (go-loop []
        (let [result (<! write) ;; TODO use timeouts
              sent? (if result
                      (>! read result))]
          (if sent?
            (recur)
            (>! stream-chan name))))
      {:core-input write
       :core-output read
       :input (mix read)
       :output (mult write)})
    (catch Exception e
      (Throwable->map e))))

(defn db-fy
  "Create a map suitable for being a document in our db"
  [params]
  (if-vector-first
   params
   db-fy
   (let [{:keys [name buffer transducer error-handler]} params]
     (if-assoc
      {:crux.db/id (keyword name)
       :stream/name (keyword name)}
      :stream/buffer buffer
      :stream/transducer transducer
      :stream/error-handler error-handler))))

(defn add-stream
  "Add stream to streams."
  [])

(defn out-is-kafka?
  "Verifies that when :out is specified on a stream that
   :out is a kafka stream"
  [])

(defn no-in-on-kafka?
  "Verifies that there is no specified input on a kafka stream.
   (The input of a kafka stream is the kafka topic it's named
    after.)"
  [])

;;(defn validate-buffer
;;  "Input: "
;;  [])

(defn transducer-has-buffer?
  "To use a transducer (transducer) on a core.async channel, the channel
   must have a buffer."
  [])

(defn error-handler-has-transducer?
  "To use an exception handler on the transducer on a core.async
   channel, you need to specifiy a transducer."
  [])

(defn create-stream
  [])

(defn update-stream
  [])

(defstate stream-state
  ;; Start/stop the go-loop that restarts stopped streams.
  ;; Unless I'm wrong, when the source of a mult channel close,
  ;; the taps close as well which mean all the channels that
  ;; relied on the stream that was updated with a new transducer
  ;; or the input to it failed need to be started, including the
  ;; stream that failed initially. When ever a stream has its
  ;; channel close on it, then it sends the stream name to
  ;; stream-chan. When stream-state is started, an event-handler
  ;; should be started to handle the events sent by those
  ;; streams.

  :start nil
  :stop nil)

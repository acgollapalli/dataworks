(ns dataworks.stream-utils
  (:require
   [clojure.core.async :refer [go]]
   [dataworks.common :refer :all]
   [mount.core :refer [defstate] :as mount])
  (:import
   org.apache.kafka.clients.consumer.KafkaConsumer
   [org.apache.kafka.clients.producer
    KafkaProducer ProducerRecord]
   [crux.kafka.edn EdnSerializer EdnDeserializer]
   [crux.kafka.json JsonSerializer JsonDeserializer]))

;; With thanks to perkss for his excellent tutorial repo.

;; Consumer:
;;   Topics: Kafka topic or set of topics to be consumed.
;;     (string or set of strings)
;;   Function; What you want to do with what you consume.

(defn kafka-settings []
  (if-let [settings(-> "config.edn"
                       slurp
                       read-string
                       :kafka-settings
                       :crux.kafka/kafka-properties-map)]
      settings
      {"bootstrap.servers" "localhost:9092"}))

;; {:<name> {:instance <instance> :function <function>}}
(def consumers
  (atom {}))

(defn consumer-instance
  "Create the consumer instance to consume
  from the provided kafka topic name"
  [consumer-topic name]
  (let [consumer-props
        (merge
         (kafka-settings)
         {"group.id" (str "dataworks/" name)
          "key.deserializer" EdnDeserializer
          "value.deserializer" EdnDeserializer
          "auto.offset.reset" "earliest"
          "enable.auto.commit" "true"})]
    (doto (KafkaConsumer. consumer-props)
      (.subscribe [consumer-topic]))))


;; You need to call this in a while-loop or go-loop.
;; go-loop is preferable.
(defn consume!
  [instance function]
  (let [records (.poll instance #time/duration "PT0.1S")]
    (doseq [record records]
      (function record))))

(defn producer-instance
  "Create the kafka producer to send edn"
  []
  (let [producer-props (merge (kafka-settings)
                               {"value.serializer" EdnSerializer
                                "key.serializer" EdnSerializer})]
    (KafkaProducer. producer-props)))

(defn json-producer-instance
  "Create the kafka producer to send json"
  []
  (let [producer-props (merge (kafka-settings)
                              {"value.serializer" JsonSerializer
                               "key.serializer" JsonSerializer})]
    (KafkaProducer. producer-props)))

(def producers
  {})


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
   (go (.send (:edn producers) (ProducerRecord. topic message))))
  ([topic message format]
   (let [instance (format producers)]
     (if instance
       (go (.send instance (ProducerRecord. topic message)))
       (produce! topic message)))))

(ns dataworks.utils.kafka
  (:require
   [clojure.core.async :refer [go]]
   [mount.core :refer [defstate]]
   [tick.alpha.api :as tick])
  (:import
   org.apache.kafka.clients.consumer.KafkaConsumer
   [org.apache.kafka.clients.producer
    KafkaProducer ProducerRecord]
   [crux.kafka.edn EdnSerializer EdnDeserializer]
   [crux.kafka.json JsonSerializer JsonDeserializer]))

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
;; Actually, end-users never call this at all, they simply
;; use the streams interface.
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

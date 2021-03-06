(ns dataworks.utils.dev
  (:require
   [dataworks.utils.common :refer :all]
   [dataworks.transformers :as form]
   [dataworks.transactors :refer [transact!]]
   [dataworks.streams :refer [stream!]]
   [dataworks.core :as dataworks]
   [crux.kafka.embedded :as ek]
   [clojure.java.io :as io]
   [clj-http.client :as client]
   [cheshire.core :as cheshire]
   [yada.yada :refer [as-resource]]))

;; You can call this as a library in a development project
;; that you can then use to deploy your code to a dataworks 
;; cluster when you are ready. You can also actually use 
;; to treat dataworks itself as a library and run it as a 
;; simple standalone process, to make use of dataworks 
;; introsepction and live modification abilities. Using
;; dataworks would be like if hacking on a production app
;; in the repl actually persisted your changes in the source
;; code, while automatically handling versioning as well.

(def url
  (atom (if-let [a (try
                     (let [url-ize
                           #(str "http://localhost:" % "/")]
                       (some->
                        "config.edn"
                        slurp
                        read-string
                        :port
                        url-ize))
                     (catch Exception _ nil))]
          "http://localhost:3000/"))) ;; configure me!

(def token
  (atom "If you're reading this, you forgot to call set-token."))

(defn embedded-kafka
  []
  (ek/start-embedded-kafka
   (merge
    {:crux.kafka.embedded/zookeeper-data-dir
     (str (io/file  "./zk-data"))
     :crux.kafka.embedded/kafka-log-dir
     (str (io/file "./kafka-log"))
     :crux.kafka.embedded/kafka-port 9092}
    (let [m (try (-> "config.edn"
                     slurp
                     read-string
                     :embedded-kafka)
                 (catch Exception _ {}))]
      (if (map? m) m {})))))

(defn go
  "starts a local dataworks node.
  If there is no config file, then it creates one suitable for
  development, (but ONLY for development). If no kafka info is
  specified, it starts an embedded kafka, again for development
  purposes."
  []
  (when-not (try (slurp "config.edn")
                 (catch Exception _ nil))
    (spit "config.edn"
          (pr-str
           {:jwt-secret "(def secret
                   (str \"the secret to development is: \" 
                        secret))"
            :dev-port 3000
            :user-port 8080
            :embedded-kafka :true
            :default-topic-settings {:number-of-partitions 1
                                     :replication-factor 1}})))
  (let [config (-> "config.edn" slurp read-string)]
    (when (not= :false (:embedded-kafka config))
      (when (or (nil? (:internal-kafka-settings config))
                (:embedded-kafka config))
        (embedded-kafka))))
  (dataworks/-main))

(defn get-token
  [user pass]
  (client/post
   (str @url "app/login")
   {:form-params {:user user
                  :pass pass}
    :content-type :json
    :as :json}))

(defn set-token
  "Resets the token value used in calls to the api by helper functions."
  [user pass]
  (->> (get-token user pass)
      :body
      :token
      (reset! token)))

;; We define these so we can send our collectors to our 
;; dataworks instance
(def transformers (atom {}))
(def collectors (atom {}))
(def transactors (atom {}))
(def streams (atom {}))

(defmacro def-transformer
;;  TODO add docstring capability
  ([name args & form]
   (swap!
    transformers
    (fn [m]
      (assoc m
             name
             {:name name
              :function (pr-str (if (vector? args)
                                  (concat ['fn args] form)
                                  (recursive-replace args)))})))))

(defmacro def-collector
  ([name path form]
   (swap!
    collectors
    (fn [m]
      (assoc m
             name
             {:name name
              :path path
              :resource (pr-str (recursive-replace form))})))))

(comment
  ;; TODO create an actual test suite
  (def-collector :test-1
    "test-1"
    {:id :test-1
     :description "test-1"
     :methods {:get {:produces "application/text"
                     :response (fn [ctx]
                                 "test-1 successful")}}})
  (def-collector :test-2
    "test-2"
    (->let
     (defn response
       [ctx]
       "test-2 successful")

     {:id :test-2
      :description "test-2"
      :methods {:get {:produces "application/text"
                      :response response}}})))

(defmacro def-transactor
  ([name args & form]
   (swap!
    transactors
    (fn [m]
      (assoc m
             name
             {:name name
              :function (pr-str (concat ['fn args] form))})))))

(defn stream-helper
  ([args]
   (if (keyword? args)
     (recur {:name args})
     (swap! streams (fn [m] (assoc m (:name args) args))))))

(defmacro def-stream
  ([args]
   (stream-helper args))
  ([name {:keys [buffer transducer error-handler] :as params}]
   (let [if-quote (fn [params key value]
                    (if value
                      (assoc params key (if (int? value)
                                          value
                                          (pr-str value)))
                      params))]
     (-> params
         (assoc :name name)
         (if-quote :transducer transducer)
         (if-quote :error-handler error-handler)
         stream-helper))))

(defn exists?
  ([fn-type {:keys [name]}]
  (try (client/get
        (str
         @url "app/"
         (stringify-keyword fn-type) "/"
         (stringify-keyword name))
        {:oauth-token @token})
       (catch Exception _ nil))))

(defn update-fn
  [fn-type f]
  (println "updating:" f)
  (client/post
   (str @url "app/"
        (stringify-keyword fn-type) "/"
        (stringify-keyword (:name f)))
   {:form-params f
    :oauth-token @token
    :content-type :edn
    :as :edn}))

(defn create-fn
  [fn-type f]
  (println "creating:" f)
  (client/post
   (str @url "app/"
        (stringify-keyword fn-type))
   {:form-params f
    :oauth-token @token
    :content-type :edn
    :as :edn}))

(defn send-fn
  ([f]
   (send-fn (namespace f) (name f)))
  ([fn-type f]
   (let [f (get
            (case (keyword fn-type)
              :collector @collectors
              :stream @streams
              :transformer @transformers
              :transactor @transactors)
            (keyword f))]
     (try (if (exists? fn-type f)
            (update-fn fn-type f)
            (create-fn fn-type f))
          (catch Exception e
            (println e))))))

(defn send-all-fns
  []
  (map (fn [[k v]]
         (map (comp send-fn
                    (fn [f]
                      (get-entity-param f k)))
              (keys v)))
       {:transformer @transformers,
        :transactor @transactors,
        :stream @streams,
        :collector @collectors}))

(defn test-exists?
  [fn-type f]
  (dataworks.db.app-db/entity
   (apply keyword
          (map stringify-keyword
               [fn-type (:name f)]))))

(defn test-create-fn
  [fn-type f]
  (println "create")
  (case (keyword fn-type)
    :collector (dataworks.collector/create-collector! f)
    :stream (dataworks.stream/create-stream! f)
    :transformer (dataworks.transformer/create-transformer! f)
    :transactor (dataworks.transactor/create-transactor! f)))

(defn test-update-fn
  [fn-type f]
  (println "update")
  (let [fn-name (stringify-keyword (:name f))]
    (case (keyword fn-type)
      :collector (dataworks.collector/update-collector! fn-name f)
      :stream (dataworks.stream/update-stream! fn-name f)
      :transformer (dataworks.transformer/update-transformer! fn-name f)
      :transactor (dataworks.transactor/update-transactor! fn-name f))))

(defn test-fn
  ([f]
   (test-fn (namespace f) (name f)))
  ([fn-type f]
   (when (map? dataworks.core/svr)
     (let [f (get
              (case (keyword fn-type)
                :collector @collectors
                :stream @streams
                :transformer @transformers
                :transactor @transactors)
              (keyword f))]
       (if (test-exists? fn-type f)
         (test-update-fn fn-type f)
         (test-create-fn fn-type f))))))

(defn test-all-fns
  []
  (map (fn [[k v]]
         (map (comp test-fn
                    (fn [f]
                      (get-entity-param f k)))
              (keys v)))
       {:transformer @transformers,
        :transactor @transactors,
        :stream @streams,
        :collector @collectors}))

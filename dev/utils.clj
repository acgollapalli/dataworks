(ns dataworks.dev.utils)

(comment
  "You'll want to just eval everything below this, when
   in dataworks.core in your REPL. Then run (-main) or
   (mount/start). Development is much easier that way.
   Look for these in the docs.
   ... arguably the easiest way is to just eval the whole buffer")


(def url (atom "http://localhost:3001/")) ;; configure me!

(require '[dataworks.utils.common :refer :all]
         '[clj-http.client :as client]
         '[cheshire.core :as cheshire]
         '[yada.yada :refer [as-resource]])

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
              :function (pr-str (concat ['fn args] form))})))))

(defmacro def-collector
  ([name path form]
   (swap!
    collectors
    (fn [m]
      (assoc m
             name
             {:name name
              :path path
              :resource (pr-str (concat [] form))})))))

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
                      (assoc params key (pr-str value))
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
         (stringify-keyword name)))
       (catch Exception _ nil))))

(defn update-fn
  [fn-type f]
  (println "updating:" f)
  (client/post
   (str @url "app/"
        (stringify-keyword fn-type) "/"
        (stringify-keyword (:name f)))
   {:form-params f
    :content-type :edn}))

(defn create-fn
  [fn-type f]
  (println "creating:" f)
  (client/post
   (str @url "app/"
        (stringify-keyword fn-type))
   {:form-params f
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
     (if (exists? fn-type f)
       (update-fn fn-type f)
       (create-fn fn-type f)))))

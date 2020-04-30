(ns dataworks.stream
  (:require
   [clojure.core.async :refer [close!
                               tap
                               chan
                               sliding-buffer
                               dropping-buffer]]
   [dataworks.utils.common :refer :all]
   [dataworks.db.app-db :refer :all]
   [dataworks.app-graph :as app]
   [dataworks.utils.stream :refer :all]
   [dataworks.streams :refer [stream-ns
                              nodes
                              edges]]
   [mount.core :refer [defstate]]
   [tick.alpha.api :as tick]))

(defn update-graph!
  [name]
  (app/stream!
   :kafka/dataworks.internal.functions
   {:crux.db/id name
    :stored-function/type :stream})
  (apply-graph! (query-graph name @edges)
                @nodes))

(defn evals? [function]
  (binding [*ns* stream-ns]
    (try (eval function)
       (catch Exception e
         (failure (:cause (Throwable->map e)))))))

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

(defn add-stream!
  "Add stream to streams."
  [[{:stream/keys [name] :as stream}
    [buffer transducer error-handler]]]
  (let [ns (namespace name)
        node (get-node stream buffer transducer error-handler)
        subgraph (get-edges stream)]
    (if (not= (:status node) :failure)
      (do ;; does not need to be dosync
        (swap! nodes #(assoc % name node))
        (swap! edges
               (comp (partial clojure.set/union subgraph)
                     (partial filter #(not= (second %) name))))
        name)
      node)))

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

(defn create-stream!
  [stream]
  (->? stream
       (blank-field? :name)
       valid-name?
       (parseable? :transducer :error-handler)
       (function-already-exists? :stream)
       db-fy
       (dependencies? :stream)
       validate-buffer
       transducer-has-buffer?
       error-handler-has-transducer?
       added-to-db?
       add-stream!
       update-graph!))

(defn update-stream!
  [stream]
  (->? stream
       (add-current-stored-function :stream)
       (has-parsed-params? :stream :transducer :error-handler)
       (function-already-exists? :stream)
       db-fy
       (dependencies? :stream)
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
       add-stream!
       ;;update-graph!
       ))

(defstate stream-chan
  :start
  (let [c (chan
           10
           (comp
            (filter
             #(= (:stored-function/type %)
                 (keyword :stream)))
            (map  ;; TODO add error handling.
             (fn [{:crux.db/keys [id]}]
               (start-stream! (entity id))))))]
    (take-while c)
    (tap (get-in
          app/node-state
          [:stream/dataworks.internal.functions
           :output])
          c))
  :stop
  (close! stream-chan))

(defstate stream-state
  ;; Start/stop the go-loop that restarts stopped streams.
  ;; This should probably return a value, but we don't use
  ;; it anywhere except on startup.
  :start
  (do
    (map start-stream! (get-stored-functions :stream))
    (apply-graph! @edges @nodes))
  :stop
  (do
    (map close!
       (apply concat
              (map channel-filter
                   (map vals (vals @nodes)))))
    (reset! nodes {})
    (reset! edges [])))

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

(defn add-to-graph! [name]
    (apply-graph! (query-graph name @edges)
                @nodes))

(defn update-graph!
  [{:keys [details] :as result}]
  (app/stream!
   :kafka/dataworks.internal.functions
   {:crux.db/id (:crux.db/id details)
    :stored-function/type :stream})
  (add-to-graph! (:stream/name details))
  result)

(defn evals? [function]
  (binding [*ns* stream-ns]
    (try (eval function)
       (catch Exception e
         (failure (:cause (Throwable->map e)))))))

(defn transducer? [function]
  (if (fn? (function '()))
    function
    (failure :cause :transducer-must-be-transducer)))

(defn evalidate-transducer
  [{:stream/keys [transducer] :as stream}]
  (println transducer)
  (println stream)
  (if transducer
    (let [xform (->? transducer evals? transducer?)]
      (if (= (:status xform) :failure)
        xform
        (assoc stream :eval/transducer (comp xform (filter some?)))))
    stream))

(defn evalidate-error-handler
  [{:stream/keys [error-handler] :as stream}]
  (if error-handler
    (let [xform (evals? error-handler)]
    (if (= (:status xform) :failure)
      xform
      (assoc stream :eval/error-handler xform)))
    stream))

(defn add-stream!
  "Add stream to streams."
  [{:stream/keys [name] :as stream}]
  (let [ns (namespace name)
        node (get-node stream)
        subgraph (get-edges stream)]
    (if (not= (:status node) :failure)
      (do ;; does not need to be dosync
        (swap! nodes #(assoc % name node))
        (swap! edges
               (comp (partial clojure.set/union subgraph)
                     (partial filter #(not= (second %) name))))
        (success (exclude-ns-keys stream :eval)))
      node)))

(defn close-old-stream!
  "close old stream and add new one"
  [{:stream/keys [name] :as stream}]
  (map close!
       (channel-filter (vals (name @nodes))))
  (add-stream! stream))

(defn validate-buffer
  [{:stream/keys [buffer] :as params}]
  (if buffer
    (if (int? buffer)
    (assoc params :eval/buffer buffer)
    (let [b (((first (keys buffer))
              {:sliding-buffer sliding-buffer
               :dropping-buffer dropping-buffer})
             (first (vals buffer)))]
      (if b
        (assoc params :eval/buffer b)
        (failure :invalid-buffer buffer))))
    params))

(defn transducer-has-buffer?
  "To use a transducer (transducer) on a core.async channel,
   the channel must have a buffer."
  [{:stream/keys [buffer transducer] :as params}]
  (if transducer
    (if buffer
      params
      (failure :must-specify-buffer-to-use-transducer))
    params))

(defn error-handler-has-transducer?
  "To use a error-handler (error-handler) on a core.async
     channel, the channel must have a transducer."
  [{:stream/keys [transducer error-handler] :as params}]
  (if error-handler
    (if transducer
      params
      (failure :must-specify-transducer-to-use-error-handler))
    params))

(defn create-stream!
  [stream]
  (->? stream
       (set-ns :stream)
       (missing-field? :name)
       valid-name?
       (parseable? :transducer)
       (parseable? :error-handler)
       function-already-exists?
       validate-buffer
       transducer-has-buffer?
       evalidate-transducer
       error-handler-has-transducer?
       evalidate-error-handler
       added-to-db?
       add-stream!
       update-graph!))

(defn update-stream!
  [stream name]
  (->? stream
       (set-ns :stream)
       (updating-correct-function? name)
       valid-name?
       add-current-stored-function
       (has-parsed-params? :transducer :error-handler)
       validate-buffer
       transducer-has-buffer?
       evalidate-transducer
       error-handler-has-transducer?
       evalidate-error-handler
       added-to-db?
       update-old-stream!
       update-graph!))

(defn start-stream!
  [stream]
  (->? stream
       validate-buffer
       transducer-has-buffer?
       evalidate-transducer
       error-handler-has-transducer?
       evalidate-error-handler
       add-stream!))

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
               (-> id entity start-stream! add-to-graph!)))))]
    (take-while c)
    (tap (get-in
          app/node-state
          [:stream/dataworks.internal.functions
           :output])
          c))
  :stop
  (close! stream-chan))

(defn wire-streams! []
  (apply-graph! @edges @nodes))

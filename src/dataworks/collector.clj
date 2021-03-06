(ns dataworks.collector
  (:require
   [clojure.core.async :refer [close!
                               chan
                               tap]]
   [crux.api :as crux]
   [dataworks.app-graph :refer [node-state
                                stream!]]
   [dataworks.utils.stream :as stream]
   [dataworks.db.app-db :refer [app-db
                                entity
                                get-stored-function
                                get-stored-functions
                                add-current-stored-function
                                function-already-exists?
                                added-to-db?]]
   [dataworks.collectors :refer [collector-ns
                                 resource-map
                                 atomic-routes]]
   [dataworks.utils.common :refer :all]
   [mount.core :refer [defstate] :as mount]
   [yada.yada :refer [as-resource] :as yada]))

;; A collector does a thing when an endpoint is called.
;; It's effectively a yada resource and a path.
;; You might reasonably think of it as an API endpoint.
;; Whatever you specify as your :resource, it must evaluate
;; to a yada resource.
;; For information on yada resources see:
;;     https://github.com/juxt/yada/tree/master/doc
;; (The manual on the website is outdated. We're on the alpha version of yada.)

;;(defn validate-path [path]
;;  (println "Validating path")
;;  (println "path:" path)
;;  (let [evald-path (try (read-string path)
;;                        (catch Exception e path))]
;;    (cond (symbol? evald-path) path
;;          (and (or (set? evald-path)
;;                   (vector? evald-path))
;;               (every? #(or (keyword? %)
;;                            (string? %)
;;                            (= java.util.regex.Pattern (type %))
;;                            (validate-path %))
;;                       evald-path)) evald-path
;;          (string? evald-path) evald-path)))

(defn validate-path [path]
  (println "Validating path")
  (cond
    (string? path) path
    (and (set? path)
         (every? validate-path path)) path
    (and (vector? path)
         (every? #(or (string? %)
                      (= java.util.regex.Pattern (type %))
                      (keyword? %)) path)) path))

(defn valid-path?
  [{:collector/keys [path] :as params}]
  (let [valid-path (validate-path path)]
    (if valid-path
      (assoc params :collector/path valid-path)
      {:status :failure
       :message :invalid-path
       :details path})))

;; TODO create separate path document to avoid race condition.
(defn other-collector-with-path?
  [{:collector/keys [path] :as collector}]
  (println "Checking for duplicate paths:" path)
  (if-let [other-collectors
           (not-empty
            (crux/q
             (crux/db app-db)
             {:find ['e]
              :where [['e :stored-function/type :collector]
                      ['e :collector/path (quote path)]]}))]
    {:status :failure
     :message :collector-with-path-already-exists
     :details other-collectors})
  collector)

(defn evalidate
  "validates, sanitizes, and evaluates the resource map from db
   CURRENTLY UNSAFE (but necessary)"
  [{:collector/keys [name resource] :as collector}]
  (binding [*ns* collector-ns]
    (println "Evalidating:" name)
    (try (assoc collector
                :eval/resource
                (yada/resource (eval resource)))
         (catch Exception e
           {:status :failure
            :message :unable-to-evalidate-resource
            :details (.getMessage e)}))))

(defn add-collector!
  ([{:collector/keys [path name] :eval/keys [resource] :as collector}]
   (if resource
     (dosync
      (println "Adding collector!" name)
      (swap! resource-map (fn [old-map]
                            (assoc old-map name resource)))
      (swap! atomic-routes (fn [old-map]
                             (assoc old-map path name)))
      {:status :success
       :message :collector-added
       :details (select-ns-keys collector :collector)})
     (->? collector
          evalidate
          add-collector!))))

(defn apply-collector! [params]
  (stream! :kafka/dataworks.internal.functions
           (select-keys params
                        [:crux.db/id
                         :stored-function/type]))
  (add-collector! params))

(defn start-collectors! []
  (println "Starting Collectors")
  (let [colls (get-stored-functions :collector)
        status (map add-collector! colls)]
    (if (every? #(= (:status %) :success) status)
      (println "Started Collectors!")
      (println "Failed to Start Collectors:"
               (map :name status)))))

(defn create-collector! [collector]
  (->? collector
       (set-ns :collector)
       (missing-field? :name :path)
       (blank-field? :resource)
       valid-path?
       valid-name?
       (parseable? :resource)
       function-already-exists?
       other-collector-with-path?
       evalidate
       added-to-db?
       apply-collector!))

(defn update-collector! [name params]
  (->? params
       (set-ns :collector)
       (updating-correct-function? name)
       valid-name?
       add-current-stored-function
       (has-params? :path)
       valid-path?
       (has-parsed-params? :resource)
       (valid-update? :path :resource)
       evalidate
       added-to-db?
       apply-collector!))

(defstate collector-chan
  :start
  (let [c (chan
           10
           (comp
            (filter
             #(= (:stored-function/type %)
                 (keyword :collector)))
            (map  ;; TODO add error handling.
             (fn [{:crux.db/keys [id]}]
               ;; pretty sure this will result in collectors being
               ;; evaluated twice. But this is harmless, so far as I
               ;; can tell
               (add-collector! (entity id))))))]
    (stream/take-while c)
    (tap (get-in
          node-state
          [:stream/dataworks.internal.functions
           :output])
          c))
  :stop
  (close! collector-chan))

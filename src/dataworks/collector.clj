(ns dataworks.collector
  (:require
   [clojure.pprint :refer [pprint]]
   [crux.api :as crux]
   [dataworks.authentication :as auth]
   [dataworks.db.app-db :refer [app-db]]
   [dataworks.collectors :refer [collector-ns]]
   [dataworks.common :refer :all]
   [mount.core :refer [defstate] :as mount]
   [yada.yada :refer [as-resource] :as yada]
   [bidi.bidi :as bidi]))

;; A collector does a thing when an endpoint is called.
;; It's effectively a yada resource and a path.
;; You might reasonably think of it as an API endpoint.
;; Whatever you specify as your :resource, it must evaluate to a yada resource.
;; For information on yada resources see:
;;     https://github.com/juxt/yada/tree/master/doc
;; (The manual on the website is outdated. We're on the alpha version of yada.)
;;
;; Example: POST to app/collector
;;
;; {
;;  "name" : "text",
;;  "path" : "text",
;;  "resource" : "{:id :text-send
;;                 :description \"sends a text\"
;;                 :methods {:post {:consumes #{\"text/plain\", \"application/json\"}
;;                                  :produces \"text/plain\"
;;                                  :response (fn [ctx]
;;                                              (let [body (:body ctx)]
;;                                                   (transact! :text body)
;;                                                   \"success!\"))}}}"
;; }

(def resource-map
  (atom
   {}))

(def atomic-routes
  (atom {}))

(defn validate-path [path]
  (println "Validating path")
  (let [evald-path (read-string path)]
    (cond (symbol? evald-path) path
          (and (vector? evald-path)
               (every? #(or (keyword? %)
                            (string? %))
                       evald-path)) evald-path
          (string? evald-path) evald-path)))

(defn valid-path? [params]
  (if-vector-first params
    valid-path?
    (let [path (:path params)
          valid-path (validate-path path)]
      (if valid-path
        (assoc params :path valid-path)
        {:status :failure
         :message :invalid-path
         :details path})) ))

;; TODO create separate path document to avoid race condition.
(defn other-collector-with-path? [{:keys [path] :as collector}]
  (println "Checking for duplicate paths:" path)
  (if-let [other-collectors (not-empty
                             (crux/q
                              (crux/db app-db)
                              {:find ['e]
                               :where [['e :stored-function/type :collector]
                                       ['e :collector/path path]]}))]
    {:status :failure
     :message :collector-with-path-already-exists
     :details other-collectors})
  collector)

(defn evalidate
  "validates, sanitizes, and evaluates the resource map from db
   CURRENTLY UNSAFE (but necessary)"
  [{:keys [resource]:as params}]

  (if-vector-conj params
    evalidate
    (binding [*ns* collector-ns]
      (println "Evalidating.")
      (try (yada/resource (eval resource))
           (catch Exception e {:status :failure
                             :message :unable-to-evalidate-resource
                             :details (.getMessage e)})))))

(defn db-fy [{:keys [name resource path] :as params}]
  (if-vector-first params
    db-fy
    (do
      {:crux.db/id (keyword "collector" name)
       :collector/name (keyword name)
       :collector/resource resource
       :collector/path path
       :stored-function/type :collector})))

(defn add-collector!
  ([{:collector/keys [resource] :as collector}]
   (if-let [evald-resource (evalidate resource)]
     (add-collector! collector evald-resource)))
  ([{:collector/keys [path name] :as collector} evald-resource]
   (println "Adding collector!")
   (dosync
    (swap! resource-map (fn [old-map]
                          (assoc old-map name evald-resource)))
    (swap! atomic-routes (fn [old-map]
                           (assoc old-map path name)))
    {:status :success
     :message :collector-added
     :details collector})))

(defn apply-collector! [params]
  (apply add-collector! params))

(defn start-collectors! []
  (println "Starting Collectors")
  (let [colls (get-stored-functions :collectors)
        status (map add-collector! colls)]
    (if (every? #(= (:status %) :success) status)
      (println "Started Collectors!")
      (println "Failed to Start Collectors:"
               (map :name status)))))

(defn create-collector! [collector]
  (->? collector
       (blank-field? :name :path :resource)
       valid-path?
       valid-name?
       (parseable? :resource)
       (function-already-exists? :collector)
       other-collector-with-path?
       evalidate
       (added-to-db? db-fy)
       apply-collector!))

(defn update-collector! [name params]
  (->? params
       (updating-correct-function? name)
       (add-current-stored-function :collector)
       (has-params? :collector :path)
       (has-parsed-params? :collector :resource)
       (valid-update? :collector :path :resource)
       valid-path?
       evalidate
       (added-to-db? db-fy)
       apply-collector!))

(defstate collector-state
  :start
  (start-collectors!)
  :stop
  (dosync (reset! atomic-routes {})
          (reset! resource-map {})))

(def user-sub
  (fn [ctx]
    (let [path-info (get-in ctx [:request :path-info])]
      (when-let [path (bidi/match-route
                       ["" @atomic-routes] path-info)]
        (@resource-map (:handler path))))))

(def user
  (yada/resource
   {:id :user
    :path-info? true
    :sub-resource user-sub}))

;; TODO ADD AUTHENTICATION!!!!!!!!!!!!!!!!!1111111111111
(def collectors
  (yada/resource
   {:id :collectors
    :description "this is the resource that returns all collector documents"
    ;;:authentication auth/dev-authentication
    ;;:authorization auth/dev-authorization
    :methods {:get {:response
                    (fn [ctx]
                      (get-stored-functions :collectors))
                    :produces "application/json"}
              :post
              {:consumes #{"application/json"}
               :produces "application/json"
               :response
               (fn [ctx]
                 (println "Received Collector Creation Request.")
                 (let [body (:body ctx)]
                   (create-collector! body)))}}}))

;; TODO ADD AUTHENTICATION!!!!!!!!!!!!!!!!!1111111111111
(def collector
  (yada/resource
   {:id :collector
    :description "resource for individual collector"
    ;;:parameters {:path {:id String}} ;; do I need plurumatic's schema thing?
    ;;:authentication auth/dev-authentication
    ;;:authorization auth/dev-authorization
    :path-info? true
    :methods {:get
              {:produces "application/json"
               :response
               (fn [ctx]
                 (let [name (get-in ctx [:request :path-info])]
                   (get-stored-function name :collector)))}
              :post
              {:consumes #{"application/json"}
               :produces "application/json"
               :response
               (fn [ctx]
                 (let [name (get-in ctx [:request :path-info])
                       body (:body ctx)]
                   (update-collector! name body)))}}}))

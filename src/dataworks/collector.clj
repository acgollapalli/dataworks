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

(defn get-collectors []
  (map #(crux/entity (crux/db app-db) (first %))
       (crux/q (crux/db app-db)
               '{:find [e]
                 :where [[e :stored-function/type :collector]
                         [e :collector/path]
                         [e :collector/resource]]})))

(defn get-collector [name]
  (let [db (crux/db app-db)]
    (crux/entity db (keyword "collector" name))))

(defn validate-path [path]
  (let [evald-path (read-string path)]
    (cond (symbol? evald-path) path
          (and (vector? evald-path)
               (every? #(or (keyword? %)
                            (string? %))
                       evald-path)) evald-path
          (string? evald-path) evald-path)))

(defn valid-path? [{:keys [path] :as collector}]
  (println "Validating path:" path)
  (if-let [valid-path (validate-path path)]
    (assoc collector :path valid-path)
    {:status :failure
     :message :invalid-path
     :details path}))

(defn resource-parseable? [{:keys [resource name] :as params}]
  (println "Checking resource parseability: " name)
  (if (string? resource)
    (try (assoc params :resource (read-string resource))
       (catch Exception e {:status :failure
                           :message :unable-to-parse-resource
                           :details (.getMessage e)}))
    params))

(defn updating-correct-fn? [{:keys [name] :as params} path-name]
  (println "Checking for correct function: " path-name "," name)
  (if name
    (if (= name path-name)
      params
      {:status :failure
       :message :name-param-does-not-match-path
       :details (str "We don't let you rename stored functions.\n"
                     "If it's changed enough to be renamed, "
                     "it's a different function at that point\n"
                     "Best thing is to retire the old function "
                     "and create a new one.")})
    (assoc params :name path-name)))

(defn add-current-collector [{:keys [name] :as collector}]
  (println "Adding current collector:" name)
  [collector (get-collector name)])

(defn has-path? [[{:keys [path] :as params} current-collector]]
  (println "Checking for path.")
  (if-not path
    (assoc params :path (:collector/path current-collector))
    [params current-collector]))

(defn has-resource? [[{:keys [resource] :as params} current-collector]]
  (println "Checking for resource.")
  (if-not resource
    (assoc params :resource (:collector/resource current-collector))
    (let [parseable-params (resource-parseable? params)
          not-parseable (:status parseable-params)]
      (if not-parseable
        parseable-params
        [parseable-params current-collector]))))

(defn valid-update? [[{:keys [path resource] :as params} current-collector]]
  (println "Checking if valid update.")
  (if (and (= (:collector/path current-collector)
              path)
           (= (:collector/resource current-collector)
              resource))
    {:status :failure
     :message :no-change-from-current-resource-or-path}
    params))

(defn collector-already-exists? [{:keys [name] :as collector}]
  (println "Checking for duplicate collectors:" name)
  (if-let [other-collector (get-collector name)]
    {:status :failure
     :message :collector-already-exists
     :details other-collector}
    collector))

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
  [resource-map]
  (binding [*ns* collector-ns]
    (try (yada/resource (eval resource-map))
         (catch Exception e {:status :failure
                             :message :unable-to-evalidate-resource
                             :details (.getMessage e)}))))

(defn evalidated? [{:keys [resource] :as collector}]
  (println "Evalidating.")
  (let [e (evalidate resource)]
    (if (= (type e) yada.resource.Resource)
      [collector e]
      e)))

(defn db-fy [{:keys [name resource path]}]
  (println "db-fying.")
  {:crux.db/id (keyword "collector" name)
   :collector/name (keyword name)
   :collector/resource resource
   :collector/path path
   :stored-function/type :collector})

(defn added-to-db? [[{:keys [name] :as collector} evald-resource]]
  (println "Adding to db: collector"  name)
  (let [db-collector (db-fy collector)]
    (if (crux/submit-tx app-db [[:crux.tx/put db-collector]])
      [db-collector evald-resource]
      {:status :failure
       :message :db-failed-to-update})))

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
  (let [colls (get-collectors)
        status (map add-collector! colls)]
    (if (every? #(= (:status %) :success) status)
      (println "Started Collectors!")
      (println "Failed to Start Collectors:" (map :name status)))))

(defn create-collector! [collector]
  (->? collector
       (blank-field? :name :path :resource)
       valid-path?
       valid-name?
       resource-parseable?
       collector-already-exists?
       other-collector-with-path?
       evalidated?
       added-to-db?
       apply-collector!))

(defn update-collector! [name params]
  (->? params
       (updating-correct-fn? name)
       add-current-collector
       has-path?
       has-resource?
       valid-update?
       valid-path?
       evalidated?
       added-to-db?
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
      (when-let [path (bidi/match-route ["" @atomic-routes] path-info)]
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
    :methods {:get {:response (fn [ctx]
                                (get-collectors))
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
                 (let [id (get-in ctx [:request :path-info])]
                   (get-collector id)))}
              :post
              {:consumes #{"application/json"}
               :produces "application/json"
               :response
               (fn [ctx]
                 (let [name (get-in ctx [:request :path-info])
                       body (:body ctx)]
                   (update-collector! name body)))}}}))

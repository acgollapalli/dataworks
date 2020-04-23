(ns dataworks.collector
  (:require
   [clojure.pprint :refer [pprint]]
   [crux.api :as crux]
   [dataworks.authentication :as auth]
   [dataworks.db.app-db :refer [app-db
                                get-stored-function
                                get-stored-functions
                                add-current-stored-function
                                function-already-exists?
                                added-to-db?]]
   [dataworks.collectors :refer [collector-ns]]
   [dataworks.common :refer :all]
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
                        :details path}))))

;; TODO create separate path document to avoid race condition.
(defn other-collector-with-path? [{:keys [path] :as collector}]
  (println "Checking for duplicate paths:" path)
  (if-let [other-collectors
           (not-empty
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
  [params]
  (if-vector-conj params
                  "params"
                  (binding [*ns* collector-ns]
                    (println "Evalidating"
                             (:collector/name params))
                    (try (yada/resource
                          (eval
                           (:collector/resource params)))
                         (catch Exception e
                           {:status :failure
                            :message :unable-to-evalidate-resource
                            :details (.getMessage e)})))))

(defn db-fy
  [params]
  (if-vector-first params
                   db-fy
                   {:crux.db/id (keyword "collector"
                                         (:name params))
                    :collector/name (keyword (:name params))
                    :collector/resource (:resource params)
                    :collector/path (:path params)
                    :stored-function/type :collector}))

(defn add-collector!
  ([{:collector/keys [resource] :as collector}]
   (if-let [evald-resource (evalidate collector)]
     (apply add-collector! evald-resource)))
  ([{:collector/keys [path name] :as collector} evald-resource]
   (println "Adding collector!" name)
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
  (let [colls (get-stored-functions :collector)
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
       db-fy
       evalidate
       added-to-db?
       apply-collector!))

(defn update-collector! [name params]
  (->? params
       (updating-correct-function? name)
       (add-current-stored-function :collector)
       (has-params? :collector :path)
       (has-parsed-params? :collector :resource)
       (valid-update? :collector :path :resource)
       valid-path?
       db-fy
       evalidate
       added-to-db?
       apply-collector!))

(defstate collector-state
  :start
  (start-collectors!)
  :stop
  (dosync (reset! atomic-routes {})
          (reset! resource-map {})))

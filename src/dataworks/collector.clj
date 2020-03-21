(ns dataworks.collector
  (:require
   [dataworks.db.app-db :refer [app-db]]
   [dataworks.db.user-db :refer [user-db]]
   [dataworks.collectors :refer [collector-ns]]
   [dataworks.authentication :as auth]
   [crux.api :as crux]
   [mount.core :refer [defstate] :as mount]
   [yada.yada :refer [as-resource] :as yada]
   [clojure.pprint :refer [pprint] :as p]
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

;; TODO ADD RESOURCE SECURITY VALIDATION!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!1111111111
;;(defn evalidate [resource-map]
;;  "validates, sanitizes, and evaluates the resource map from db CURRENTLY UNSAFE (but necessary)"
;;  (binding [*ns* collector-ns]
;;    (if (string? resource-map)
;;      (yada/resource (eval (read-string resource-map))) ;; TODO add case for eval fail
;;      (yada/resource (eval resource-map)))))

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

;; TODO ADD PATH VALIDATION!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!11111
;; TODO FIGURE OUT HUMAN READABLE ID'S IN MAPS WITHOUT DUPLICATES!!
;;(defn add-collector!
;;  ([{:collector/keys [path resource name] :as params}]
;;  (if-let [r (evalidate resource)]
;;      (add-collector! name path r)))
;;  ([name path r]
;;   (dosync
;;       (swap! resource-map (fn [old-map]
;;                             (assoc old-map (keyword name) r)))
;;       (swap! atomic-routes (fn [old-map]
;;                              (assoc old-map path (keyword name)))))))

;;(defn new-collector! [{:collector/keys [name resource path] :as params}]
;;  (if-let [r (evalidate resource)]
;;    (if path
;;      (if (crux/submit-tx app-db [[:crux.tx/put params]])
;;        (do (add-collector! name r)
;;            {:status :success
;;             :message :collector-added
;;             :details params})
;;        {:status :failure
;;         :message :db-failed-to-update})
;;      {:status :failure
;;       :message :path-failed-to-validate}) ;; this should have a details clause TODO
;;    {:status :failure
;;     :message :resource-failed-to-evalidate})) ;; this should have a details clause TODO



;;(defn db-fy [{:keys [name resource path] }]
;;  {:crux.db/id (keyword "collector" name)
;;   :collector/name (keyword name)
;;   :collector/resource (read-string resource)
;;   :collector/path (let [evald-path (clojure.edn/read-string path)]
;;                     (cond (symbol? evald-path) path
;;                           (and (vector? evald-path)
;;                                (every? #(or (keyword? %)
;;                                             (string? %))
;;                                        evald-path)) evald-path
;;                           (string? evald-path) evald-path))
;;   :stored-function/type :collector})
;;
;;;;(defn create-collector! [{:keys [name] :as collector}]
;;;;  (if (get-collector name)
;;;;    {:status :failed
;;;;     :message :collector-already-exists}
;;;;    (if (crux/submit-tx
;;;;         app-db
;;;;         [[:crux.tx/put (db-fy collector)]])
;;;;      (add-collector! (get-collector name)))
;;;;    {:status :failure
;;;;     :message :db-failed-to-update}))
;;
;;(defn create-collector! [{:keys [name path] :as params}]
;;  (if (get-collector name)
;;    {:status :failed
;;     :message :collector-already-exists}
;;    (if-let [other-colls (not-empty
;;                          (crux/q
;;                           (crux/db app-db)
;;                           {:find ['e]
;;                            :where [['e :stored-function/type :collector]
;;                                    ['e :collector/path path]]}))]
;;      (new-collector! (db-fy params))
;;      {:status :failed
;;       :message :collector-with-path-already-exists
;;       :details other-colls})))
;;
;;;; TODO VERIFY THAT COLLECTOR UPDATED IN DB BEFORE CALLING add-collector!
;;;;(defn update-collector! [name params]
;;;;  (if (crux/submit-tx
;;;;       app-db
;;;;       [[:crux.tx/put (db-fy params)]])
;;;;    (add-collector! (get-collector name))
;;;;    {:status :failure
;;;;     :message :db-failed-to-update}))
;;(defn update-collector! [name params]
;;  (let [current-collector (get-collector name)
;;        new-collector (db-fy name params)]
;;    (cond (not= (:crux.db/id current-collector)
;;                (:crux.db/id new-collector))
;;          {:status :failure
;;           :message :name-param-does-not-match-path}
;;          (and (= (:collector/path current-collector)
;;                  (:collector/path new-collector))
;;               (= (:collector/resource current-collector)
;;                  (:collector/resource new-collector)))
;;          {:status :failure
;;           :message :no-change-from-current-resource-or-path}
;;          :else (new-collector! params))))

;;Creating Collector:
;;:db-fy
;;:collector-already-exists
;;:other-collector-with-path-already-exists
;;:path-failed-to-validate
;;:resource-failed-to-evalidate
;;:db-failed-to-update
;;:add-collector
;;
;;Updating Collector:
;;:db-fy
;;:name-param-does-not-match-path
;;:no-change-from-current-resource-or-path
;;start at: :other-collector-with-path-already-exists



(defn validate-path [path]
  (let [evald-path (clojure.edn/read-string path)]
    (cond (symbol? evald-path) path
          (and (vector? evald-path)
               (every? #(or (keyword? %)
                            (string? %))
                       evald-path)) evald-path
          (string? evald-path) evald-path)))

(defn valid-path? [{:keys [path] :as collector}]
  (if-let [valid-path (validate-path path)]
    (assoc collector :path valid-path)
    {:status :failure
     :message :invalid-path
     :details path}))

(defn blank-field?
  ([m & fields]
   (loop [fs fields]
     (if fields
       (if (blank? ((first fields) m))
         {:status :failure
          :message (keyword (next (str (first fields) "-cannot be blank")))}
         (recur (next fs)))))))

(defn missing-field?
  ([m & fields]
   (loop [fs fields]
     (if fields
       (if (blank? ((first fields) m))
         {:status :failure
          :message (keyword (next (str (first fields) "-must-have-a-value")))}
         (recur (next fs)))))))

(defn valid-name? [{:keys [name] :as collector}]
  (cond (not (string? name))
        {:status :failure
         :message :name-must-be-string}
        (clojure.string/include? name ":")
        {:status :failure
         :message :name-cannot-include-colon}
        (clojure.string/starts-with? name "/")
        {:status :failure
         :message :name-cannot-start-with-slash}
        (clojure.string/includes? name " ")
        {:status :failure
         :message :name-cannot-include-whitespace}
        :else collector))

(defn collector-already-exists? [{:keys [name] :as collector}]
  (if-let [other-collector (get-collector name)]
    {:status :failed
     :message :collector-already-exists
     :details other-colls}
    collector))

(defn other-collector-with-path? [{:keys [path] :as collector}]
  (if-let [other-colls (not-empty
                        (crux/q
                         (crux/db app-db)
                         {:find ['e]
                          :where [['e :stored-function/type :collector]
                                  ['e :collector/path path]]}))]
    collector
    {:status :failed
     :message :collector-with-path-already-exists
     :details other-colls}))

(defn evalidate [resource-map]
  "validates, sanitizes, and evaluates the resource map from db CURRENTLY UNSAFE (but necessary)"
  (binding [*ns* collector-ns]
    (try
      (if (string? resource-map)
        (yada/resource (eval (read-string resource-map)))
        (yada/resource (eval resource-map)))
      (catch Exception e {:status :failure
                          :message :unable-to-evalidate-resource
                          :details (.getMessage e)}))))

(defn evalidated? [{:keys [name path resource] :as collector}]
  (let [e (evalidate resource)]
    (if (= (type e) yada.resource.Resource)
      [collector e]
      e)))

(defn db-fy [{:keys [name resource path]}]
  {:crux.db/id (keyword "collector" name)
   :collector/name (keyword name)
   :collector/resource (read-string resource)
   :collector/path path
   :stored-function/type :collector})

(defn added-to-db? [{:collector/keys [name resource path] :as collector}
                    evald-resource]
  (if (crux/submit-tx app-db [[:crux.tx/put (db-fy collector)]])
    [collector evald-resource]
    {:status :failure
     :message :db-failed-to-update}))

(defn add-collector!
  ([{:collector/keys [path resource name] :as params}]
   (if-let [r (evalidate resource)]
     (add-collector! name path r)))
  ([name path r]
   (dosync
    (swap! resource-map (fn [old-map]
                          (assoc old-map name r)))
    (swap! atomic-routes (fn [old-map]
                           (assoc old-map path name))))))

(defn added-to-collectors? [{:collector/keys [name path] :as collector}
                            evald-resource]
  (do (add-collector! name path evald-resource)
      {:status :success
       :message :collector-added
       :details collector}))

(defn start-collectors! []
  (do (println "Starting Collectors")
      (let [colls (get-collectors)
            started-colls (map add-collector! colls)]
        (if (= (count colls)
               (count (last started-colls)))
          (println "Started Collectors!")
          (println "Failed to Start Collectors.")))))

(defstate collector-state
  :start
  (start-collectors!)
  :stop
  (dosync (reset! atomic-routes {})
          (reset! resource-map {})))

(def user-sub
  (fn [ctx]
    (let [path-info (get-in ctx [:request :path-info])]
      (if-let [path (bidi/match-route ["" @atomic-routes] path-info)]
        (@resource-map (:handler path))
        (as-resource "Endpoint not found")))))

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

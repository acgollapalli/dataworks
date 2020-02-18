(ns dataworks.collector
  (:require
   [dataworks.db.app-db :refer [app-db]]
   [dataworks.db.user-db :refer [user-db]]
   [dataworks.collectors :refer [collector-ns]]
   [monger.collection :as mc]
   [monger.operators :refer :all]
   [monger.conversion :refer [to-object-id]]
   [monger.json]
   [yada.yada :refer [as-resource] :as yada]
   [clojure.pprint :refer [pprint] :as p]
   [bidi.bidi :as bidi]))

(defn get-collectors []
  (mc/find-maps app-db "collectors"))

(defn get-collector [id]
  (mc/find-one-as-map app-db "collectors" {:_id (to-object-id id)}))

;; this function also needs to eval the :handler functions
;; and ensure that the functions included in the handler are
;; in this namespace
(defn collector-assoc [coll collector]
  (assoc-in coll
            [(collector :_id)]
            (dissoc (assoc collector
                           :handler
                           (read-string (collector :handler)))
                    :_id)))

(def resource-map
  (atom
   {}))

(def atomic-routes
  (atom {}))

;; TODO ADD RESOURCE SECURITY VALIDATION!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!1111111111
;; TODO Create a custom namespace for safe evaluation with unsafe functions removed.
;;      Right now we evaluate in 'dataworks.collector. We should evaluate in a dedicated
;;      namespace.
(defn evalidate [resource-map]
  "validates, sanitizes, and evaluates the resource map from db CURRENTLY UNSAFE (but necessary)"
  (binding [*ns* collector-ns]
    (if (string? resource-map)
      (yada/resource (eval (read-string resource-map))) ;; TODO add case for eval fail
      (yada/resource (eval resource-map)))))

;; TODO ADD PATH VALIDATION!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!11111
;; TODO FIGURE OUT HUMAN READABLE ID'S IN MAPS WITHOUT DUPLICATES!!
(defn update-collectors! [{:keys [path resource _id :as coll]}]
      (let [res (evalidate resource)
            id (keyword (str _id))]
        (dosync
         (swap! resource-map (fn [old-map]
                               (assoc old-map id res)))
         (swap! atomic-routes (fn [old-map]
                                (assoc old-map path id))))))

(defn start-collectors! []
  (do (println "Starting Collectors")
      (let [colls (get-collectors)
            started-colls (map update-collectors! colls)]
        (if (= (count colls)
               (count (last started-colls)))
          (println "Started Collectors!")
          (println "Failed to Start Collectors.")))))

(defn create-collector! [collector]
  (if-let [coll (mc/insert-and-return app-db "collectors" collector)]
    (do
      ;;(p/pprint collector)
      ;;(p/pprint coll)
      (update-collectors! coll)
      coll)))

;; TODO VERIFY THAT COLLECTOR UPDATED IN DB BEFORE CALLING update-collectors!
(defn update-collector! [id params]
  (let [update (mc/update-by-id app-db "collectors" (to-object-id id)
                                {$set (reduce (fn [m [k v]]
                                                (assoc m k v))
                                              {}
                                              params)})

        coll (get-collector id)]
    ;;(p/pprint coll)
    (update-collectors! coll)
    coll))

(def user-sub
   (fn [ctx]
      (let [path-info (get-in ctx [:request :path-info])]
        ;;(println path-info)
        (if-let [path (bidi/match-route ["/" @atomic-routes] path-info)]
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
    ;;:access-control {:realm "developer"
    ;;                 :scheme ????
    ;;                 :verify ????}
    :methods {:get {:response (fn [ctx] (get-collectors))
                    :produces "application/json"}
              :post
              {:consumes #{"application/json"}
               :produces "application/json"
               :response
               (fn [ctx]
                 (let [body (ctx :body)]
                   ;;(p/pprint (type (:body ctx)))
                   (create-collector! body)))}}}))

;; TODO ADD AUTHENTICATION!!!!!!!!!!!!!!!!!1111111111111
(def collector
  (yada/resource
   {:id :collector
    :description "resource for individual collector"
    :parameters {:path {:id String}} ;; do I need plurumatic's schema thing?
    ;;:access-control {:realm "developer"
    ;;                 :scheme ????
    ;;                 :verify ????}
    :methods {:get
               {:produces "application/json"
               :response
               (fn [ctx]
                 (let [id (get-in ctx [:request :route-params :id])]
                   (get-collector id)))}
              :post
              {:consumes #{"application/json"}
               :produces "application/json"
               :response
               (fn [ctx]
                 (let [id (get-in ctx [:request :route-params :id])
                       body (ctx :body)]
                   (update-collector! id (remove :_id body))))}}}))

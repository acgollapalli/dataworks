(ns dataworks.core
  (:require
   [clojure.edn :as edn]
   [dataworks.authentication :as auth]
   [dataworks.resource :refer [creation-resource
                               update-resource
                               user-resource]]
   [mount.core :refer [defstate] :as mount]
   [yada.yada :refer [listener as-resource]])
  (:gen-class))

(def routes
  ["/"
   [["app/"
     {"collector" (creation-resource :collector)
      "collector/" (update-resource :collector)
      "internal" (creation-resource :internal)
      "internal/" (update-resource :internal)
      "transactor" (creation-resource :transactor)
      "transactor/" (update-resource :transactor)
      "transformer" (creation-resource :transformer)
      "transformer/" (update-resource :transformer)
      "register" auth/register
      "login" auth/login
      "admin/user-roles/" auth/admin-user-roles}]
    ["user/" user-resource]
    [true (as-resource nil)]]])

(def port
  (-> "config.edn"
      slurp
      edn/read-string
      :port))

(defstate svr
  :start
  (listener routes {:port port})
  :stop
  ((:close svr)))

(def states
  ["#'dataworks.db.app-db/app-db"
   "#'dataworks.db.user-db/user-db"
   "#'dataworks.collector/collector-state"
   "#'dataworks.transactor/transactor-state"
   "#'dataworks.internal/internal-state"
   "#'dataworks.heartbeat/heartbeat-chan"
   "#'dataworks.heartbeat/heartbeat"
   "#'dataworks.transformer/transformer-state"
   "#'dataworks.stream/stream-state"
   "#'dataworks.stream/producers"
   "#'dataworks.core/svr"])

(defn go []
  (apply mount/start states))

(defn stop []
  (apply mount/stop states))

(defn reset []
  (println (stop))
  (println (go)))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (do
    (go)
    (print "Herro")
    (thread/sleep 100) ;; remove these in prod.
    (print " .")
    (thread/sleep 100)
    (print " .")
    (thread/sleep 100)
    (print " . ")
    (thread/sleep 100)
    (println "ZA WARRUDO!!!!!")))

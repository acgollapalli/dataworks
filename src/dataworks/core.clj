(ns dataworks.core
  (:require
   [clojure.edn :as edn]
   [dataworks.authentication :as auth]
   [dataworks.collector :as c]
   [dataworks.internal :as i]
   [dataworks.transactor :as t]
   [mount.core :refer [defstate] :as mount]
   [yada.yada :refer [listener as-resource]])
  (:gen-class))

(def routes
  ["/"
   [["app/"
     {"collector" c/collectors
      "collector/" c/collector
      "transactor" t/transactors
      "transactor/" t/transactor
      "internal" i/internals
      "internal/" i/internal
      "register" auth/register
      "login" auth/login}]
    ["user/" c/user]
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
   "#'dataworks.collectors/db"
   "#'dataworks.collector/collector-state"
   "#'dataworks.transactor/transactor-state"
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
    (mount/start)
    (println "Dio says: Herro Warrudo!!")))

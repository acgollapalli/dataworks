(ns dataworks.core
  (:require
   [clojure.edn :as edn]
   [dataworks.resource.dev :as dev]
   [dataworks.resource.user :as user]
   [dataworks.utils.function :refer [stored-fns]]
   [mount.core :refer [defstate] :as mount]
   [yada.yada :refer [listener as-resource]])
  (:gen-class))

(defstate dev-server
  :start
  (when dev/routes
    (listener dev/routes {:port dev/port}))
  :stop
  (when dev-server
    ((:close dev-server))))

(defstate user-server
  :start
  (when user/routes
    (listener user/routes {:port user/port}))
  :stop
  (when user-server
    ((:close user-server))))

(defn -main
  [& args]
  (mount/start)
  (doseq [f stored-fns]
    (println f "started."))
  (when dev-server
    (println "Dataworks Dev Server Started."))
  (when user-server
    (println "Dataworks User Server Started.")))

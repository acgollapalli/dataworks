(ns dataworks.core
  (:require
   [clojure.edn :as edn]
   [dataworks.authentication :as auth]
   [dataworks.resource :refer [creation-resource
                               update-resource
                               user-resource]]
   [dataworks.utils.function :refer [stored-fns]]
   [mount.core :refer [defstate] :as mount]
   [yada.yada :refer [listener as-resource]])
  (:gen-class))

(def routes
  ["/"
   [["app/"
     {"collector" (creation-resource :collector)
      "collector/" (update-resource :collector)
      "transactor" (creation-resource :transactor)
      "transactor/" (update-resource :transactor)
      "transformer" (creation-resource :transformer)
      "transformer/" (update-resource :transformer)
      "stream" (creation-resource :stream)
      "stream/" (update-resource :stream)
      "register" auth/register
      "login" auth/login
      "admin/user-roles/" auth/admin-user-roles}]
    ["user/" user-resource]
    [true (as-resource nil)]]])

(def port
  (or 
   (-> "config.edn"
      slurp
      edn/read-string
      :port)
   3000))

(defstate svr
  :start
  (listener routes {:port port})
  :stop
  ((:close svr)))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (do
    (mount/start)
    (doseq [f stored-fns]
      (println f "started"))
    (println)
    (print "Hello")
    (Thread/sleep 750) ;; remove these in prod.
    (print " .")
    (Thread/sleep 750)
    (print " .")
    (Thread/sleep 750)
    (print " . ")
    (Thread/sleep 750)
    (println "ZA WARRUDO!!!!!")))

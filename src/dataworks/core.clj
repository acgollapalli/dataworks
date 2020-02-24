(ns dataworks.core
  (:require
   [bidi.bidi :as bidi]
   [clojure.core.async :refer [go-loop]]
   [dataworks.authentication :as auth]
   [dataworks.collector :as c]
   [dataworks.transactor :as t]
   [mount.core :refer [defstate] :as mount]
   [yada.yada :refer [listener resource as-resource]])
  (:gen-class))

(defn routes []
    ["/"
     [["app/"
       {"collector" (c/collectors)
        ["collector/" :id] (c/collector)
        "transactor" (t/transactors)
        ["transactor/" :id] (t/transactor)
        "register" (auth/register)
        "login" (auth/login)}]
      ["user" (c/user)]
      [true (as-resource nil)]]])

(defstate svr
  :start
  (listener (routes) {:port 3000})
  :stop
  (:close svr))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (do
    (mount/start)
    (t/start-transactors!)
    (c/start-collectors!)
    (println "Herro Warrudo!!")))

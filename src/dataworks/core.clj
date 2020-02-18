(ns dataworks.core
  (:require
   [clojure.core.async :refer [go-loop]]
   [yada.yada :refer [listener resource as-resource]]
   [dataworks.collector :as c]
   [dataworks.transactor :as t]
   [bidi.bidi :as bidi]
   [mount.core :refer [defstate] :as mount])
  (:gen-class))

(def routes
    ["/"
     [["app/"
       {"collector" c/collectors
        ["collector/" :id] c/collector
        "transactor" t/transactors
        ["transactor/" :id] t/transactor}]
      ["user" c/user]
      [true (as-resource nil)]]])

(defstate svr
  :start
  (listener routes {:port 3000}))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (do
    (mount/start)
    (t/start-transactors!)
    (c/start-collectors!)
    (println "Herro Warrudo!!")))

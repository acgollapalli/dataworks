(ns dataworks.core
  (:require
   [yada.yada :refer [listener resource as-resource]]
   [dataworks.collector :as c]
   [bidi.bidi :as bidi])
  (:gen-class))

(def routes
    ["/"
     [["app/"
       {"collector" c/collectors
        ["collector/" :id] (as-resource "collector id")}]
      ["user" c/user]
      [true (as-resource nil)]]])

(def svr
  (listener
   routes
   {:port 3000}))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))

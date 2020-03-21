(ns dataworks.core
  (:require
   [bidi.bidi :as bidi]
   [clojure.edn :as edn]
   [dataworks.authentication :as auth]
   [dataworks.collector :as c]
   [dataworks.internal :as i]
   [dataworks.transactor :as t]
   [mount.core :refer [defstate] :as mount]
   [yada.yada :refer [listener resource as-resource]])
  (:gen-class))

(defmacro ->? [x & forms]
  "A utility function for validation of data and transactions"
  (loop [x x
         forms forms]
    (if-not forms
      x
      (let [next-form (first forms)
            status? `(fn ~'[{:keys [status] :as params}]
                       (if (= ~'status :failure)
                         ~'params
                         (-> ~'params ~next-form)))]
        (recur (list status? x) (next forms))))))

(def routes
  ["/"
   [["app/"
     {"collector" c/collectors
      "collector/" c/collector
      "transactor" t/transactors
      ["transactor/" :id] t/transactor
      "internal" i/internals
      ["internal/" :id] i/internal
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

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (do
    (mount/start)
    (println "Dio says: Herro Warrudo!!")))

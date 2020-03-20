(ns dataworks.collectors
  (:require
   [clojure.edn :refer [read-string]]
   [dataworks.db.user-db :refer [user-db]]
   [dataworks.transactor :refer [transact!]]
   [crux.api :as crux]
   [mount.core :refer [defstate] :as mount]
   [tick.alpha.api :as time]
   [yada.yada :refer [as-resource] :as yada]))

(defstate db
  :start user-db
  :end nil)

(def collector-ns *ns*)

;; This is where the actual collectors live.
;; They only live here at runtime.
;; DO NOT PUT CODE IN THIS NAMESPACE.

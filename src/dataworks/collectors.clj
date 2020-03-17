(ns dataworks.collectors
  (:require
   [clojure.edn :refer [read-string]]
   [dataworks.db.user-db :refer [user-db]]
   [dataworks.time-utils]
   [dataworks.transactor :refer [transact!]]
   [monger.collection :as mc]
   [monger.operators :refer :all]
   [monger.conversion :refer [to-object-id]]
   [monger.json]
   [mount.core :refer [defstate] :as mount]
   [tick.alpha.api :as t]
   [yada.yada :refer [as-resource] :as yada]))

(defstate db
  :start user-db
  :end nil)

(def collector-ns *ns*)

;; This is where the actual collectors live.
;; They only live here at runtime.
;; DO NOT PUT CODE IN THIS NAMESPACE.

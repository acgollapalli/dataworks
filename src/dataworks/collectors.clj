(ns dataworks.collectors
  (:require
   [dataworks.db.user-db :refer [user-db]]
   [dataworks.transactor :refer [transact!]]
   [monger.collection :as mc]
   [monger.operators :refer :all]
   [monger.conversion :refer [to-object-id]]
   [monger.json]
   [tick.alpha.api :as time]
   [yada.yada :refer [as-resource] :as yada]))

(def db user-db)

(def collector-ns *ns*)

;; This is where the actual collectors live.
;; They only live here at runtime.
;; DO NOT PUT CODE IN THIS NAMESPACE.

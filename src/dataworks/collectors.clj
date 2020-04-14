(ns dataworks.collectors
  (:require
   [clojure.pprint :refer [pprint]]
   [dataworks.authentication :refer [authenticate
                                     authorize]]
   [dataworks.common :refer :all]
   [dataworks.db.user-db :refer [user-db
                                 submit-tx
                                 query
                                 entity]]
   [dataworks.stream-utils :refer [produce!]]
   [dataworks.time-utils :refer [consume-time]]
   [dataworks.transactor :refer [transact!]]
   [dataworks.transformer :refer [transformers]]
   [crux.api :as crux]
   [mount.core :refer [defstate] :as mount]
   [tick.alpha.api :as tick]
   [yada.yada :refer [as-resource] :as yada]
   [schema.core :refer [defschema] :as schema]))


(defstate db
  :start user-db
  :end nil)

(def collector-ns *ns*)

;; This is where the actual collectors live.
;; They only live here at runtime.
;; DO NOT PUT CODE IN THIS NAMESPACE.

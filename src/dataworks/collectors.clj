(ns dataworks.collectors
  (:require
   [camel-snake-kebab.core :as case]
   [clojure.pprint :refer [pprint]]
   [crux.api :as crux]
   [dataworks.authentication :refer [authenticate
                                     authorize]]
   [dataworks.utils.common :refer :all]
   [dataworks.db.user-db :refer [user-db
                                 submit-tx
                                 query
                                 entity]]
   [dataworks.streams :refer [stream!]]
   [dataworks.utils.time :refer [consume-time]]
   [dataworks.transactors :refer [transact!]]
   [dataworks.transformers :refer [transformers]]
   [dataworks.streams :refer [stream!]]
   [mount.core :refer [defstate] :as mount]
   [schema.core :refer [defschema] :as schema]
   [tick.alpha.api :as tick]
   [yada.yada :refer [as-resource] :as yada]))


(def collector-ns *ns*)


(def resource-map
  (atom
   {}))

(def atomic-routes
  (atom {}))

;; This is where the actual collectors live.
;; They only live here at runtime.
;; DO NOT PUT CODE IN THIS NAMESPACE.
;; (... beyond what's above)

(ns dataworks.internals
  (:require
   [cheshire.core :as cheshire]
   [crux.api :as crux]
   [dataworks.db.user-db :refer [user-db
                                 submit-tx
                                 query
                                 entity]]
   [dataworks.common :refer :all]
   [dataworks.streams :refer [stream!]]
   [dataworks.time-utils :refer :all]
   [dataworks.transactors :refer [transact!]]
   [dataworks.transformers :refer [transformers]]
   [tick.alpha.api :as tick]))

(def internal-ns *ns*)

(def internal-map
  (atom {}))


;; TODO add alias of user-db

;; This is where the actual internals live.
;; They only live here at runtime.
;; DO NOT PUT CODE IN THIS NAMESPACE.

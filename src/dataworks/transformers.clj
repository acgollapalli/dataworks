(ns dataworks.transformers
  (:require
   [dataworks.common :refer :all]
   [dataworks.db.user-db :refer :all]
   [tick.alpha.api :as tick]))

(def transformer-ns *ns*)

;; This is where the actual transformers live.
;; They only live here at runtime.
;; DO NOT PUT CODE IN THIS NAMESPACE.

;; TODO add computational libraries like neanderthal and
;; other math libs.

(ns dataworks.transactors
  (:require
   [cheshire.core :as cheshire]
   [clj-http.client :as client]
   [dataworks.transformer :refer [transformers]]
   [tick.alpha.api :as tick]))

(def transactor-ns *ns*)

;; This is where the actual transactors live.
;; They only live here at runtime.
;; DO NOT PUT CODE IN THIS NAMESPACE.

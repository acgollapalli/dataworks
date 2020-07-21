(ns dataworks.transactors
  (:require
   [camel-snake-kebab.core :as case]
   [camel-snake-kebab.extras :as case.extras]
   [cheshire.core :as cheshire]
   [clj-http.client :as client]
   [clojure.core.async :refer [go]]
   [dataworks.db.user-db :refer :all]
   [dataworks.utils.common :refer :all]
   [dataworks.utils.time :refer :all]
   [tick.alpha.api :as tick]))

(def transactor-ns *ns*)

(def transactor-map
  (atom {}))

(defn transact! [tname & args]
  (go (apply ((keyword tname) @transactor-map) args)))

(require '[dataworks.streams :refer [stream!]])
(require '[dataworks.transformers :refer [transformers]] )

;; This is where the actual transactors live.
;; They only live here at runtime.
;; DO NOT PUT CODE IN THIS NAMESPACE.
;; (... beyond what's above)

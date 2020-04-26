(ns dataworks.transformers
  (:require
   [dataworks.utils.common :refer :all]
   [dataworks.utils.time :refer :all]
   [dataworks.db.user-db :refer :all]
   [tick.alpha.api :as tick]))

(def transformer-ns *ns*)

(def transformer-map
  (atom {}))

(defn def-ify [xformer]
  `(def ~xformer
     ~(get @transformer-map (keyword xformer))))

(defmacro transformers
  [xformers & forms]
  (concat '(->let)
          (map def-ify xformers)
          forms))
(require '[dataworks.streams :refer [stream!]])
(require '[dataworks.transactors :refer [transact!]])

;; This is where the actual transformers live.
;; They only live here at runtime.
;; DO NOT PUT CODE IN THIS NAMESPACE.
;; (...beyond the above)

;; TODO add computational libraries like neanderthal and
;; other math libs.

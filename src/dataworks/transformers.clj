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

(defn get-xformers [xformers]
  (apply concat
         (map
          (juxt identity
                #(get @transformer-map
                     (keyword %)))
          xformers)))

(defmacro transformers
  [xformers & forms]
  (reverse
   (into (list)
        (concat [(symbol '->let)]
                (map #(apply
                       (partial replace-symbols %)
                       (get-xformers xformers))
                     forms)))))

(require '[dataworks.streams :refer [stream!]])
(require '[dataworks.transactors :refer [transact!]])

;; This is where the actual transformers live.
;; They only live here at runtime.
;; DO NOT PUT CODE IN THIS NAMESPACE unless that code
;; is actually something that transformers should be able
;; to run (like the transformers macro).

;; TODO add computational libraries like neanderthal and
;; other math libs.

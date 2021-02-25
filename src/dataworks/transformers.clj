(ns dataworks.transformers
  (:require
   [camel-snake-kebab.core :as case]
   [camel-snake-kebab.extras :as case.extras]
   [crux.api :as crux]
   [dataworks.utils.common :refer :all]
   [dataworks.utils.time :refer :all]
   [dataworks.db.user-db :refer :all]
   [tick.alpha.api :as tick]))

(def transformer-ns *ns*)

(def transformer-map
  (atom {}))

(defn transform
  [xform & args]
  (apply (get @transformer-map
              xform)
         args))

(defn get-xformers [xformers]
  (apply concat
         (map
          (juxt identity
                #(list partial transform (keyword %)))
          xformers)))

;; the reason this macro does a recursive find and replace
;; through the entire form, instead of just using the let* macro
;; is because the let* macro breaks namespaced transformers.
(defmacro transformers
  [xformers & forms]
  (reverse
   (into (list)
        (concat [(symbol '->let)]
                (map #(apply
                       (partial replace-these %)
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

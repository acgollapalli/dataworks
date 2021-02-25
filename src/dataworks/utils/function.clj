(ns dataworks.utils.function
  (:require
   [crux.api :as crux]
   [dataworks.db.app-db :refer [get-stored-functions app-db]]
   [dataworks.collector :refer [add-collector!]]
   [dataworks.transactor :refer [add-transactor!]]
   [dataworks.transformer :refer [add-transformer!]]
   [dataworks.stream :refer [start-stream! wire-streams!]]
   [mount.core :refer [defstate] :as mount]))

(defn start-stored-function!
  [f]
  (case (:stored-function/type f)
    :collector (add-collector! f)
    :transformer (add-transformer! f)
    :transactor (add-transactor! f)
    :stream (start-stream! f)))

(def start-function-xform
  (comp
   (map (juxt (comp :status start-stored-function!)
              :crux.db/id))
   (filter #(= :success (first %))) ;; TODO add error-logging
   (map second)))

(defn start-functions!
  []
  (let [f (into #{}
                start-function-xform
                (get-stored-functions))]
    (wire-streams!)
    f))

(defstate stored-fns
  :start
  (start-functions!))

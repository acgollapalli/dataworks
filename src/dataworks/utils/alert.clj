(ns dataworks.utils.alert
  (:require
   [clojure.core.async :refer [go-loop close! alt! timeout chan]]
   [crux.api :as crux]
   [dataworks.db.app-db :as db]
   [dataworks.transactors :refer [transact!]]
   [mount.core :refer [defstate]]))

(defn get-alerts
  []
  (db/query
   '{:find [id handler params]
     :where [[id :alert/timestamp]
             [id :alert/handler handler]
             [id :alert/params params]]}))

(defn handle-alert
  [[id handler params]]
  (let [{:alert/keys [claim] :as alert} (db/entity id)]
    (when-not claim
      (let [tx (db/submit-tx
                [[:crux.tx/put
                  (assoc alert
                         :alert/claim
                         :claimed)]])]
        (crux/await-tx db/app-db tx)
        (when (crux/tx-committed? db/app-db tx)
          ;; TODO add error handling here
          (apply (partial transact! handler) params)
          (db/submit-tx
           [[:crux.tx/delete id]]))))))

(defn handle-alerts!
  [channel]
  (go-loop []
    (alt! (timeout 1000) ([]
                          (doseq
                              [alert (get-alerts)]
                            (handle-alert alert))
                          (recur))
          channel :closed))
  channel)

(defstate alerts
  :start
  (handle-alerts! (chan))
  ;;(println "alerts not-implemented")
  :stop
  (close! alerts)
  ;;(println "unimplemented alerts have stopped")
  )

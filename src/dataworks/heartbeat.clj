(ns dataworks.heartbeat
  (:require
   [clojure.core.async :refer [chan close! go-loop timeout go >! alt!]]
   [clojure.java.io :as io]
   [crux.api :as crux]
   [dataworks.common :refer :all]
   [dataworks.db.app-db :refer [query submit-tx entity app-db]]
   [mount.core :refer [defstate]]
   [tick.alpha.api :as tick]))

(def uuid
  (str "uuid." (java.util.UUID/randomUUID)))

(def node-id
  (keyword "node" uuid))

(def heartbeat-id
  (keyword "heartbeat" uuid))

(def heartbeat-duration
 (if-let [duration (-> "config.edn"
                        slurp
                        read-string
                        :heartbeat-duration)]
   duration
   (tick/new-duration 1 :seconds)))

(def heartbeat-delay
  (int
   (/ (* 9 (tick/millis heartbeat-duration))
      10)))

(defn check-in []
  (let [app-beat (entity :dataworks/heartbeat)]
    (if app-beat
      (submit-tx [[:crux.tx/cas
                 app-beat
                 (update app-beat
                         :dataworks/nodes
                         #(conj % node-id))]])
      (submit-tx [[:crux.tx/put
                   {:crux.db/id :dataworks/heartbeat
                    :dataworks/nodes [node-id]
                    :dataworks/started (tick/inst
                                        (tick/now))}]]))))

(defn send-heartbeat []
   (submit-tx [[:crux.tx/put
                 {:crux.db/id node-id
                  :node/status :up
                  :node/heartbeat heartbeat-id}]
                [:crux.tx/put
                 {:crux.db/id heartbeat-id}
                 (tick/inst
                  (tick/+ (tick/now)
                          heartbeat-duration))]]))

(defn missing-siblings? [brother sister
                         sister's-husband brother-in-law]
  (let [missing-nodes (map first
                           (query
                            '{:find [e]
                              :where [[e :node/heartbeat heart]
                                      [heart :crux.db/id]]}))
        missing-siblings (filter #(contains?
                                   missing-nodes
                                    %)
                                 #{brother sister
                                   sister's-husband
                                   brother-in-law})]
  (if (not (empty? missing-siblings))
    (into #{} missing-siblings))))

(defn take-over-responsibility [[responsibility]]
  (let [sibling's-responsibility (entity responsibility)
        my-responsibility (update
                           sibling's-responsibility
                           :responsibility/node
                           node-id)]
   (submit-tx [[:crux.db/cas
                sibling's-responsibility
                my-responsibility]])))

(defn take-over-for-sibling [sibling]
  (map take-over-responsibility
      (query '{:find [responsibility]
           :where [responsibility :resposibility/node sibling]})))

(defn check-on-siblings []
  (let [nodes (query '{:find [nodes]
                     :where [[e :crux.db/id :dataworks/heartbeat]
                             [e :dataworks/nodes nodes]]})]
    (if-let [index (get
                    (zipmap nodes
                            (range (count nodes)))
                    node-id)]
      (let [brother (get nodes (mod (- index 1)
                                    (count nodes)))
            sister (get nodes (mod (+ index 1)
                                   (count nodes)))
            sister's-husband (get nodes (mod (+ index 2)
                                             (count nodes)))
            brother-in-law (get nodes (mod (+ index 3)
                                           (count nodes)))
            missing-siblings (missing-siblings?
                              brother
                              sister
                              sister's-husband
                              brother-in-law)]
        (when missing-siblings
         (when (contains? missing-siblings brother)
           (take-over-for-sibling brother))
         (when (and
                (contains? missing-siblings sister)
                (contains? missing-siblings sister's-husband))
           (take-over-for-sibling sister))
         (when (and
                (contains? missing-siblings sister)
                (contains? missing-siblings sister's-husband)
                (contains? missing-siblings brother-in-law))
           (take-over-for-sibling sister's-husband)))))))

(defn enter-the-heartbeat
  ;; WhoOoah. I'm gonna take my chances right now
  "Sends heartbeat and checks on siblings"
  []
  (crux/sync app-db) ;; Sync db
  (send-heartbeat)
  (check-on-siblings))

(defstate  heartbeat-chan
  :start
  (chan)
  :stop
  (close! heartbeat-chan))

(defstate heartbeat
  :start
  (do
    (println "Starting Heartbeat!")
    (check-in)
    (enter-the-heartbeat)
    (go-loop []
      (alt!
        heartbeat-chan :stopped
        (timeout heartbeat-delay)
        ([]
         (enter-the-heartbeat)
         (recur)))))
  :stop
  (go (>! heartbeat-chan :stop)))

(ns dataworks.coordination
  (:require
   [core.async :refer [chan pub >! go-loop buffer close!]]
   [mount.core :refer [defstate]]))

(defstate function-chan
  :start (chan 1000)
  :stop (close! function-chan))

(defstate function-pub
  :start (pub function-chan :stored-function/type))

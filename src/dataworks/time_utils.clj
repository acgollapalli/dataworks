(ns dataworks.time-utils
  (:require
   [cheshire.generate :refer [add-encoder encode-str]]
   [tick.alpha.api :as tick]))

(add-encoder java.time.Duration encode-str)
(add-encoder java.time.Instant encode-str)

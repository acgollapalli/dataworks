(ns dataworks.time-utils
  (:require [monger.conversion :refer :all]
            [tick.alpha.api :as t]))

(extend-protocol ConvertFromDBObject
  java.util.Date
  (from-db-object [^java.util.Date input keywordize]
    (t/instant input)))

(extend-protocol ConvertToDBObject
  java.time.Instant
  (to-db-object [^Instant input]
    (to-db-object (t/inst input))))

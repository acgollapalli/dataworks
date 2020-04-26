(ns dataworks.time-utils
  (:require
   [cheshire.generate :refer [add-encoder encode-str]]
   [dataworks.common :refer [read-string]]
   [tick.alpha.api :as t :refer [instant]])) ;; everywhere else it's tick,
                                             ;; but we use it too damn often here

(add-encoder java.time.Duration encode-str)
(add-encoder java.time.Instant encode-str)

(defn consume-time
  "Produces: java.time.Instant, :never, or nil, or
             a sequence/lazy-sequence of the same.

   Accepts the following as time-literals, java types,
   a string representation which tick/parse can turn
   into one of the acceptable types, a sequence of any
   of the above or either of the previous serialized
   (stringified) by clojure.core/pr-str :

   Consumes: java.time.Instant (#time/instant)
             java.util.Date (#inst)
             java.time.LocalDate (#time/date)
             java.time.Duration (#time/duration)
                 (returns as now + duration)
             java.time.Period (#time/period)
                 (returns as today's date + period)
             java.time.DayOfWeek (#time/day-of-week)
                 (returns as next day-of-week)
             int (number of milliseconds,
                  returns as now + milliseconds)
             keyword indicating a duration or period
                 (ex: :millis, :seconds, :minutes :hours,
                  :weeks, :months, :years)
             keyword indicating never (:never)

  WARNING: Currently bad inputs don't produce exceptions, but
           just return nil. This is because I haven't figured
           out how to handle typed polymorphism in Clojure yet."
  ([time?] (consume-time (t/now) time?))
  ([now time?]
   (let [literal-time? (try (read-string time?)
                            (catch Exception _))
         string-time? (try (t/parse time?)
                           (catch Exception _))
         time (if literal-time? literal-time?
                  (if string-time? string-time?
                      (if-not (string? time?)
                        time?)))
         type? #(= (type time) %)]
     (cond
       (not time) nil
       (seq? time?) (rest
                     (reductions
                      (fn [init t]
                        (consume-time init t))
                      now
                      time))
       (type? java.time.Instant) time
       (type? java.util.Date) (instant time)
       (type? java.time.LocalDate) (instant
                                    (t/at
                                     (t/date time)
                                     (t/time "00:00")))
       (type? java.time.Duration) (t/+ now time)
       (type? java.time.Period) (instant
                                 (t/at (t/+ (t/date now)
                                            time)
                                       (t/time "00:00")))
       (type? java.time.DayOfWeek) (loop [day (t/today)]
                                     (if (= (t/day-of-week day) time)
                                       (instant (t/at day (t/time "00:00")))
                                       (recur (t/+ day #time/period "P1D"))))
       (int? time) (t/+ now (t/new-duration time :millis))
       (= :never time) :never
       (keyword? time) (try (t/truncate
                             (t/+ now
                                  (t/new-duration 1 time))
                             time)
                            (catch Exception _
                              (try (instant
                                    (t/at
                                     (t/+ (t/date now)
                                          (t/new-period 1 time))
                                     (t/time "00:00"))) ;; chucking out exceptions
                                   (catch Exception _)))))))) ;; like this is probably bad



(defn get-millis [t]
  (if-let [time (consume-time t)]
  (t/millis (t/between (t/now)
                       time)
            (t/now))))

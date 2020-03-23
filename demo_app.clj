;; Hi, This is the Demo App!

;; Today we're building a generic monitoring application that will let you:
;;      Collect logs from an app helpfully called demo-app via REST API,
;;      Track whether they it has started and is running.
;;      Notify yourself if there's a problem, or receive hourly OK text.

;; We're using Dataworks to do this, as it's the Dataworks Demo App, after all!

;; What do we assume you know?
;;     Clojure. If not, read Clojure for the Brave and True (braveclojure.com)
;;         to awaken your inner clojure viking. You don't need to be Rich Hickey
;;         or Zach Tellman to follow along. You just need the basics.
;;     What a REST API is, and a bit about web tech. YouTube is the place to go
;;         to learn more about this.

;; Now what is Dataworks, and why am I here?
;; Dataworks is an web app that lets you build web apps, and make changes as
;; your apps run, so you don't have to redeploy every time you make a minor
;; change. Dataworks stores your code, written in clojure in a database, and
;; runs it and any changes as you make them. In practical terms: Need a new
;; endpoint? Need to add some functionality or fix a bug? Just change those
;; things while the rest of the app runs happily oblivious of the changes that
;; are taking place.
;; As for why you're here? Why are any of us here? IDK, but we aren't here to
;; discuss existential questions.

;; Now the first thing to understand about Dataworks is that it works on
;; the basis of Stored Functions. What is a Stored Function?

;; A Stored Function is a Clojure expression that is stored in a database
;; and evaluated at runtime, and reevaluated every time it changes. It is
;; usually a function, or a map of functions, or something that evaluates
;; to a function, depending on what we're talking about. Basically, we treat
;; Code as Data and store it in a databased. Now, due to the requirements of
;; programmers writing things that are actually useful, these usually aren't
;; pure functions, however a well architected Dataworks Stored Function will
;; rely on only a single store of app state, the Database.

;; There are 4 types of Stored Functions in Dataworks:
;;     The Collector
;;     The Transactor
;;     The Internal
;;     The Transformer

;; Of the four of them, only the Transformer is actually a pure function,
;; and even then, that's only if you don't try to make it an impure function
;; by calling a transactor or something.

;; We'll go through each of the four by providing an actual meaningful example,
;; as well as some exposition on what each one is meant to do, and not to do.
;; Though again, this is clojure. You're allowed more flexibility than with
;; any other language, or application framework, or db/stored-procedure engine.
;; With great power comes great responsibility, and how you architect your
;; Dataworks app is ultimately up to you.

;; The Collector
;; The Collector is arguably the most fundamental of the four types of Stored
;; Functions in Dataworks. Not in any kind of fancy shmancy programming sense.
;; This isn't a Rich Hickey talk, and I'm not nearly so sophisticated. Dataworks
;; was conceived as a way to create a new API endpoint for your application
;; without taking down your entire app and redeoploying it.
;; To do that, I invented the collector.

;; So what the hell is a Collector?
;; A Collector is an API endpoint. It's a web address, along with some functions
;; that say what happens when someone uses that web address. When you add a collector
;; you add an endpoint to your API while your API is running, and when you change a
;; collector, you change what that API endpoint does while the API is running.
;; Pretty neat huh?
;;
;; We use a library called Yada to tell Dataworks what to do when a request is made
;; of our endpoint. A collector is a Yada Resource. And a Yada Resource is a map of
;; functions and other info about things like authentication, and a brief description.
;;

;; Here's the thing, Right you're only allowed to evaluate 1 s-expression.
;; I'd be tempted to call it a security thing, but really it's just a limitation
;; we haven't gotten around yet. However, because clojure is clojure, you can
;; create functions inside of expresions, and even name them if you do it inside
;; a let macro.

;; Collectors have access to your mongo database, a time library called tick to tell
;; time, a safe read-string so you can accept serialized edn params, and yada, so you
;; can make yada resources easily

;; Our Demo Collector:
;; This lives in the collectors namespace in which the following are provided:
   [dataworks.common :refer :all]
   [dataworks.db.user-db :refer [user-db submit-tx query entity]]
   [dataworks.time-utils :refer [consume-time]]
   [dataworks.transactor :refer [transact!]]
   [crux.api :as crux]
   [mount.core :refer [defstate] :as mount]
   [tick.alpha.api :as tick]
   [yada.yada :refer [as-resource] :as yada]


;; Our collector (the json field names are commented.)
;; Path:
"demo-collector"
;; Name:
"demo-colector"
;; Resource:
{:id :demo-collector
 :description "captures data from applications with discrete stop and start times"
 :methods {:post
           {:consumes #{"application/json"}
            :produces "application/json"
            :response (fn [ctx]
                        (let [{:keys [app event details next-event]} (:body ctx)
                              id (keyword app "log")
                              now (tick/now)
                              pr-event {:crux.db/id id
                                        :log/event event
                                        :log/time (tick/now)
                                        :app/name (keyword app)
                                        :app/await (keyword app "await")}
                              details? #(if details
                                          (assoc % :log/details details)
                                          %)
                              top-of-the-hour (consume-time now :hours)
                              next-event? #(let [nxt (consume-time now next-event)]
                                             (assoc %
                                                    :await/next-event
                                                    (if nxt nxt top-of-the-hour)))
                              tx-event (-> pr-event
                                           details?
                                           next-event?)
                              await-event {:crux.db/id (keyword app "await")
                                           :app/name (keyword app)
                                           :await/timestamp now}]
                          (submit-tx [[:crux.tx/put tx-event]
                                      (when-not (= :never (:await/next-event tx-event))
                                        [:crux.tx/put await-event
                                         (tick/inst (:await/next-event tx-event))])])
                          (entity id)))}}}

;; A brief explanation of the various convenience functions used in the above:

;; consume-time:
;;    Produces: java.time.Instant, :never, or nil
;;
;;    Accepts the following as time-literals, java types,
;;    a string representation which tick/parse can turn
;;    into one of the acceptable types, a sequence of any
;;    of the above or either of the previous serialized
;;    (stringified) by clojure.core/pr-str :
;;
;;    Consumes: java.time.Instant (#time/instant)
;;              java.util.Date (#inst)
;;              java.time.LocalDate (#time/date)
;;              java.time.Duration (#time/duration)
;;                  (returns as now + duration)
;;              java.time.Period (#time/period)
;;                  (returns as today's date + period)
;;              java.time.DayOfWeek (#time/day-of-week)
;;                  (returns as next day-of-week)
;;              int (number of milliseconds,
;;                   returns as now + milliseconds)
;;              keyword indicating a duration or period
;;                  (ex: :millis, :seconds, :minutes :hours,
;;                   :weeks, :months, :years)
;;              keyword indicating never (:never)
;;
;;   WARNING: Currently bad inputs don't produce exceptions, but just return nil.
;;            This is because I haven't figured out how to handle typed polymorphism
;;            in Clojure yet.
;;
;; This way our resource will accept serialized time-literals, including durations,
;; as well as dates like "2020-04-05" and ISO datetimes of the sort that are normally
;; used in JSON documents. We use tick, which bundles the time-literals library.
;; Experiment with tick in the repl to figure out what each of them looks like.

;; submit-tx
;;   An aliased form of crux/submit-tx. Effectively it's #(crux/submit-tx db %)
;; Added because I got tired of typing db. See crux documentation for more info.

;; query
;;   An aliased form of crux/q. Effectively it's #(crux/q (crux/db db) %). Although
;;   it also accepts optional valid-time and transaction-time arguments for more
;;   intensive queries.
;;   Arities: [query], [valid-time query], [valid-time transaction-time query]
;; Added because I got tired of typing (crux/db db).


;; The Transactor
;; The Transactor does a thing when it's called. That's it. It can do it as many times
;; as you call it to. It doesn't return anything (except whatever it is that a go-block
;; returns). But it does what you tell it to, when you tell it to. It's what our dads all
;; wish we'd have been.

;; The important thing about a transactor is that you can call it from other Stored Functions.
;; A Transactor is your ticket to the outside world. With a simple
   (transact! :your-transactor arguments)
;; you can send text-messages, emails, call other API's or whatever you want!

;; For his transactor we use the Twilio API to send ourselves text messages. I might
;; have sent an infinite loop of them while developing the transactor, but I did it so
;; you don't have to!
;; Again it's just one s-expression per Stored Function.
;; For transactors we give you clj-http so you can contact the outside world, cheshire
;; because, clj-http likes that, and our time library tick, for obvious reasons.

;; Our transactor that we use to text ourselves:
;; client is the included clj-http.client
;; The following are provided in the transactors namespace:
[cheshire.core :as cheshire]
[clj-http.client :as client]
[tick.alpha.api :as tick]

;; Our transactor
;; name:
"text"
;; func:
(fn [body phone-number]
  (let [twilio-sid "YOUR TWILIO SID"
        twilio-token "YOUR TWILIO TOKEN"
        hello-body {:Body (str body)
                    :From "YOUR TWILIO NUMBER"
                    :To phone-number}]
    (client/post (str "https://api.twilio.com/2010-04-01/Accounts/"
                      twilio-sid
                      "/Messages.json")
                 {:basic-auth [twilio-sid twilio-token]
                  :form-params hello-body})))

;; The Internal
;; The Internal does something on a timer, or whenever it thinks is necessary.
;; It's sort of like a scheduled task that can reschedule itself when it likes.
;; This one's a bit tricky, so I outlined what we're trying to do.

;; Our initial internal func
;; What it assumes:
;;   We have a demo app helpfully called "demo-app".
;;   demo-app runs every hour, and runs for a long time.
;;   When demo-app completes, it helpfully sends a "successfully-ended" event.
;;   When demo-app fails, we hope it sends an "ended-in-failure" event.
;;   However demo-app might not send anything at all.
;; What it does:
;;   Checks whether we've received any events in the past couple minutes from our demo-app
;;   If we have, then we wait a couple minutes and check again.
;;   If we haven't then we check whether the app closed successfully.
;;     If the app closed successfully, then we wait until the app is supposed to start again.
;;     If the app closed unsuccessfully, then we send a text to our harried dev/admin
;;     If we haven't then we wait a minute to check again.
;;       If we still haven't heard back, then we wait another minute.
;;         If we STILL haven't heard back
;;             Text our dev/admin that our app hasn't responded in a while.

;; Now, there's a couple of things to pay attention to here, as they're important,
;; and will make life easier. The first is that this function is hairy. So how
;; do we make it less hairy? By dividing it into a bunch of smaller functions!
;; But we only have one expression to work with, so what do we do? We use let !
;; We use let to define a bunch of smaller functions, and maps of functions, and
;; values to make our life easier. Just keep in mind it looks hairy because you're
;; looking at 6 or so functions, not just one.

;; This lives in the internals namespace, for which the following are provided:
[dataworks.db.user-db :refer [user-db]] ;; user-db is aliased as db for convenience
[cheshire.core :as cheshire]
[dataworks.transactor :refer [transact!]]
[crux.api :as crux]
[tick.alpha.api :as tick]

;; Our Internal
;; Query to find Apps that need to be monitored:
(crux/q (crux/db db)
        '{:find [app timestamp status next-event]
          :where [[e :app/name app]
                  [e :log/event status]
                  [e :await/next-event next-event]
                  [e :app/await await]
                  [await :await/timestamp timestamp]]})
;;Shorthand version:
(query '{:find [app timestamp status next-event]
          :where [[e :app/name app]
                  [e :log/event status]
                  [e :await/next-event next-event]
                  [e :app/await await]
                  [await :await/timestamp timestamp]]})
;; name:
"demo-app"
;; yes, we're naming it the same as the collector it's actual name is
;; ;internal/demo-app because everything is namespaced, so this is fine.

;; next-run:
#time/duration "PT1H"
;;You get this by running:
(tick/new-duration 1 :minutes)
;; Don't forget to run pr-string on it before sending!

;; initial-value:
{:events-checked-once {}}

;; function:
(fn [{:keys [events-checked-once]}]
  (let [events-to-check (query '{:find [app timestamp status next-event]
                                 :where [[e :app/name app]
                                         [e :log/event status]
                                         [e :await/next-event next-event]
                                         [e :app/await await]
                                         [await :await/timestamp timestamp]]})
        now (tick/now)
        waiting-since #(t/minutes (t/between % now))
        waiting-too-long? (fn [[app timestamp status next-event]]
                            (if-let [previous-timestamp (get events-checked-once app)]
                              (= timestamp previous-timestamp)))
        wait-a-bit-longer? #(not waiting-too-long? %)
        it's-been-too-long (filter waiting-too-long)
        let's-wait-a-bit-longer (filter wait-a-bit-longer)
        text-the-dev (fn [[app timestamp status next-event]]
                       (transact!
                        :text
                        (str app " has not checked in.\n\n"
                             "Expected information " (waiting-since next-event)
                             " minutes ago.\n"
                             "Have not heard from " app
                             "for " (waiting-since timestamp))))
        check-in-a-minute (fn [event-map [app timestamp status next-event]]
                            (assoc event-map app timestamp))
        we'll-check-in-a-minute #(reduce check-in-a-minute {} %)
        let-the-dev-know (map text-the-dev)]
    (if-not (empty? events-to-check)
      (when-not (empty? events-checked-once)
        (comp let-the-dev-know
              it's-been-too-long
              events-to-check)
        {:events-checked-once (check-in-a-minute
                               let's-wait-a-bit-longer
                               events-to-check)})
      {:events-checked-once {}})))


;; The Transformer:
;; So far I haven't written the code for Transformers yet so...
;; Transformers TODO in disguise!!!

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
;; time, a safe read-string so you can accept serrialized edn params, and yada, so you
;; can make yada resources easily

;; Our Demo Collector:
;; This lives in the transactors namespace in which the following are provided:
   [clojure.edn :refer [read-string]]
   [dataworks.db.user-db :refer [user-db]] ;; user-db can be called as db for convenience.
   [dataworks.transactor :refer [transact!]]
   [monger.collection :as mc]
   [monger.operators :refer :all]
   [monger.conversion :refer [to-object-id]]
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
                        (let [{:keys [app app-started event details app-ended]} (:body ctx)
                              start-time (t/parse app-started)
                              log-event
                              (fn []
                                (mc/update db app
                                           {:app-started start-time}
                                           {$push {:events (if details
                                                             [(keyword event) (t/now) details]
                                                             [(keyword event) (t/now)])}}
                                           {:upsert true}))]
                          (if-not event
                            (mc/insert-and-return db app
                                                  {:app-started start-time
                                                   :events [[:app-started (t/now)]]}))
                          (when event (log-event))
                          (when app-ended
                            (mc/update db app
                                       {:app-started start-time}
                                       {$set {:app-ended (t/parse app-ended)}}))
                          {:event-logged (if event event start-time)}))}}}

;; The Transactor
;; The Transactor does something when it's called. That's it. It can do it as many times
;; you call it to. It doesn't return anything (except whatever it is that a go-block
;; returns). But it does what you tell it to, when you tell it to.

;; The important thing about a transactor is that you can call it from other Stored Functions.
;; A Transactor is your ticket to the outside world. With a simple
;; (transact! :your-transactor arguments), you can send text-messages, emails, call other API's
;; or whatever you want!

;; This transactor we use the Twilio API to send ourselves text messages. I might have sent an
;; infinite loop of them while developing the transactor, but I did it so you don't have to!
;; Again it's just one s-expression per Stored Function.
;; For transactors we give you clj-http so you can contact the outside world, and our time
;; library tick, for obvious reasons.

;; Our transactor that we use to text ourselves:
;; client is the included clj-http.client
;; The following are provided in the transactors namespace:
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
;; name:
"demo-monitor"
;; next-run:
(t/new-duration 1 :hours)
;; init:
{:last-status nil}
;; func:
(fn [{:keys [last-status]}]
  (let [event-data (-> (mq/with-collection user-db "demo-app"
                         (mq/sort {:app-started -1})
                         (mq/limit 1))
                       last
                       :events
                       first)
        last-event (first event-data)
        last-time (second event-data)
        time-elapsed (t/between last-time (t/now))
        return-value (fn [status number-of time-unit]
                       {:last-status status
                        :next-run (t/truncate
                                   (t/+ (t/now)
                                        (t/new-duration
                                         number-of time-unit))
                                   time-unit)})
        waiting-for (fn [number-of time-unit]
                      (t/>= time-elapsed
                            (t/new-duration number-of time-unit)))
        closed-handlers {:successfully-ended
                         (fn []
                           (return-value :successfully-ended 1 :hours))
                         :ended-in-failure
                         (fn []
                           (do
                             (transact! :text event-data)
                             (return-value :ended-in-failure 1 :hours)))}]
    (if-let [closed-handler (last-event closed-handlers)]
      (closed-handler)
      (if (and (waiting-for 2 :minutes)
               (not= :no-response last-status))
        (if-not (waiting-for 3 :minutes)
          (return-value :waiting 1 :minutes)
          (if-not (waiting-for 4 :minutes)
            (return-value :still-waiting 1 :minutes)
            (do
              (transact! :text
                         (str "demo-app has not responded for "
                              (t/minutes time-elapsed)
                              " minutes!\n"
                              "Last status: " last-event))
              (return-value :no-response 1 :hours)
              (return-value last-event 2 :minutes)))))

;; The Transformer:
;; So far I haven't written the code for Transformers yet so...
;; Transformers TODO in disguise!!!

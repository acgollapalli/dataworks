;; Hi, This is the Demo App!

;; Today we're building a generic monitoring application that
;; will let you:
;;   Collect logs from an arbitrary number of apps via REST API,
;;   Track whether they it has started and is running.
;;   Notify yourself if there's a problem,
;;     or send you an hourly OK test.

;; We're using Dataworks to do this, as it's the Dataworks Demo
;; App, after all!

;; What do we assume you know?
;;   Clojure. If not, read Clojure for the Brave and True
;;     (braveclojure.com) to awaken your inner clojure viking.
;;     It's free and will teach you the basics, which is all you
;;     need for this tutorial.
;;   What a REST API is, and a bit about web tech. YouTube is
;;     the place to go to learn more about this.
;;   What Kafka is. Again YouTube is sufficient. You just need
;;     the basics.

;; Now, what is Dataworks, and why am I here?
;; Dataworks is an web app that lets you build web apps, and
;; make changes as your apps run, so you don't have to redeploy
;; every time you make a minor change. Dataworks stores your
;; code, written in clojure, in a database, and runs it and any
;; changes as you make them. In practical terms: Need a new
;; endpoint? Need to add some functionality or fix a bug? Just
;; change those things while the rest of the app runs happily
;; oblivious of the changes that are taking place. As for why
;; you're here? Why are any of us here? IDK, but we aren't here
;; to discuss existential questions.

;; Now, the first thing to understand about Dataworks is that it
;; works on the basis of Stored Functions. What the hell is a
;; Stored Function?

;; A Stored Function is a Clojure expression that is stored in a
;; database and evaluated at runtime, and reevaluated every time
;; it changes. It is usually a function, or a map of functions,
;; or something that evaluates to a function, depending on what
;; we're talking about. Basically, we treat Code as Data and
;; store it in a database.
;;
;; Now, due to the requirements of programmers writing things
;; that are actually useful, these usually aren't pure
;; functions, however a well architected Dataworks Stored
;; Function will rely on only a single store of app state, the
;; Database.

;; There are 4 types of Stored Functions in Dataworks:
;;   The Collector
;;   The Transactor
;;   The Internal
;;   The Transformer

;; Of the four of them, only the Transformer is actually a pure
;; function, and even then, that's only if you don't try to make
;; it an impure function by calling a transactor or something.

;; We'll go through each of the four by providing an actual
;; meaningful example, as well as some exposition on what each
;; one is meant to do, and not to do. Though again, this is
;; clojure. You're allowed more flexibility than with any other
;; language, or application framework, or db/stored-procedure
;; thing. With great power comes great responsibility, and how
;; you architect your Dataworks app is ultimately up to you.

;; The Collector:
;; The Collector is arguably the most fundamental of the four
;; types of Stored Functions in Dataworks. Not in any kind of
;; fancy shmancy programming sense. This isn't a Rich Hickey
;; talk, and I'm not nearly so sophisticated. Dataworks was
;; conceived as a way to create a new API endpoint for your
;; application without taking down your entire app and
;; redeoploying it. To do that, I came up with the collector.

;; So what the hell is a Collector? A Collector is an API
;; endpoint. It's a web address, along with some functions that
;; say what happens when someone uses that web address. When you
;; add a collector you add an endpoint to your API while your
;; API is running, and when you change a collector, you change
;; what that API endpoint does while the API is running. Pretty
;; neat huh? We use a library called Yada to tell Dataworks what
;; to do when a request is made of our endpoint. A collector is
;; a Yada Resource. And a Yada Resource is a map of functions
;; and other info about things like authentication, and a brief
;; description.

;; Here's the thing, Right you're only allowed to evaluate 1
;; s-expression. I'd be tempted to prtend it's some sort of
;; security thing, but really it's just a limitation we haven't
;; gotten around yet. However, because clojure is clojure, you
;; can create functions inside of functions, and even name them
;; if you do it inside a let macro (if you didn't know let is a
;; macro in clojure).

;; Every stored function lives inside a namespace, and there's a
;; namespace for each type of stored function. This doesn't mean
;; you can call one stored function from another, except for
;; transactors via the "transact!" function and transformers via
;; the "transformers" macro. The stored functions are evaluated
;; as anonymous functions, and other means have to be used to
;; refer to them.

;; Collectors have access to your database (we use a database
;; called Crux, and I'll explain why in a bit) a time library
;; called tick to tell time, a safe read-string so you can
;; accept serialized edn params, and yada, so you can make yada
;; resources easily

;; Our Demo Collector:
;; This lives in the collectors namespace in which the following
;; are provided:
(ns dataworks.collectors
  (:require
   [dataworks.common :refer :all]
   [dataworks.db.user-db :refer [user-db
                                 submit-tx
                                 query
                                 entity]]
   [dataworks.time-utils :refer [consume-time]]
   [dataworks.transactor :refer [transact!]]
   [crux.api :as crux]
   [mount.core :refer [defstate] :as mount]
   [tick.alpha.api :as tick]
   [yada.yada :refer [as-resource] :as yada]))


;; This is in addition to clojure.core, as well as everything
;; that ships with clojure.core. Don't call eval on expressions
;; from untrusted sources! read-string is safe because we've
;; inserted a safe read-string which does not eval what it
;; reads, and you can require other libraries if you put them on
;; the class path, and have the require statement in your code.

;; I'll say it again: DON"T CALL EVAL ON UNTRUSTED CODE!!! And
;; if you do call eval on untrusted code, after we told you not
;; to then you deserve to get owned.

;; Our collector (the json field names are commented.)
;; Path:
"demo-app"
;;Name:
"demo-app"
;;Resource:
{:id :demo-app
 :description "captures data from apps"
 :methods
 {:post
  {:consumes #{"application/json"}
   :produces "application/json"
   :response
   (fn [ctx]
     (let [{:keys [app event details next-event]} (:body ctx)
           id (keyword app "log")
           now (tick/now)
           pr-event {:crux.db/id id
                     :log/event event
                     :log/time now
                     :app/name (keyword app)
                     :app/alert (keyword app "alert")}
           details? #(if details
                       (assoc % :log/details details)
                       %)
           top-of-the-hour (consume-time now :hours)
           next-event? #(let [nxt (consume-time
                                   now
                                   next-event)]
                          (assoc %
                                 :alert/next-event
                                 (if nxt
                                   nxt
                                   top-of-the-hour)))
           tx-event (-> pr-event
                        details?
                        next-event?)
           alert-event {:crux.db/id (keyword app "alert")
                        :app/name (keyword app)
                        :alert/timestamp now}]
       (submit-tx [[:crux.tx/put tx-event]
                   (when-not (= :never
                                (:alert/next-event tx-event))
                     [:crux.tx/put alert-event
                      (tick/inst (:alert/next-event tx-event))])])
       (entity id)))}}}

;; A brief explanation of the various convenience functions used
;; in the above:

;; consume-time:
;;   Produces: java.time.Instant, :never, or nil, or
;;             a sequence/lazy-sequence of the same.
;;
;;   Accepts the following as time-literals, java types,
;;   a string representation which tick/parse can turn
;;   into one of the acceptable types, a sequence of any
;;   of the above or either of the previous serialized
;;   (stringified) by clojure.core/pr-str :
;;
;;   Consumes: java.time.Instant (#time/instant)
;;             java.util.Date (#inst)
;;             java.time.LocalDate (#time/date)
;;             java.time.Duration (#time/duration)
;;                 (returns as now + duration)
;;             java.time.Period (#time/period)
;;                 (returns as today's date + period)
;;             java.time.DayOfWeek (#time/day-of-week)
;;                 (returns as next day-of-week)
;;             int (number of milliseconds,
;;                  returns as now + milliseconds)
;;             keyword indicating a duration or period
;;                 (ex: :millis, :seconds, :minutes :hours,
;;                  :weeks, :months, :years)
;;             keyword indicating never (:never)
;;
;;  WARNING: Currently bad inputs don't produce exceptions, but
;;           just return nil. This is because I haven't figured
;;           out how to handle typed polymorphism in Clojure yet.

;; submit-tx:
;;   An aliased form of crux/submit-tx.
;;   Effectively it's #(crux/submit-tx db %)
;;   Added because I got tired of typing db.
;;   See crux documentation for more info.

;; query:
;;   An aliased form of crux/q.
;;   Effectively it's #(crux/q (crux/db db) %). Although it also
;;   accepts optional valid-time and transaction-time arguments
;;   for more intensive queries.
;;   Arities:
;;     [query],
;;     [valid-time query],
;;     [valid-time transaction-time query]
;;   Added because I got tired of typing (crux/db db).


;; The Transactor:
;; The Transactor does a thing when it's called. That's it. It
;; can do it as many times as you call it to. It doesn't return
;; anything (except whatever it is that a go-block returns). But
;; it does what you tell it to, when you tell it to. It's what
;; our dads all wish we'd have been.

;; The important thing about a transactor is that you can call
;; it from other Stored Functions. A Transactor is your ticket
;; to the outside world. With a simple (transact!
;; :your-transactor arguments) you can send text-messages,
;; emails, call other API's or whatever you want!

;; For his transactor we use the Twilio API to send ourselves
;; text messages. I might have sent an infinite loop of them
;; while developing the transactor, but I did it so you don't
;; have to! Again it's just one s-expression per Stored
;; Function. For transactors we give you clj-http so you can
;; contact the outside world, cheshire because, clj-http likes
;; that, and our time library tick, for obvious reasons.

;; Our transactor that we use to text ourselves: client is the
;; included clj-http.client The following are provided in the
;; transactors namespace: [cheshire.core :as cheshire]
;; [clj-http.client :as client] [tick.alpha.api :as tick]

;; Our transactor:
;; name:
"text"
;; function:
(fn [body phone-number]
  (let [twilio-sid "YOUR TWILIO SID"
        twilio-token "YOUR TWILIO TOKEN"
        hello-body {:Body (str body)
                    :From "YOUR TWILIO NUMBER"
                    :To phone-number}]
    (client/post
     (str "https://api.twilio.com/2010-04-01/Accounts/"
          twilio-sid
          "/Messages.json")
     {:basic-auth [twilio-sid twilio-token]
      :form-params hello-body})))

;; The Internal The Internal does something on a timer, or
;; whenever it thinks is necessary. It's sort of like a
;; scheduled task that can reschedule itself when it likes. This
;; one's a bit tricky, so I outlined what we're trying to do.

;; Our initial internal function:
;;   What it assumes: TODO
;;   What it does: TODO

;; Now, there's a couple of things to pay attention to here, as
;; they're important, and will make life easier. The first is
;; that this function is hairy. So how do we make it less hairy?
;; By dividing it into a bunch of smaller functions! But we only
;; have one expression to work with, so what do we do? We use
;; let! We use let to define a bunch of smaller functions, and
;; maps of functions, and values to make our life easier. Just
;; keep in mind it looks hairy because you're looking at 6 or so
;; functions, not just one.

;; This lives in the internals namespace, for which the
;; following are provided:
(ns dataworks.internals
  (:require
   [dataworks.db.user-db :refer [user-db
                                 submit-tx
                                 query
                                 entity]]
   [dataworks.common :refer [->? read-string
                             stringify-keyword
                             if-vector-first
                             if-vector-conj]]
   [cheshire.core :as cheshire]
   [crux.api :as crux]
   [dataworks.transactor :refer [transact!]]
   [tick.alpha.api :as tick]))

;; For convenience, user-db can be called as db.

;; Our Internal Query to find Apps that need to be monitored:
(crux/q
 (crux/db db)
 '{:find [app timestamp status next-event]
   :where [[e :app/name app]
           [e :log/event status]
           [e :alert/next-event next-event]
           [e :app/alert alert]
           [alert :alert/timestamp timestamp]]})
;; Shorthand version:
(query
 '{:find [app timestamp status next-event]
   :where [[e :app/name app]
           [e :log/event status]
           [e :alert/next-event next-event]
           [e :app/alert alert]
           [alert :alert/timestamp timestamp]]})

;; name:
"demo-app"
;; Yes, we're naming it the same as the collector. It's actual
;; name is :internal/demo-app because everything is namespaced,
;; so this is fine.

;; next-run:
#time/duration "PT1H"
;;You get this by running:
(tick/new-duration 1 :minutes) ;; Don't forget to run pr-string
                               ;; on it before sending!
;; initial-value:
{:events-checked-once {}}

;; function:
(fn
  [{:keys [events-checked-once]}]
  (let [events-to-check
        (query
         '{:find [app
                  timestamp
                  status
                  next-event]
           :where [[e :app/name app]
                   [e :log/event status]
                   [e :alert/next-event next-event]
                   [e :app/alert alert]
                   [alert :alert/timestamp timestamp]]})
        now (tick/now)
        waiting-since #(t/minutes (t/between % now))
        waiting-too-long?
        (fn [[app timestamp status next-event]]
          (if-let [previous-timestamp (get events-checked-once
                                           app)]
            (= timestamp previous-timestamp)))
        wait-a-bit-longer? #(not waiting-too-long? %)
        it's-been-too-long (filter waiting-too-long)
        let's-wait-a-bit-longer (filter wait-a-bit-longer)
        text-the-dev (fn [[app timestamp status next-event]]
                       (transact!
                        :text
                        (str app " has not checked in.\n\n"
                             "Expected information "
                             (waiting-since next-event)
                             " minutes ago.\n"
                             "Have not heard from " app
                             "for " (waiting-since timestamp))))
        check-in-a-minute (fn [event-map
                               [app timestamp status
                                next-event]]
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

;; Crux, Kafka, and Streams:
;; Now, I mentioned earlier that we use a funky little beastie
;; of a DB called Crux. Now why in the Hell would we want to use
;; something like that? It's new, and unknown, and everybody
;; else uses Mongo, right? Well a) they said the the same damn
;; thing about Mongo, and b) I don't know about you, but a
;; heckuvalotta people don't get to use anything so new and
;; shiny as Mongo; they use 15 year old SQL servers, and
;; software originally implemented in PowerBuilder, and have to
;; use Visual Studio, instead of Emacs, and deploy on a crusty
;; old windows server, have the DBA run their damned SQL queries
;; for them, and where was I going with this? Oh yeah: Crux, why
;; that? Well there's a few good reasons, the first being that
;; it stores EDN natively, and that means it stores lists of
;; symbols, and keywords, and maps, that clojure can evaluate
;; and turn into functions, without having to serialize or
;; deserialize those lists. The second reason being that you can
;; declare something to be valid in the future instead of now,
;; or in the past instead of right now. It lets you create a
;; timeline of future state and then update that timeline as
;; things change over time. The third reason is because it runs
;; on Kafka, and that opens up a whole Texas-width highway of
;; horizontal scalability, which brings us to our next question
;; of:

;; Why Kafka?
;; Dataworks is built on Kafka, and you're probably wondering
;; why. The answer is that Kafka makes it really, really easy to
;; horizontally scale. Because Dataworks is built on Kafka, we
;; can use Kafka to coordinate our different nodes, running
;; things transactionally, without having to worry about who's
;; doing what. It let's us say "Oh I need a bit more
;; availability? I'll just fire up another Dataworks node."
;; Kafka natively runs in clusters, and Crux natively runs in
;; clusters and Dataworks natively runs in clusters. You can run
;; a cluster of 1 for each of those, but it's distributed by
;; default which means it's fault tolerant by default and
;; scalable by default.

;; But there's another reason, a deep, dark, scary reason...
;; Sometimes your Dataworks cluster might have to interface with
;; *dun dun dun!!!* other apps! It's not like Dataworks was made
;; for this or anything (it was), but it gets worse. Sometimes
;; these apps won't want to be sending or polling. Sometimes,
;; they'll want to receive data in a stream. And sometimes...
;; they'll even want both!!! Earlier we said that dataworks is a
;; web app that lets you create web apps, and that's true. But
;; dataworks is also a stream processor that let's you build
;; stream processors. Pretty nifty ain't it, swifty?

;; So enough blathering on about all these technologies that
;; some random person on the internet (me) says are cool. Let's
;; write some code and show you the actual advantages.

;; What we're trying to do:
;;   We want to take in a document,
;;   Validate that document,
;;   If it's invalid, we send something back saying so,
;;     otherwise,
;;   Put that document in a database and send something back
;;     saying we did so.
;;   Put that same document on a kafka topic,
;;     and not worry about it for a while.
;;   Receive a confirmation or denial,
;;     or no news at all on that document, and
;;   Send a status report to whoever sent us the document.

;; Pretty simple right? Given what we've done so far?

;; Now, this might seem contrived, but it's a real life
;; situation that was (is) one of the first uses of dataworks in
;; production. Why do this? For a couple reasons:
;;   1) The service sending you the documents expects you to
;;      respond quickly.
;;   2) The service which receives the documents via kafka is
;;      slow. Presumably because it's interfacing with a legacy
;;      system and not because you wrote bad code. (If someone
;;      else wrote the other service then obviously it's their
;;      fault though, amirite?)
;; Because of that, using Kafka as a queue for the slower legacy
;; system actually makes a lot of sense.

;; This time let's just write the response function first, and
;; then we'll look at the whole collector.
(let
    [any-failures? (filter
                    #(= :failure
                        (:status %)))
     map-valid (fn [params validator & key]
                 (let [param (if key
                               (get-in params
                                       (into [] key))
                               params)
                       failures (into [] validator param)]
                   (if (empty? failures)
                     params (first failures))))
     volume>0? (fn [{:keys [volume], :as params}]
                 (if (and (int? volume)
                          (< 0 volume))
                   params
                   {:status :failure,
                    :message :volume-must-be-int>0,
                    :details (str "volume: " volume)}))
     valid-pickup-cmds? (comp
                         (map #(->? %
                                    (missing-field?
                                     :volume)
                                    volume>0?
                                    (blank-field? :supplier
                                                  :commodity)))
                         any-failures?)
     valid-pickups? (comp
                     (map #(->? %
                                (blank-field? :shipper)
                                (map-valid valid-pickup-cmds?
                                           :commodities)))
                     any-failures?)
     drop-cmd-status (comp
                      (map #(->? %
                                 (missing-field? :volume)
                                 volume>0?
                                 (blank-field? :commodity)))
                      any-failures?)
     valid-drops? (comp
                   (map #(->? %
                              (blank-field? :site)
                              (map-valid valid-drop-cmds?
                                         :commodities)))
                   any-failures?)
     status (fn [order]
              (->?
               order
               (blank-field? :billTo)
               (missing-field? :pickups :drops)
               (map-valid valid-pickups? :pickups)
               (map-valid valid-drops? :drops)))
     db-fy (fn [order uuid]
             (-> order
                 (clojure.set/rename-keys
                  {:billTo :order/bill-to,
                   :pickups :order/pickups,
                   :drops :order/drops,
                   :shipper :pickup/shipper,
                   :site :drop/site,
                   :commodity :commodity/type,
                   :volume :commodity/volume,
                   :supplier :commodity/supplier})
                 (assoc :crux.db/id
                        (keyword "order" (str uuid)))))]
  (fn [ctx]
    (let
        [order (:body ctx)
         valid-order? (status order)
         valid-order (if (and valid-order?
                              (not= :failure
                                    (:status valid-order?)))
                       valid-order?)
         uuid (if valid-order
                (java.util.UUID/randomUUID))]
      (if valid-order
        (do
          (submit-tx
           [[:crux.tx/cas (db-fy valid-order uuid) nil]
            [:crux.tx/put
             {:crux.db/id (keyword "order-watcher" (str uuid)),
              :watcher/watched (keyword "order" (str uuid))}
             (tick/inst (consume-time
                         #time/duration "PT5M"))]])
          (produce! "tentative-orders"
                    (assoc valid-order
                           :dataworks-id (str uuid)))
          {:status :success,
           :message :tentative-order-added!,
           :details valid-order})
        status))))

;; Well that looks friggin ridiculous doesn't it?!
;; We had to define fourteen different functions. Well I wrote a
;; handy dandy little macro to shorten that, and included it in
;; all the stored-function namespaces so it's available to use:
(defmacro ->let [& forms]
  (loop [lets []
         forms forms]
    (if (< 1 (count forms))
      (let [form (first forms)
            exp-form (macroexpand form)]
        (if (= (first exp-form)
               'def)
          (recur (apply conj lets (rest exp-form))
                 (next forms))
          (recur lets (next forms))))
      `(let ~lets ~(last forms)))))
;; Now, let's try that again:
(->let
 (def any-failures?
   (filter #(= :failure (:status %))))

 (defn map-valid
   [params validator & key]
   (let [param (if key
                   (get-in params (into [] key))
                  params)
         failures (into [] validator param)]
     (if (empty? failures)
       params
       (first failures))))

 (defn volume>0?
   [{:keys [volume] :as params}]
   (if (and (int? volume)
            (< 0 volume))
     params
     {:status :failure
      :message :volume-must-be-int>0
      :details (str "volume: "
                    volume)}))

 (def valid-pickup-cmds?
   (comp (map #(->? %
              (missing-field? :volume)
              volume>0?
              (blank-field? :supplier
                            :commodity)))
         any-failures?))

 (def valid-pickups?
   (comp
    (map #(->? %
               (blank-field? :shipper)
                    (map-valid
                     valid-pickup-cmds?
                     :commodities)))
         any-failures?))
 (def drop-cmd-status
   (comp
    (map #(->? %
              (missing-field? :volume)
              volume>0?
              (blank-field? :commodity)))
         any-failures?))


 (def valid-drops?
   (comp
         (map #(->? %
                    (blank-field? :site)
                    (map-valid
                     valid-drop-cmds?
                     :commodities)))
          any-failures?))

 (defn status
   [order]
   (->? order
        (blank-field? :billTo)
        (missing-field? :pickups :drops)
        (map-valid valid-pickups? :pickups)
        (map-valid valid-drops? :drops)))

 (defn db-fy
   [order uuid]
   (-> order
       (clojure.set/rename-keys
        {:billTo :order/bill-to
         :pickups :order/pickups
         :drops :order/drops
         :shipper :pickup/shipper
         :site :drop/site
         :commodity :commodity/type
         :volume :commodity/volume
         :supplier :commodity/supplier})
       (assoc :crux.db/id
              (keyword "order" (str uuid)))))

 (fn [ctx]
   (let [order (:body ctx)
         valid-order? (status order)
         valid-order (if (and valid-order?
                              (not= :failure
                                    (:status valid-order?)))
                       valid-order?)
         uuid (if valid-order
                (java.util.UUID/randomUUID))]
     (if valid-order
       (do
         (submit-tx [[:crux.tx/cas
                      (db-fy valid-order uuid) nil]
                     [:crux.tx/put
                      {:crux.db/id (keyword "order-watcher"
                                            (str uuid))
                       :watcher/watched (keyword "order"
                                                 (str uuid))}
                      (tick/inst
                       (consume-time #time/duration "PT5M"))]])
         (produce! "tentative-orders" (assoc valid-order
                                             :dataworks-id
                                             (str uuid)))
         {:status :success
          :message :tentative-order-added!
          :details valid-order})
       status))))

;; Now, that's still a doozy, but at least it looks like a bunch
;; of functions instead of a single function. It looks
;; manageable, like something you could write at the repl, which
;; is the goal. I might make that any-failures? transducer
;; available in dataworks.common, and hence in all the stored
;; function namespaces as well, if it seems useful. But did it
;; work?

;; Let's macroexpand it back out:
(let*
    [any-failures? (filter
                    (fn* [p1__66499#]
                         (= :failure
                            (:status p1__66499#))))
     map-valid (clojure.core/fn
                 ([params validator & key]
                  (let [param (if key
                                (get-in params
                                        (into [] key))
                                params)
                        failures (into [] validator param)]
                    (if (empty? failures)
                      params (first failures)))))
     volume>0? (clojure.core/fn
                 ([{:keys [volume], :as params}]
                  (if (and (int? volume)
                           (< 0 volume))
                    params
                    {:status :failure,
                     :message :volume-must-be-int>0,
                     :details (str "volume: " volume)})))
     valid-pickup-cmds? (comp
                         (map
                          (fn* [p1__66500#]
                               (->? p1__66500#
                                    (missing-field?
                                     :volume)
                                    volume>0?
                                    (blank-field? :supplier
                                                  :commodity))))
                         any-failures?)
     valid-pickups? (comp
                     (map
                      (fn* [p1__66501#]
                           (->? p1__66501#
                                (blank-field? :shipper)
                                (map-valid valid-pickup-cmds?
                                           :commodities))))
                     any-failures?)
     drop-cmd-status (comp
                      (map
                       (fn* [p1__66502#]
                            (->? p1__66502#
                                 (missing-field? :volume)
                                 volume>0?
                                 (blank-field? :commodity))))
                      any-failures?)
     valid-drops? (comp
                   (map
                    (fn* [p1__66503#]
                         (->? p1__66503#
                              (blank-field? :site)
                              (map-valid valid-drop-cmds?
                                         :commodities))))
                   any-failures?)
     status (clojure.core/fn
              ([order]
               (->?
                order
                (blank-field? :billTo)
                (missing-field? :pickups :drops)
                (map-valid valid-pickups? :pickups)
                (map-valid valid-drops? :drops))))
     db-fy (clojure.core/fn
             ([order uuid]
              (-> order
                  (clojure.set/rename-keys
                   {:billTo :order/bill-to,
                    :pickups :order/pickups,
                    :drops :order/drops,
                    :shipper :pickup/shipper,
                    :site :drop/site,
                    :commodity :commodity/type,
                    :volume :commodity/volume,
                    :supplier :commodity/supplier})
                  (assoc :crux.db/id
                         (keyword "order" (str uuid))))))]
  (fn [order]
    (let
        [valid-order? (status order)
         valid-order (if (and valid-order?
                              (not= :failure
                                    (:status valid-order?)))
                       valid-order?)
         uuid (if valid-order
                (java.util.UUID/randomUUID))]
      (if valid-order
        (do
          (submit-tx
           [[:crux.tx/cas (db-fy valid-order uuid) nil]
            [:crux.tx/put
             {:crux.db/id (keyword "order-watcher" (str uuid)),
              :watcher/watched (keyword "order" (str uuid))}
             (tick/inst (consume-time
                         (. java.time.Duration parse
                            "PT5M")))]])
          (produce! "tentative-orders"
                    (assoc valid-order
                           :dataworks-id (str uuid)))
          {:status :success,
           :message :tentative-order-added!,
           :details valid-order})
        status))))

;; Looks about right to me!

;; Most of the data transformation that we right now do through
;; functions in a let block will eventually be done through
;; transformers, so they're reusable, composable, and make the
;; functions you build simpler, and less massive. I don't want
;; to compromise the resuabilty and composability of clojure
;; functions if I can help it, as the whole thing is built on
;; that.

;; A couple brief words on some of the functions that have been
;; made available to you from the dataworks.common namespace:

;; ->?
;;   The Validation Macro:
;;   A utility function for validation of data and transactions
;;   Like the threading macro (->), except after each function,
;;   it looks to see whether the function returned a map with
;;   the key :status and the value :faiure. If the function did
;;   return such a map, then we return that same map, other wise
;;   we pass the result to the next function like the threading
;;   macro (->)."
;; ->let
;;   Args: [& forms]
;;   Input:
          (->let
            (defn plus-2
              [a]
              (+ 2 a))
            (fn [b]
              (/ (plus-2 b) 3)))
;;
;;  Output (macroexpansion):
          (let
            [plus-2 (fn [a]
                      (+ 2 a))]
            (fn [b] (/ (plus-2 b) 3)))
;;  The above actually returns a function, Because that's what
;;  the macroexpanded form evaluates to. Essentially, it takes
;;  every expression but the last that macroexpands to (def name
;;  expression) and makes it so that the name and expression are
;;  part of a let expression:
            (let [name expression
                  ...
                  other-name other-expression]
              last-expression)

;; blank-field?
;;   Args: [m & fields]
;;   Checks to see whether the specified parameters are blank
;;   or nil. m must be a map. fields must be keywords, or
;;   vectors of keywords for nested values. Values must be
;;   strings

;; missing-field?
;;   Args: [m & fields]
;;   Checks to see whether the specified parameters are nil. m
;;   must be a map. fields must be keywords or vectors of
;;   keywords for nested values. Values may be anything.

;; Now we're trying to build a collector and that means a yada
;; resource so we need one more expression after the function,
;; that expression being our yada resource itself.
(->let
 (def any-failures?
   (filter #(= :failure (:status %))))

 (defn map-valid
   [params validator & key]
   (let [param (if key
                   (get-in params (into [] key))
                  params)
         failures (into [] validator param)]
     (if (empty? failures)
       params
       (first failures))))

 (defn volume>0?
   [{:keys [volume] :as params}]
   (if (and (int? volume)
            (< 0 volume))
     params
     {:status :failure
      :message :volume-must-be-int>0
      :details (str "volume: "
                    volume)}))

 (def valid-pickup-cmds?
   (comp (map #(->? %
              (missing-field? :volume)
              volume>0?
              (blank-field? :supplier
                            :commodity)))
         any-failures?))

 (def valid-pickups?
   (comp
    (map #(->? %
               (blank-field? :shipper)
                    (map-valid
                     valid-pickup-cmds?
                     :commodities)))
         any-failures?))
 (def valid-drop-cmds?
   (comp
    (map #(->? %
              (missing-field? :volume)
              volume>0?
              (blank-field? :commodity)))
         any-failures?))

 (def valid-drops?
   (comp
         (map #(->? %
                    (blank-field? :site)
                    (map-valid
                     valid-drop-cmds?
                     :commodities)))
          any-failures?))

 (defn status
   [order]
   (->? order
        (blank-field? :billTo)
        (missing-field? :pickups :drops)
        (map-valid valid-pickups? :pickups)
        (map-valid valid-drops? :drops)))

 (defn db-fy
   [order id-string]
   (-> order
       (clojure.set/rename-keys
        {:billTo :order/bill-to
         :pickups :order/pickups
         :drops :order/drops
         :shipper :pickup/shipper
         :site :drop/site
         :commodity :commodity/type
         :volume :commodity/volume
         :supplier :commodity/supplier})
       (assoc :crux.db/id
              (keyword "order" id-string))))

 (defn create-order
   [ctx]
   (let [order (:body ctx)
         valid-order? (status order)
         valid-order (if (and valid-order?
                              (not= :failure
                                    (:status valid-order?)))
                       valid-order?)
         uuid (if valid-order
                (java.util.UUID/randomUUID))
         id-string (str "uuid." uuid)]
     (if valid-order
       (do
         (submit-tx [[:crux.tx/put
                      (db-fy valid-order id-string)]
                     [:crux.tx/put
                      {:crux.db/id (keyword "order-watcher"
                                            id-string)
                       :watcher/watched (keyword "order"
                                                 id-string)}
                      (tick/inst
                       (consume-time #time/duration "PT5M"))]])
         (produce! "tentative-orders"
                   (assoc valid-order
                          :dataworks-id
                          id-string)
                   :json)
         {:status :success
          :message :tentative-order-added!
          :details valid-order})
       valid-order?)))

 {:id :create-order
 :description "Validates and creates an order"
 :methods
 {:post
  {:consumes #{"application/json"}
   :produces "application/json"
   :response create-order}}})

;; Huh, well that was easy. After all the trouble it took to
;; create the function, actually turning it into a resource was
;; simple.


# Table of Contents

1.  [Introduction](#org428047c)
2.  [Now, what is Dataworks, and why am I here?](#orgc4df826)
3.  [The Collector](#org3f2e728)
4.  [The Transactor](#org2ba9088)
5.  [The Internal](#orgaf0435f)
6.  [The Transformer](#org0d7f780)
7.  [Naming things.](#org8f2e275)



<a id="org428047c"></a>

# Introduction

Hi this is the Demo App. It&rsquo;s also the documentation.

Today we&rsquo;re building a generic monitoring application that will let you:

1.  Collect logs from an arbitrary number of apps via REST API,
2.  Track whether they it has started and is running,
3.  Notify yourself if there&rsquo;s a problem, or send you an hourly OK test.

We&rsquo;re using Dataworks to do this, as it&rsquo;s the Dataworks Demo App, after all!

What do we assume you know?

1.  Clojure. If not, read Clojure for the Brave and True (braveclojure.com) to awaken your inner clojure viking. It&rsquo;s free and will teach you the basics, which is all you need for this tutorial.
2.  What a REST API is, and a bit about web tech. YouTube is the place to go to learn more about this.
3.  What Kafka is. Again YouTube is sufficient. You just need the basics. It doesn&rsquo;t come up in part 1, but it does in part 2.


<a id="orgc4df826"></a>

# Now, what is Dataworks, and why am I here?

Dataworks is an web app that lets you build web apps, and make changes as your apps run, so you don&rsquo;t have to redeploy every time you make a minor change. Dataworks stores your code, written in clojure, in a database, and runs it and any changes as you make them. In practical terms: Need a new endpoint? Need to add some functionality or fix a bug? Just change those things while the rest of the app runs happily oblivious of the changes that are taking place. As for why you&rsquo;re here? Why are any of us here? IDK, but we aren&rsquo;t here to discuss existential questions.

Now, the first thing to understand about Dataworks is that it works on the basis of Stored Functions. What the hell is a Stored Function?

A Stored Function is a Clojure expression that is stored in a database and evaluated at runtime, and reevaluated every time it changes. It is usually a function, or a map of functions, or something that evaluates to a function, depending on what we&rsquo;re talking about. Basically, we treat Code as Data and store it in a database.

Now, due to the requirements of programmers writing things that are actually useful, these usually aren&rsquo;t pure functions, however a well architected Dataworks Stored Function will rely on only a single store of app state, the Database.

There are 4 types of Stored Functions in Dataworks:

1.  The Collector
2.  The Transactor
3.  The Internal
4.  The Transformer

Of the four of them, only the Transformer is actually a pure function, and even then, that&rsquo;s only if you don&rsquo;t try to make it an impure function by calling a transactor or something.

We&rsquo;ll go through each of the four by providing an actual meaningful example, as well as some exposition on what each one is meant to do, and not to do. Though again, this is clojure. You&rsquo;re allowed more flexibility than with any other language, or application framework, or db/stored-procedure thing. With great power comes great responsibility, and how you architect your Dataworks app is ultimately up to you.


<a id="org3f2e728"></a>

# The Collector

The Collector is arguably the most fundamental of the four types of Stored Functions in Dataworks. Not in any kind of fancy shmancy programming sense. This isn&rsquo;t a Rich Hickey talk, and I&rsquo;m not nearly so sophisticated. Dataworks was conceived as a way to create a new API endpoint for your application without taking down your entire app and redeoploying it. To do that, I came up with the collector.

So what the hell is a Collector? A Collector is an API endpoint. It&rsquo;s a web address, along with some functions that say what happens when someone uses that web address. When you add a collector you add an endpoint to your API while your API is running, and when you change a collector, you change what that API endpoint does while the API is running. Pretty neat huh? We use a library called Yada to tell Dataworks what to do when a request is made of our endpoint. A collector is a Yada Resource. And a Yada Resource is a map of functions and other info about things like authentication, and a brief description.

Here&rsquo;s the thing, Right you&rsquo;re only allowed to evaluate 1 s-expression. I&rsquo;d be tempted to prtend it&rsquo;s some sort of security thing, but really it&rsquo;s just a limitation we haven&rsquo;t gotten around yet. However, because clojure is clojure, you can create functions inside of functions, and even name them if you do it inside a let macro (if you didn&rsquo;t know let is a macro in clojure). Every stored function lives inside a namespace, and there&rsquo;s a namespace for each type of stored function. This doesn&rsquo;t mean you can call one stored function from another, except for transactors via the &ldquo;transact!&rdquo; function and transformers via the &ldquo;transformers&rdquo; macro. The stored functions are evaluated as anonymous functions, and other means have to be used to refer to them.

Collectors have access to your database (we use a database called Crux, and I&rsquo;ll explain why in a bit) a time library called tick to tell time, a safe read-string so you can accept serialized edn params, the dataworks.common namespace so you can use some of the handy convenience and validation functions I&rsquo;ve written, and yada, so you can make yada resources easily.

Here&rsquo;s what the namespace looks like:

    (ns dataworks.collectors
      (:require
       [clojure.pprint :refer [pprint]]
       [dataworks.authentication :refer [authenticate
                                         authorize]]
       [dataworks.common :refer :all]
       [dataworks.db.user-db :refer [user-db
                                     submit-tx
                                     query
                                     entity]]
       [dataworks.stream-utils :refer [produce!]]
       [dataworks.time-utils :refer [consume-time]]
       [dataworks.transactor :refer [transact!]]
       [dataworks.transformer :refer [transformers]]
       [crux.api :as crux]
       [mount.core :refer [defstate] :as mount]
       [tick.alpha.api :as tick]
       [yada.yada :refer [as-resource] :as yada]
       [schema.core :refer [defschema] :as schema]))

This is in addition to clojure.core, as well as everything
that ships with clojure.core. Don&rsquo;t call eval on expressions
from untrusted sources! read-string is safe because we&rsquo;ve
inserted a safe read-string which does not eval what it
reads, and you can require other libraries if you put them on
the class path, and have the require statement in your code.

I&rsquo;ll say it again: DON&ldquo;T CALL EVAL ON UNTRUSTED CODE!!! And
if you do call eval on untrusted code, after we told you not
to then you deserve to get owned.

Our collector (the json field names are commented.)
Path: &ldquo;demo-app&rdquo;
Name: &ldquo;demo-app&rdquo;
Resource:

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
           (submit-tx (if (= :never
                              (:alert/next-event tx-event))
                         [[:crux.tx/put tx-event]]
                         [[:crux.tx/put tx-event]
                          [:crux.tx/put alert-event
                           (tick/inst (:alert/next-event tx-event))]]))
                tx-event))}}}

Well that&rsquo;s all nice and tidy, right? No? Well luckily I wrote a handy convencience function that makes it more like the kind of code you&rsquo;d write everyday at the repl.

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

Here&rsquo;s some example input:

    (->let
      (defn plus-2
        [a]
        (+ 2 a))
      (fn [b]
        (/ (plus-2 b) 3)))

And here&rsquo;s its output (macroexpanded):

    (let
      [plus-2 (fn [a]
                (+ 2 a))]
      (fn [b] (/ (plus-2 b) 3)))

The above actually returns a function, Because that&rsquo;s what the macroexpanded form evaluates to. Essentially, it takes every expression but the last that macroexpands to (def name expression) and makes it so that the name and expression are part of a let expression:

    (let [name-1 expression-1
          ...
          name-n expression-n]
      last-expression)

Any expressions (except the last) that don&rsquo;t macroexpand out to `(def something expression)` are simply thrown out.

Let&rsquo;s try that with our collector:

    (->let
     (def now      ;; we want only a single value for now
       (tick/now)) ;; so best define it once
    
     (def top-of-the-hour
       (consume-time now :hours))
    
     (defn pr-event
       [{:keys [app event details next-event]}]
       {:crux.db/id (keyword app "log")
        :log/event event
        :log/time now
        :app/name (keyword app)
        :app/alert (keyword app "alert")})
    
     (defn details?
       [pr-event details]
       (if details
         (assoc pr-event :log/details details)
         pr-event))
    
     (defn next-event?
       [pr-event next-event]
       (let [nxt (consume-time now next-event)]
         (assoc pr-event
                :alert/next-event
                (if nxt
                  nxt
                  top-of-the-hour))))
    
     (defn db-fy
       [{:keys [details next-event] :as params}]
       (-> params
           pr-event
           (details? details)
           (next-event? next-event)))
    
     (defn alert-fy [{:keys [app]}]
       {:crux.db/id (keyword app "alert")
        :app/name (keyword app)
        :alert/timestamp now})
    
     (defn handle-event
       [params]
       (let [tx (db-fy params)
             alert (alert-fy params)]
        (submit-tx
        (if (= :never
               (:alert/next-event tx))
          [[:crux.tx/put tx]]
          [[:crux.tx/put tx]
           [:crux.tx/put alert
            (tick/inst (:alert/next-event tx))]]))))
    
     {:id :demo-app
      :description "captures data from apps"
      :methods
      {:post
       {:consumes #{"application/json"}
        :produces "application/json"
        :response
        (fn [ctx]
          (handle-event (:body ctx)))}}})

Now, I know what you&rsquo;re about to say. Wait a minute, that does the same thing as the other one! And it&rsquo;s 20 lines longer! What gives? And the answer is that writing code this way makes so that it&rsquo;s easier to go function by function and make sure that you&rsquo;re getting the result you want from each function. Is it less concise? Yeah. Is it easier to write? Also yeah. And that&rsquo;s the point.

A brief explanation of the various convenience functions used in the above:

consume-time:
  Produces: java.time.Instant, :never, or nil, or
            a sequence/lazy-sequence of the same.

Accepts the following as time-literals, java types, a string representation which tick/parse can turn into one of the acceptable types, a (lazy?) sequence of any of the above or either of the previous serialized (stringified) by clojure.core/pr-str :

Consumes: java.time.Instant (#time/instant)
          java.util.Date (#inst)
          java.time.LocalDate (#time/date)
          java.time.Duration (#time/duration)
              (returns as now + duration)
          java.time.Period (#time/period)
              (returns as today&rsquo;s date + period)
          java.time.DayOfWeek (#time/day-of-week)
              (returns as next day-of-week)
          int (number of milliseconds,
               returns as now + milliseconds)
          keyword indicating a duration or period
              (ex: :millis, :seconds, :minutes :hours,
               :weeks, :months, :years)
          keyword indicating never (:never)

WARNING: Currently bad inputs don&rsquo;t produce exceptions, but
         just return nil. This is because I haven&rsquo;t figured
         out how to handle typed polymorphism in Clojure yet.

submit-tx:
  An aliased form of crux/submit-tx.
  Effectively it&rsquo;s `#(crux/submit-tx db %)`
  See crux documentation for more info.

query:
  An aliased form of crux/q.
  Effectively it&rsquo;s `#(crux/q (crux/db db) %)`. Although it also accepts optional valid-time and transaction-time arguments for more intensive queries.
  Arities:
    [query],
    [valid-time query],
    [valid-time transaction-time query]

So what have we done in 63 lines? We&rsquo;ve create an api endpoint that accepts event data from arbitrarily many different functions, and creates alerts for them if they don&rsquo;t respond in a certain amount of time. Now we need to figure out how to actually tell somebody about these alerts.


<a id="org2ba9088"></a>

# The Transactor

The Transactor does a thing when it&rsquo;s called. That&rsquo;s it. It can do it as many times as you call it to. It doesn&rsquo;t return anything (though I&rsquo;m working on that). But it does what you tell it to, when you tell it to. It&rsquo;s what our dads all wish we&rsquo;d have been.

The important thing about a transactor is that you can call it from other Stored Functions. A Transactor is your ticket to the outside world. With a simple (transact! :your-transactor arguments) you can send text-messages, emails, call other API&rsquo;s or whatever you want!

For his transactor we use the Twilio API to send ourselves text messages. I might have sent an infinite loop of them while developing the transactor, but I did it so you don&rsquo;t have to! Again it&rsquo;s just one s-expression per Stored Function. For transactors we give you clj-http so you can contact the outside world, cheshire because, clj-http likes that, and our time library tick, for obvious reasons.

Our transactor that we use to text ourselves: client is the included clj-http.client The following are provided in the transactors namespace: [cheshire.core :as cheshire] [clj-http.client :as client] [tick.alpha.api :as tick]

 Our transactor:
name: &ldquo;text&rdquo;
function:

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

Well, isn&rsquo;t that simple! You call it using the transact! function which is available in every stored-function namespace.

    (transact! :text "Hello World!")

Pretty nifty, right? You can do it as many times as you want, and it all happens asyncronously, so it won&rsquo;t block the rest of your function.


<a id="orgaf0435f"></a>

# The Internal

The Internal does something on a timer, or whenever it thinks is necessary. It&rsquo;s sort of like a scheduled task that can reschedule itself when it likes.

Our internal function:
  What it assumes: That our apps will either:
    a) that running our internal every minute gives us   sufficient fidelity to effectively monitor our apps
    b) that our collector that we built earlier will set up alerts for our internal to pick up
    TODO explain that db entries in crux don&rsquo;t actually exist until after their valid time, which is why alerts work.
  What it does:
    a) filter through the alerts that are currently valid
    b) filter for the ones it&rsquo;s seen previously (from the result returned previously) and send us a text about them.
    c) filter for the ones it hasn&rsquo;t seen previously and return them so that the next iteration can check on them again.

This lives in the internals namespace, for which the following are provided:

    (ns dataworks.internals
      (:require
       [dataworks.db.user-db :refer [user-db
                                     submit-tx
                                     query
                                     entity]]
       [dataworks.common :refer :all]
       [cheshire.core :as cheshire]
       [crux.api :as crux]
       [dataworks.transactor :refer [transact!]]
       [dataworks.transformer :refer [transformers]]
       [dataworks.stream-utils :refer [produce!
                                       consumer-instance
                                       consume!]]
       [tick.alpha.api :as tick]))

For convenience, user-db is aliased as db in this namespace.

Our Internal Query to find Apps that need to be monitored:

    (crux/q
     (crux/db db)
     '{:find [app timestamp status next-event]
       :where [[e :app/name app]
               [e :log/event status]
               [e :alert/next-event next-event]
               [e :app/alert alert]
               [alert :alert/timestamp timestamp]]})

Shorthand version:

    (query
     '{:find [app timestamp status next-event]
       :where [[e :app/name app]
               [e :log/event status]
               [e :alert/next-event next-event]
               [e :app/alert alert]
               [alert :alert/timestamp timestamp]]})

name: &ldquo;demo-app&rdquo;
Yes, we&rsquo;re naming it the same as the collector. It&rsquo;s actual name is :internal/demo-app because everything is namespaced, so this is fine.

initial-value:

    {:events-checked-once {}}

function:

    (->let
     (def now
       (tick/now))
    
     (def events-to-check   ;;remember this will be transformed
       (query               ;; via ->let and only exists within
        '{:find [app        ;; the scope of the ->let block
                 timestamp
                 status
                 next-event]
          :where [[e :app/name app]
                  [e :log/event status]
                  [e :alert/next-event next-event]
                  [e :app/alert alert]
                  [alert :alert/timestamp timestamp]]}))
    
     (defn waiting-since
       [t]
       (tick/minutes
        (tick/between t now)))
    
     (defn waiting-too-long?
       "If this seems weird to you, we're just writing a function that creates a function. We don't have the events-checked-once value until the final function, which why we're doing it this way."
       [events-checked-once]
       (fn [[app timestamp status next-event]]
         (if-let [previous-timestamp (get events-checked-once app)]
           (= timestamp previous-timestamp))))
    
     (defn check-in-a-minute
       [event-map [app timestamp status next-event]]
       (assoc event-map app timestamp))
    
     (defn text-the-dev
       [[app timestamp status next-event]]
       (transact! :text
                  (str app " has not checked in.\n\n"
                       "Expected information "
                       (waiting-since next-event)
                       " minutes ago.\n"
                       "Have not heard from " app
                       " for " (waiting-since timestamp) "minutes.")))
    
     (fn [{:keys [events-checked-once]}]
       (if-not (empty? events-to-check)
         (do
           (doseq
            [alert (filter
                    (waiting-too-long?
                     events-checked-once)
                    events-to-check)]
             (text-the-dev alert))
           (println (filter (complement
                             (waiting-too-long?
                              events-checked-once))
                            events-to-check))
           {:events-checked-once
            (reduce check-in-a-minute {}
                    (filter (complement
                             (waiting-too-long?
                              events-checked-once))
                            events-to-check))
            :next-run (tick/new-duration 1 :minutes)})
         {:events-checked-once {}
          :next-run (tick/new-duration 1 :minutes)})))


<a id="org0d7f780"></a>

# The Transformer

<del>So far I haven&rsquo;t written the code for Transformers yet so&#x2026; Transformers TODO in disguise!!!</del>

I got a bit too attached to that joke I&rsquo;m afraid, so it stays in. The transformer really is the fundamental unit of the dataworks platform. It&rsquo;s the only stored function that actually returns a value (or a function, or any valid clojure object really), and it&rsquo;s really where the power of dataworks lies. When you change one, everything that uses it get&rsquo;s changed too. You can reuse different functions if you create them as transformers. Your, collectors, transformers, and internals can all be built almost entirely as transformers and then have the relevant transformer be called by a barebones calling function. You can even namespace them (and you should).

I don&rsquo;t have an example yet, as this demo app that we&rsquo;ve been going through didn&rsquo;t really seem to need them (though later ones will), but I thought I should at least introduce it here.

Let&rsquo;s say we wanted to use our waiting-since? function in more than one stored function. We could turn it into a transformer to do so.

name: &ldquo;time-utils/waiting-since&rdquo;
function:

    (fn [t]
      (tick/minutes
       (tick/between t now)))

And then would call it like this:

    (transformers [time-utils/waiting-since]
     ...
     (time-utils/waiting-since my-time)
     ...)

That&rsquo;s it. The transformers block is available in every stored function namespace (including the transformers one) and it grabs the functions you want, and makes them available in the scope of the transformers block. The transformers block also puts everything in an implict ->let block, so you can have your defs and defns in that block with no trouble. As always, it&rsquo;s worthwhile to read the code for all these things. Dataworks is pretty small, so you shouldn&rsquo;t hesitate in that regard.


<a id="org8f2e275"></a>

# Naming things.

This is important, and probably should have come earlier in the story, but all your names should be easily converted to a valid keyword. Run (keyword your-name) on the name parameter that you send to dataworks, and if it doesn&rsquo;t look right to you, then use something that does. If you&rsquo;re using namespaced names, then you should realize that when you try to update the stored-function via api, you&rsquo;ll most likely need to replace the slash (*) with a period (.) in the web address. For instance time-utils/waiting-since becomes transformer/time-utils.waiting-since. (hopefully in future updates it will be transformer.time-utils/waiting, which is arguably the /correct* way to do it, but it isn&rsquo;t yet.)


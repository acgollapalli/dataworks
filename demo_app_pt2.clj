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

;; Here's what our order looks like in JSON:
{
    "billTo": "LANHOU",
    "deliveryWindow": {
        "startDate": "2020-03-18T19:00:00",
        "endDate": "2020-03-18T23:00:00"
    },
    "pickups": [
        {
            "shipper": "MOTPAS",
            "commodities": [
                {
                    "supplier": "SHEBEA",
                    "commodity": "NL",
                    "volume": 4500
                },
                {
                    "supplier": "SHEBEA",
                    "commodity": "SU",
                    "volume": 4000
                }
            ]
        }
    ],
    "drops": [
        {
            "site": "20405",
            "commodities": [
                {
                    "commodity": "NL",
                    "volume": 4500
                },
                {
                    "commodity": "SU",
                    "volume": 4000
                }
            ]
        }
    ]
 }

;; And here's what it looks like to our collector which helpfully
;; converts it to EDN that clojure likes.
{:billTo "LANHOU"
 :deliveryWindow {:startDate "2020-03-18T19:00:00"
                  :endDate "2020-03-18T23:00:00"}
 :pickups [{:shipper "MOTPAS"
            :commodities
            [{:supplier "SHEBEA"
              :commodity "NL"
              :volume 4500}
             {:supplier "SHEBEA"
              :commodity "SU"
              :volume 4000}]}]
 :drops [{:site "20405"
          :commodities [{:commodity "NL"
                         :volume 4500}
                        {:commodity "SU",
                         :volume 4000}]}]}

;; This time let's just write the response function first, and
;; then we'll look at the whole collector.

;;    (Ommitted because it's a pain to look at (and also wrong))

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

;;     (Omitted because it's a pain to look at (and also wrong))

;; Now, that's still a doozy, but at least it looks like a bunch
;; of functions instead of a single function. It looks
;; manageable, like something you could write at the repl, which
;; is the goal. I might make that any-failures? transducer
;; available in dataworks.common, and hence in all the stored
;; function namespaces as well, if it seems useful. But did it
;; work?

;; Let's macroexpand it back out:

;;   (Omitted because it's long and annoying to look at)
;;
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
   (assoc
    (clojure.walk/postwalk-replace
     {:billTo :order/bill-to
      :foreignOrderNumber :order/foreign-number
      :orderNumber :order/order-number
      :pickups :order/pickups
      :drops :order/drops
      :shipper :pickup/shipper
      :site :drop/site
      :commodity :commodity/type
      :volume :commodity/volume
      :supplier :commodity/supplier}
     order)
    :crux.db/id
    (keyword "order" id-string)))

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
  :authentication {:realm "Customer-Facing"
                   :scheme "Bearer"
                   :authenticate authenticate}
  :authorization {:authorize authorize
                  :custom/roles #{:user/orders}}
  :methods {:post
            {:consumes #{"application/json"}
             :produces "application/json"
             :response create-order}}})

;; Huh, well that was easy. After all the trouble it took to
;; create the function, actually turning it into a resource was
;; simple.

;; A couple notes on some things that we did.
;; The :authentication field is part of yada's resource model.
;; It asks you to define an authentication scheme (right now
;; we're only set up for bearer auth. This will change in the
;; future.), and to define an authentication function. We've
;; included an authentication function in the collectors
;; namespace for you to use that checks whether the bearer token
;; being used is valid and that the user exists in the internal
;; dataworks db.

;; The :authorization field is also a feature of yada's resource
;; model. It requires you to define an authorization function,
;; which we've included in the collectors namespace. Our
;; function checks whether any of the roles defined in the
;; :custom/roles field are included in the claims encoded in the
;; bearer token. Super simple, but effective.

;; clojure.walk/postwalk-replace is a function in the
;; clojure.walk namespace, which ships with clojure. You can
;; call just about any function from any namespace if you
;; fully qualify it, and it exists on the classpath. We don't
;; prevent this or sandbox the code, in part because we don't
;; have any idea what precisely you'll need to do. Instead, we
;; give you sensible defaults and presume you won't go outside
;; of them unless you need to. You can call up the internal db,
;; you can do java interop, and operate on the filesystem.
;; You probably shouldn't do those things, but if you need to
;; for some reason, we don't stop you. Again, don't eval code
;; from untrusted sources.

;; The rest of the resource is all stuff you've seen before.

;; So let's recap: In 124 lines of code we achieved the
;; following:
;;     Create an API endpoint that:
;;     Authenticates the user.
;;     Verifies that the user is authorized to create an order.
;;     Takes in an order.
;;     Checks for required fields
;;     Checks for valid order volumes
;;     Transforms the order into our order schema (needs work)
;;     Puts the order into our database.
;;     Sends the order to a client-side kafka consumer.
;;     Creates an alert if we don't hear back from our database
;;        in the next five minutes or so.

;; Not half bad!

;; One of the things that I really want to do with these demo
;; apps is show you how to do things that are actually useful.
;; Most demos are toy-ish, and try to show you how easy things
;; are, but in doing so, they neglect showing you how to actually
;; use the damn thing to do things you might actually want to do,
;; presuming that the programmer will just read the docs and
;; figure it out from there. I also expect you to read the docs
;; (once I write them), but frankly the best way to show you how
;; to use a tool is to just show you how to use the tool.

;; Now we need do do the consumer side. We presume the thing that
;; is consuming our tentative orders is a windows service that is
;; creating them, then sending us back an order event that looks
;; like the following (this time just the EDN):

{:OrderNumber 3563275,
 :ForeignOrderNumber 12345,
 :Status "Created",
 :Details ;; This is mostly the order schema above with a couple extras
 {:OrderNumber 3563275,
  :ForeignOrderNumber 12345,
  :OrderStatus "AVL",
  :BillTo "LANHOU",
  :DeliveryWindow {:StartDate "2020-03-18T19:00:00",
                   :EndDate "2020-03-18T23:00:00"},
  :Pickups [{:Shipper "MOTPAS",
             :Commodities [{:Supplier "SHEBEA",
                            :Commodity "NL",
                            :Volume 4500}
                           {:Supplier "SHEBEA",
                            :Commodity "SU",
                            :Volume 4000}]}],
  :Drops [{:Site 20405,
           :Commodities [{:Supplier "SHEBEA",
                          :Commodity "NL",
                          :Volume 4500}
                         {:Supplier "SHEBEA",
                          :Commodity "SU", :Volume 4000}]}]}}

;; Note the capitalization. That's a quirk of our windows service.
;; (I should probably fix that).
;; Anyways, now we need to consume all the stuff back up from the
;; Now this time we'll just start with our ->let macro, and build up
;; the function. Again, the last expression in the ->let block has to
;; evaluate out to a function, which means using fn not defn (which
;; returns a var not a function).

(->let
 (def consumer
   ;; This is our kafka consumer instance. Say something nice about it.
   ;; The consumer-instance function is in dataworks.stream-utils.
   ;; It's worth reading the source to help you understand what's
   ;; going on.
   (consumer-instance
    "order-events"
    :json))

 (defn db-fy
   "Our happy (now capitalized) db-fy function.
    Docstrings get chucked out when you macroexpand a '(defn ...)
    so this is totally fine (and sort of useless)."
   [order id-string]
   (assoc
    (clojure.walk/postwalk-replace
     {:BillTo :order/bill-to
      :ForeignOrderNumber :order/foreign-number
      :OrderNumber :order/order-number
      :Pickups :order/pickups
      :Drops :order/drops
      :Shipper :pickup/shipper
      :Site :drop/site
      :Commodity :commodity/type
      :Volume :commodity/volume
      :Supplier :commodity/supplier}
     order)
    :crux.db/id
    (keyword "order" id-string)))

 (defn handle-failure [record]
   (let [ids (query {:find '[e]
                     :where
                     [['e :order/foreign-number ForeignOrderNumber]
                      ['e :order/bill-to (:BillTo Details)]]})]
     (transact! :order-rejected record)
     (submit-tx
      (into []
            (map #(conj [:crux.tx/delete] %) ids)))))

 (defn handle-created [{:keys [OrderNumber
                               ForeignOrderNumber
                               Status
                               Details]
                        :as record}]
   (let [ids (query {:find '[e]
                     :where
                     [['e :order/foreign-number ForeignOrderNumber]
                      ['e :order/bill-to (:BillTo Details)]]})
         id (when (= 1 (count ids))
              (first ids))
         order (when id
                 (entity id))
         tx (if order )]
     (println record)
     (transact! :order-created record)
     (if order
       (submit-tx [[:crux.tx/cas
                    order
                    (assoc order
                           :order/order-number
                           OrderNumber
                           :order/status
                           (:OrderStatus Details))]])
       (when (empty? ids)
         (submit-tx [[:crux.tx/put
                      (db-fy
                       Details
                       (str "uuid." (java.util.UUID/randomUUID)))]])))))

 (defn handle-no-handler
   "This function is not japanese."
   [{:keys [Status]}]
   (println "No handler found for" Status "!!!"))

 (def handlers
   {"Created" handle-created
;;    "Updated" handle-updated
;;    "Completed" handle-completed
    "Failure" handle-failure})

 (defn event-handler
   "The thing that handles what our consumer returns."
   [consumer-record]
   (let [{:keys [Status] :as record} (.value consumer-record)
         handler (get handlers Status)]
     (if handler
       (handler record)
       (handle-no-handler record))))

 (fn [_]
   (consume! consumer event-handler)
   {:next-run 0}))

;; A few notes. First, you'll notice that we're creating a kafka
;; consumer instance. This won't be the case by version 0.5.0, but
;; it is the case right now, so please bear with it. Eventually we'll
;; have a thread pool of consumers that send consumed events to
;; core.async channels and it'll make it all cool and optimum and stuff.
;; The second thing is that we created a map of handler functions for
;; different events. It saves us from going
(if (= Status "my-status")
  (handle-status))
;; over and over. The third thing is that our actual consume! function
;; is really simple, it runs the function event-handler on each record
;; that our kafka consumer consumes. Finally, note that our last
;; function returns the map {:next-run 0} this tells the dataworks
;; cluster to re-run the collector immediately. It's sort of like
;; (while true ...), but distributed. It's probably worth noting
;; that all our handlers could be written as transactors as well.

;; And now you'll notice that we referred to a couple transactors that
;; haven't been written yet. We should probably write those.

:order-created
(fn
  [{:keys [OrderNumber Status Details] :as record}]
  (let [oauth-token "YOUR OATH TOKEN"
        url "YOUR URL"]
    (client/post
     (str url OrderNumber)
     {:oauth-token oauth-token
      :form-params Details
      :content-type :json})))

:order-rejected
(fn
  [{:keys [OrderNumber Status Details] :as record}]
  (let [oauth-token "YOUR OATH TOKEN"
        url "YOUR URL"]
    (client/post
     (str url OrderNumber)
     {:oauth-token oauth-token
      :form-params Details
      :content-type :json})))

:order-completed
(fn
  [{:keys [OrderNumber Status Details] :as record}]
  (let [oauth-token "YOUR OATH TOKEN"
        url "YOUR URL"]
    (client/post
     (str url OrderNumber)
     {:oauth-token oauth-token
      :form-params Details
      :content-type :json})))

;; "Now wait just a gosh-darned minute!" you think, "Those are all
;; the same function!" Yes, yes they are. And that's because I haven't
;; included URL params, but each of those goes to a different endpoint.
;; Given that that's the case, how might we handle this?
;; I know! Let's create a single transactor, that accepts two params,
;; an endpoint, and a record.

:order-update
(fn
  [{:keys [OrderNumber Status Details] :as record} endpoint]
  (let [oauth-token "YOUR OATH TOKEN"
        url "YOUR URL"]
    (client/post
     (str url endpoint OrderNumber)
     {:oauth-token oauth-token
      :form-params Details
      :content-type :json})))

;; And now for our internal:
(->let
 (def consumer
   ;; This is our kafka consumer instance. Say something nice about her.
   ;; The consumer-instance function is in dataworks.stream-utils.
   ;; It's worth reading the source to help you understand what's
   ;; going on.
   (consumer-instance
    "order-events"
    :json))

 (defn db-fy
   "Our happy (now capitalized) db-fy function.
    Docstrings get chucked out when you macroexpand a '(defn ...)
    so this is totally fine (and sort of useless)."
   [order id-string]
   (assoc
    (clojure.walk/postwalk-replace
     {:BillTo :order/bill-to
      :ForeignOrderNumber :order/foreign-number
      :OrderNumber :order/order-number
      :Pickups :order/pickups
      :Drops :order/drops
      :Shipper :pickup/shipper
      :Site :drop/site
      :Commodity :commodity/type
      :Volume :commodity/volume
      :Supplier :commodity/supplier}
     order)
    :crux.db/id
    (keyword "order" id-string)))

 (defn handle-failed-to-create [record]
   (let [ids (query {:find '[e]
                     :where
                     [['e :order/foreign-number ForeignOrderNumber]
                      ['e :order/bill-to (:BillTo Details)]]})]
     (transact! :order-update record "reject/")
     (submit-tx
      (into []
            (map #(conj [:crux.tx/delete] %) ids)))))

 (defn handle-created [{:keys [OrderNumber
                               ForeignOrderNumber
                               Status
                               Details]
                        :as record}]
   (let [ids (query {:find '[e]
                     :where
                     [['e :order/foreign-number ForeignOrderNumber]
                      ['e :order/bill-to (:BillTo Details)]]})
         id (when (= 1 (count ids))
              (first ids))
         order (when id
                 (entity id))
         tx (if order )]
     (println record)
     (transact! :order-update record "accept/")
     (if order
       (submit-tx [[:crux.tx/cas
                    order
                    (assoc order
                           :order/order-number
                           OrderNumber
                           :order/status
                           (:OrderStatus Details))]])
       (when (empty? ids)
         (submit-tx [[:crux.tx/put
                      (db-fy
                       Details
                       (str "uuid." (java.util.UUID/randomUUID)))]])))))

 (defn handle-completed
   [{:keys [OrderNumber ForeignOrderNumber Status Details] :as record}]
   (let [ids (query {:find '[e]
                     :where
                     [['e :order/foreign-number ForeignOrderNumber]
                      ['e :order/bill-to (:BillTo Details)]]})
         id (when (= 1 (count ids))
              (first ids))
         order (when id
                 (entity id))
         tx (if order )]
     (println record)
     (transact! :order-update record "fulfill/")
     (if order
       (submit-tx [[:crux.tx/cas
                    order
                    (assoc order
                           :order/status
                           (:OrderStatus Details))]])
       (when (empty? ids)
         (submit-tx [[:crux.tx/put
                      (db-fy
                       Details
                       (str "uuid." (java.util.UUID/randomUUID)))]])))))

 (defn add-bol-to-pickup [{:order/keys [pickups] :as order}
                          {:keys [Shipper BolNumber] :as bol}]
   (let [limit (count pickups)]
     (loop [pickups pickups
            i 0]
       (if (< i limit)
         (let [{:pickup/keys [shipper]
                :bol/keys [bol-numbers]
                :as pickup} (get pickups i)]
           (recur (if (= shipper Shipper)
                    (assoc pickups i
                           (update pickup
                                   :bol/bol-numbers
                                   (comp set conj)
                                     (keyword
                                      "bol"
                                      (str "bol." BolNumber))))
                    pickups)
                  (inc i)))
         (assoc order :order/pickups pickups)))))

 (defn handle-BOL [{:keys [OrderNumber BolNumber Shipper Document]
                    :as bol}]
   (submit-tx
    [[:crux.tx/put
      {:crux.db/id (keyword "bol" (str "bol." BolNumber))
       :bol/bol-number BolNumber
       :order/order-number OrderNumber
       :bol/document Document}]])
   (let [orders (query {:find ['e]
                        :where [['e :order/order-number OrderNumber]
                                ['e :order/foreign-number]]})
         order (when (= 1 (count orders))
                 (entity (first orders)))]
     (when order
       (transact! :send-image "bol/" BolNumber Document)
       (submit-tx [[:crux.tx/cas
                    order
                    (add-bol-to-pickup order bol)]]))))

(defn add-delivery-ticket-to-drop [{:order/keys [drops] :as order}
                                   {:keys [Site] :as bol}
                                   id]
   (let [limit (count drops)]
     (loop [drops drops
            i 0]
       (if (< i limit)
         (let [{:drop/keys [site]
                :delivery-ticket/keys [tickets]
                :as drop} (get drops i)]
           (recur (if (= site Site)
                    (assoc drops i
                           (update drop
                                   :delivery-ticket/tickets
                                   (comp set conj)
                                     id))
                    drops)
                  (inc i)))
         (assoc order :order/drops drops)))))

 (defn handle-delivery-ticket [{:keys [OrderNumber Site Document]
                                :as delivery-ticket}]
   (let [uuid (java.util.UUID/randomUUID)
         id (keyword "delivery-ticket" (str "uuid." uuid))]
     (submit-tx
    [[:crux.tx/put
      {:crux.db/id id
       :order/order-number OrderNumber
       :delivery-ticket/document Document}]])
   (let [orders (query {:find ['e]
                        :where [['e :order/order-number OrderNumer]
                                ['e :order/foreign-number]]})
         order (when (= 1 (count orders))
                 (entity (first orders)))]
     (when order
       (transact! :send-image "dt/" Site Document)
       (submit-tx [[:crux.tx/cas
                    order
                    (add-delivery-ticket-to-drop
                     order
                     delivery-ticket
                     id)]])))))

 (defn handle-no-handler
   "This function is not japanese."
   [{:keys [Status]}]
   (println "No handler found for" Status "!!!"))

 (def handlers
   {"Created" handle-created
    "Failed-To-Create" handle-failed-to-create
    "BOL" handle-BOL
    "Delivery-Ticket" handle-delivery-ticket})

 (defn event-handler
   "The thing that handles what our consumer returns."
   [consumer-record]
   (let [{:keys [Status] :as record} (.value consumer-record)
         handler (get handlers Status)]
     (if handler
       (handler record)
       (handle-no-handler record))))

 (fn [_]
   (consume! consumer event-handler)
   {:next-run 0}))

;; So that's one collector, one internal, and one transactor. Okay,
;; so far so good.

;; We need a second transactor to handle the bol images:
:send-image
(fn
  [endpoint param document]
  (let [oauth-token "YOUR OATH TOKEN"
        url "https://uat-tte-order.qa.fuelnow.network/image/"]
    (client/put
     (str url endpoint param)
     {:oauth-token oauth-token
      :form-params {:imageType "PDF"
                    :imageData document}
      :content-type :json})))

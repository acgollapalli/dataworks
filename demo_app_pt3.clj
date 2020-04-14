;; Let's build a dispatch system!
;;
;; Before what we built was a middleman for one trucking company
;; to send orders to another company and receive updates and
;; documents. Now let's try building a whole dispatch backend!

;; What is the shape of our data that is coming in?
{:ordernumber 3563275,
 :foreignordernumber 12345,
 :status "created",
 :details ;; this is mostly the order schema above with a couple extras
 {:ordernumber 3563275,
  :foreignordernumber 12345,
  :orderstatus "avl",
  :billto "lanhou", ;; Landmark
  :deliverywindow {:startdate "2020-03-18t19:00:00",
                   :enddate "2020-03-18t23:00:00"},
  :pickups [{:shipper "motpas", ;; Motiva Pasadena
             :commodities [{:supplier "shebea", ;;Shell Branded
                            :commodity "nl", ;; unleaded fuel
                            :volume 4500}
                           {:supplier "shebea",
                            :commodity "su", ;; premium fuel
                            :volume 4000}]}],
  :drops [{:site 20405, ;; Site no. 20405
           :commodities [{:supplier "shebea",
                          :commodity "nl",
                          :volume 4500}
                         {:supplier "shebea",
                          :commodity "su", :volume 4000}]}]}}

;; Our events (keywordified):

:created
:updated
:planned
:completed
:invoiced

;; Now this is all pretty good, by itself. It takes advantage
;; of the fact that we're using a document store for our
;; database. However, it does not take advantage of the fact
;; that our document store is also a graph database. Nor does
;; it take advantage of the fact that our document references
;; are namespaced keywords. It doesn't really take full
;; advantage of the fact that our database and our application
;; are actually the same thing, integrated as strongly as they
;; are, written in the same language, and that we can basically
;; work however we want. Finally, it does not take advantage of
;; Crux's bitemporality, (Not that this little sketch of an
;; application does any of those things fully either, but I
;; think it does a bit more so.)

;; So how do we take advantage of the fact that we have a graph
;; database? Well, we create nodes and edges!

;; TODO explain what a graph database is and add a cool-looking
;; picture of nodes and edges.

;; So this is a logistics dispatch application, right? And what
;; exactly does a logistics dispatch application do. It routes
;; stuff from place to place. So our edge is pretty simple, a
;; source, where the stuff comes from, a sink, where the stuff
;; goes to, and the flow which is the stuff itself. And hang on
;; there's one more thing. This is a dispatch application.
;; Which means it's used by businesses. And businesses don't
;; run for free, they run on supply and demand. They make money
;; by fulfilling demand.

;; The fulfillment.
{:crux.db/id :fulfillment/uuid.<uuid-1>
 :source :shipper/motiva-pasadena
 :sink :consignee.landmark/store.20405
 :flow {:commodity.shell/unleaded 4500
        :commodity.shell/premium 4000}
 ;; optional
 :fulfills :demand/uuid.<uuid-2>}

;; Now, you'll notice that the :fulfills key is optional.
;; There's a good reason for this, and it's a business reason,
;; not a programming one. The trucking business, and in
;; particular, the fuel trucking business runs on a few
;; different business models.

;; The first one is obvious: the call-in order. A gas station
;; order, or a company that owns gas stations calls a trucking
;; company, saying "I need 8600 gallons of unbranded unleaded
;; fuel at location :x". The trucking company sends out a
;; driver & truck to pick up the ordered fuel from a fuel
;; terminal (in logistics speak, the shipper), and then the
;; driver takes the fuel to the gas station (in logistics
;; speak, the consignee), and puts it in the gas station's
;; tank, which is where the gas pumps you use to put gas in
;; your car pull it from.

;; The second way fuel trucking businesses work is less
;; obvious, but arguably more convenient for all involved, and
;; a method that lends itself more easily to automation: the
;; managed fuel services model. Instead of the gas station
;; owner calling the trucking company, saying "We need so many
;; gallons at :x", the trucking company remotely views (through
;; computers) the amount of fuel at each gas station, usually
;; through a website or through the dispatch application
;; directly. Usually gas stations have three tanks, one for
;; unleaded, one for premium, and one for diesel. It's not
;; really necessary to go to deep into all this, but suffice to
;; say, the trucking company tries to keep the amount of fuel
;; at the gas station above a certain amount.

;; Anyway, the point being, let's generalize those two so we
;; can approach the root of the problem. Business is about one
;; thing, taking supply, and using it to fulfill demand, and
;; both business models fulfill the demand for fuel, which is
;; what we want. However, one form of demand (the order) is
;; explicit, whereas the other (the managed service) is
;; implicit. Let's take a look at what demand looks like in
;; our database.

;; The demand schema.
{:crux.db/id :demand/uuid.<uuid-2>
 :requester :customer/fuelNOW
 :requested {:commodity.shell/unleaded 4500
             :commodity.shell/premium 4000}
 :at :consignee.landmark/store.20405}

;; Now, that makes a lot of sense doesn't it? It tells us:
;; Who's asking? What are they asking for? Where they asking
;; for it to be delivered?. Simple. Yet not quite sufficient,
;; at least not yet. First, how do we know when the customer
;; wants the fuel by? The fuel may be needed a week from now,
;; or RIGHT NOW or they're going to run out and then nobody can
;; buy gas or get to work and everyone has a really bad day.
;; And second, how does the implicit demand of managed service
;; play into this? Well the answer to both questions is
;; bitemporality.

;; Now, I said I was going to eplain the advantages of Crux's
;; bitemporality in Part 2, and I never really did. And not
;; only did I not explain it, I didn't even explain what it
;; WAS. Well, it's kind of hard to do without a working
;; example, which we now have.

;; To explain bitemporality, we need to think about what a
;; database is. A database is a store of data. But in clojure
;; land, a database isn't just a store of data, a database is
;; a repository of state. And with bitemporality, it's a record
;; of the change of state over time.

;; A bitemporal database has two important, implicit fields in
;; every document. The first is the transaction time, and the
;; second is the valid time, which is actually a window of
;; time. You can think of a document like a set of connected
;; facts. The transaction time is when you knew those facts,
;; and the valid time is when those facts actually became
;; relevant. For instance. You may have demand for fuel at a
;; location, and that demand may be a week from now. If you
;; tried send fuel right now, the tanks would be full and
;; wouldn't have room for it, so you'd have to take the fuel
;; you went to the trouble of buying and transporting to some
;; other place, or even back to the place you bought it from
;; (that's called a retain, btw). So what do you do, you say
;; that there will be demand for fuel at :x location a week
;; from now, which is the valid time, but you say that right
;; now, which is the transaction time. Suppose three days
;; later, after a market downturn, we now know there is going
;; to be no demand at :x location, we can update our knowledge
;; to say that there will be no demand at that location, simply
;; by deleting the explicit demand entry. Anyway, if we want to
;; say when demand exists, all we have to do is specify that in
;; the valid time entry when we put the data into the database.
;; That answers the first question.

;; Now, anyone who tries to naively query crux will find that
;; they are, for some reason, only able to return documents
;; that are valid RIGHT NOW. How do you get documents that are
;; valid a week from now? How do you get things that you think
;; will occur, but haven't occurred yet? Well the answer to that
;; is TIME TRAVLEL. Now I'm not being facetious here. Crux
;; actually has a feature called time travel, as does its
;; predecessor Datomic. How and why can we do this? We already
;; said that a database is a repository of state. That doesn't
;; just mean that it holds a state, that means that it holds a
;; timeline of states, past, present, and future. Which is what
;; the valid time is. By specifying :as-of <one-week-from-now>
;; we can see the demand at different stores one week from now.
;; This allows us to plan out fulfillments in advance.

;; But what about implicit demand, via managed services? How do
;; we wrestle with that temporally traversing elephant? Well,
;; then answer is that we have to predict the future. Now,
;; humans actually aren't half bad at this. Planning is the
;; very definition of predicting the future and preparing
;; accordingly. Now, how do we predict the future? Why, off of
;; current data, same way we predict everythihng else. We
;; presume for managed services that we have tank data coming
;; in every few minutes:

{:crux.db/id :consignee.landmark/store.20405
 :consignee/address "<address>"
 :consignee/gps-coordinates {:latitude <latitude>
                             :longitude <longitude>}
 :consignee/tanks {:commodities.shell/premium
                   {:volume 4000
                    :capacity 15000}
                   :commodities.shell/unleaded
                   {:volume 9000
                    :capacity 25000}
                   :commodities.shell/diesel
                   {:volume 3000
                    :capacity 10000}}
 :consignee/contact {:name "store manager"
                     :phone "phone-number"}}

;; Now those give the volumes (that we know) right now. We can
;; time travel back every couple days to get readings and find
;; the average rate of consumption, accounting for previous
;; fulfillments, and use that to get an average rate of
;; consumption, and thus predict predict when we next need to
;; send a truck out to fill their tanks. And we don't even
;; need to create explicit demand requirements, because we can
;; just create a function to generate that for us when we need
;; it.

;; "Now hold on just a minute" you think. "If we don't have
;; explicit demand requirements, then what happens if we have a
;; retain, or if we let the gas station run (in logistics
;; speak, a runout)." Well that's, again, where bitemporality
;; comes in. You see, a database isn't just a repository of
;; state over time, it's also a repository of our knowledge of
;; state over time. That means that we can not only time travel
;; to different parts of the timeline, but to what we thought
;; the timeline was at different times. So we can ask "What did
;; we think the tank volumes of location :x would be two weeks
;; ago when we planned that fulfillment?" and because of time
;; travel, we get the exact same answer we got two weeks ago.
;; That means that we can look at what we knew back when we
;; made a decision, and judge our decision by those standards,
;; instead of what we know right now. In addition, we can
;; adjust the way we make decisions to compensate for factors
;; that we didn't understand at the time. And because dataworks
;; stores it's functions in the database, we can ask our
;; application how would we have made that decision then,
;; according to our old decision function, and how would we
;; make it now, based on our new decision function. That's a
;; relatively easy thing to do, if you know what you're doing.

;; Anyway, we've so far investigated demand and fulfillment.
;; Let's talk about supply. Every terminal has only so much
;; fuel at a terminal, of any one type. And each terminal lets
;; each company only buy so much of it at a time. So companies
;; that fulfill a lot of volume often have trouble with running
;; out of fuel at a terminal, and need to plan out their loads
;; while recognizing these limitations, and planning
;; accordingly. So the best way to do this is to keep track of
;; current state of their fuel allocations at a certain terminal
;; and again use this to predict the future:

{:crux.db/id :shipper/motiva-pasadena
 :shipper/address "terminal address"
 :shipper/gps-coordinates {:latitude <latitude>
                           :longitude <longitude>}
 :shipper/allocations {:commodity/unleaded 200000
                       :commodity/premium 100000
                       :commodity.shell/unleaded 150000
                       :commodity.shell/premium 50000}
 :shipper/contact {:name "terminal manager"
                   :phone "phone-number"}}

;; So we know right now that we can pull 200,000 gallons from
;; unbranded unleaded fuel from motiva-pasadena and plan
;; accordingly. Later in the day. It might look like this:

{:crux.db/id :shipper/motiva-pasadena
 :shipper/address "terminal address"
 :shipper/gps-coordinates {:latitude <latitude>
                           :longitude <longitude>}
 :shipper/capacities {:commodity/unleaded 12000
                      :commodity/premium 45000
                      :commodity.shell/unleaded 100000
                      :commodity.shell/premium 45000}
 :shipper/contact {:name "terminal manager"
                   :phone "phone-number"}}

;; And then the next day it might reset back to the amount
;; above. Terminals, when they change the amount that we are
;; allocated for can send us the change via a collector (an
;; api endpoint) and then we can update our future allocations
;; amounts accordingly.

;; Let's say that we aren't dealing with fuel though. Say we're
;; dealing with an amazon warehouse.
{:crux.db/id :shipper/amazon-warehouse-no-29385472
 :shipper/address "address"
 :shipper/gps-coordinates {:latitude <latitude>
                           :longitude <longitude>}
 :shipper/capacities {:item/rubber-ducky 299938
                      :item/lime-16gb-ipad 9293484
                      :item/fake-tomato 3939
                      :item/delivery-drone-parts 1201
                      ... ...}}

;; All the logic that we've covered thus far applies without any
;; modifications required, right down to the managed services
;; as amazon may very well be moving their delivery drone parts
;; to their drone service center, which is a sign of implicit
;; demand.

;; Now there's one more piece to this puzzle, and that's drivers
;; and trucks! They're the people actually moving the stuff,
;; after all, though I guess some of this could apply to drones
;; as well). Well, at the end of the day, the question that
;; we're dealing with is again a resource management one, only
;; this time, we're the supplier and out edges are the demand.
;; Do we create more edges and nodes? Well, obviously!

{:crux.db/id :driver/john-smith
 :driver/shift {:start <shift-start>
                :end <shift-end>}
 :driver/personal-info ...}

{:crux.db/id :transport/tractor.1278
 :transport/driver :driver/john-smith
 :transport/fullfilling #{:fulfillment/uuid.<uuid-1>}
 :transport/misc ...}

;; and for the sake of illustrating how this actually works
(crux/submit-tx user-db
                [
                 [:crux.tx/put
                  {:crux.db/id :transport/tractor.1278
                   :transport/driver :driver/john-smith
                   :transport/fullfilling
                   #{:fulfillment/uuid.<uuid-1>}
                   :transport/misc ...}
                  (tick/inst <shift-start>)
                  (tick/inst <shift-end>)]
                 ]) 

;; You'll notice that we've omitted the :transport/driver
;; and :transport/fullfilling because the truck isn't being
;; driven and the truck isn't fulfilling anything after the
;; shift is over. We could alternatively set these values to
;; nil or the empty set #{} respectively, to indicate the same.

;; Now so far what can we do? With the current shape of our
;; data, we can assess supply, assess demand, and assign
;; transportation resources to fulfill that demand. But how
;; do we know when the demand is fulfilled? Well, there are
;; two things, and these are transportation industry specific,
;; but I'll try to keep it general.

;; On the fullfillment itself:
{:crux.db/id :fulfillment/uuid.<uuid-1>
 :source :shipper/motiva-pasadena
 :sink :consignee.landmark/store.20405
 :flow {:commodity.shell/unleaded 4500
        :commodity.shell/premium 4000}
 :proof-of-completion {:bill-of-lading
                       #{:bill-of-lading/uuid.<uuid-3>}
                       :delivery-ticket
                       :delivery-ticket/uuid.<uuid-4>}
 ;; optional
 :fulfills :demand/uuid.<uuid-2>}

;; Now here's why this is useful. Simply put, any completed
;; fulfillment will have proof of completion usually this is
;; some signed document scanned and uploaded into our system.
;; This is axiomatic. Today it may be a signed and scanned
;; document or documents, such as the bill(s) of lading
;; received at the terminal, and the delivery ticket which is
;; signed by the consignee on receipt of the goods concerned.
;; Tomorrow, it may be some kind of cryptographically signed,
;; electronically generated proof upon delivery by automated
;; transport. We can be as specific or as general as we want,
;; however in this case, we'll assume a fulfillment has been
;; completed if there is a proof-of-completion with non-nil
;; :bill-of-lading and :delivery-ticket. The :bill-of-lading
;; and :delivery-ticket are the same as what you saw in part
;; two. The :bill-of-lading being a set instead of a single
;; entry is because multiple bills of lading is a thing that
;; happens in real life, usually because of some kind of error
;; at the terminal. Having two delivery tickets for a single
;; site is something that generally does not happen, and
;; really, really should not happen.

;; Now how do we bill the completed fulfillments? Well, we send
;; them to reconciliation, which will be covered later.

;; Anyway, we've spent an awful lot of time talking around a
;; data model, let's talk about how people (and I guess
;; computers too) do something useful with it!

;; Our API endpoints:

:demand
;; This endpoint is for requests by customers. It's version of
;; what we did in Part 2, but a lot simpler, and easier to
;; validate.

:supply
;; This endpoint is how different shippers send us their
;; inventory. They're only sending us how much they're actually
;; willing to sell us.

:fulfillment
;; How our dispatchers actually create fulfillments.

:personel
;; personel management.

:resources
;; tractors, trailers, drones, or whatever else.

:customers
;; managing customer contact info, etc.

:predictions
;; predictions, on demand, supply, resources, and how we intend
;; to approach the next week(s).

;; Obviously this is a lot more ambitious than what we did
;; previously right? Well that's because we're building a real
;; application, not a toy or a tutorial. Anyway, let's get on
;; with it:

:demand
;; what we expect to be posted to us (json converted to edn).
{:what {:commodity.shell/unleaded 4500
           :commodity.shell/premium 4000}
 :where :consignee.landmark/store.20405
 :when <delivery-time>}

(->let
 (defn commodities-valid?
   [{:keys [what] :as demand}]
   (let [commodities (keys what)
         valid? (map #(if (entity %)
                       :valid
                       nil)
                     commodities)]
     (if (every? some? valid?)
         demand
          (failure :not-every-commodity-is-valid
                   (zipmap commodities valid?)))))

 (defn amounts-non-zero?
   [{:keys [what] :as demand}]
   (let [amounts (values what)]
     (if (every? #(> % 0) amounts)
       demand
       (failure :amount-must-be-greater-than-0
                (into {}
                      (filter
                       #(not (> (second %) 0))
                       what))) )))

 (defn where-valid?
   [{:keys [where] :as demand}]
   (if (entity where)
     demand
     (failure :invalid-where {:where where})))

 (defn parseable-when?
   [{:keys [when] :as demand}]
   (let [valid-when? (consume-time when)]
     (if valid-when?
       (assoc demand :when valid-when?)
       (failure :unparseable-when {:when when}))))

 (defn valid-when?
   [{:keys [when] :as demand}]
   (let [now (tick/now)]
     (if (tick/>= when now)
       demand
       (failure :when-must-be-in-future-or-present
                {:when (consume-time when)
                 :now now}))))

 (defn log-demand! [{:keys [request where when]}
                    requester]
   (let [id (keyword "demand"
                     (str "uuid." (java.util.UUID/randomUUID)))
         tx {:crux.db/id id
             :requester requester
             :requested request
             :at where}]
     (try (submit-tx [[:crux.tx/put
                       tx
                       (tick/inst when)]])
          (success :request-received tx)
          (catch Exception e
            (failure :failed-to-update-db
                     (.getMessage e))))))
;; TODO create a function for these damn status messages.

 (defn validate-and-log-demand [demand requester]
     (->? demand
        commodities-valid?
        amounts-non-zero?
        consignee-valid?
        parseable-delivery-time
        valid-delivery-time
        (log-demand! requester)))

 {:id :create-request
  :description "Endpoint to validate and create a request"
  :authentication {:realm "Customer-Facing"
                   :scheme "Bearer"
                   :authenticate authenticate}
  ;; TODO Ensure that customers are able to order only for
  ;; their own stores.
  :authorization {:authorize (authorize :user/order)}
  :methods {:post
            {:consumes #{"application/json"}
             :produces "application/json"
             :response
             (fn [ctx]
               (let [demand (:body ctx)
                     requester (get-in ctx [:header :user])]
                 (validate-and-log-demand demand
                                          requester)))}
            ;; TODO add a get method with GPS tracking data.
            }})

;; Well one down, a shitton to go!

:supply
;; what we expect (json converted to edn):

(transformers
 []
 (defn update-supply
   [{:keys [location inventory] :as supply}]
   (->? supply

        )
   )

 {:id :supply
  :description "Where we get our supply routes"
  :authentication {:realm "Customer-Facing"
                   :scheme "Bearer"
                   :authenticate authenticate}
  ;; TODO Ensure that customers are able to order only for
  ;; their own stores.
  :authorization {:authorize (authorize :user/supply)}
  :methods {:post
            {:consumes #{"application/json"}
             :produces "application/json"
             :response
             (fn [{:keys [body]}]
               (update-supply))}}}
 )

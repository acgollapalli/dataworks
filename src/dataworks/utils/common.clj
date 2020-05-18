(ns dataworks.utils.common
  (:require
   [clojure.string :as string]
   [clojure.pprint :refer [pprint]]
   [clojure.edn :as edn]
   [time-literals.read-write :as time-literals]))

(defmacro ->?
  "The Validation Macro:
  A utility function for validation of data and transactions
  Like the threading macro (->), except after each function, it
  looks to see whether the function returned a map with the key
  :status and the value :faiure. If the function did return such
  a map, then we return that same map, other wise we pass the
  result to the next function like the threading macro (->)."
  [x & forms]
  (loop [x x
         forms forms]
    (if-not forms
      x
      (let [next-form (first forms)
            status? `(fn ~'[params]
                       (let ~'[status (:status params)]
                         (if (= ~'status :failure)
                         ~'params
                         (-> ~'params ~next-form))))]
        (recur (list status? x) (next forms))))))

;;(defn recursive-filter
;;  "recursively filters through nested sequences, applying the
;;   test 'test' to each sequence and if a function 'f' is
;;   specified, applyed to that sequence, returning the result of
;;   f as though you had done (map f (filter test s))."
;;  ([test f s]
;;   (if (coll? s)
;;     (if (test s)
;;       (concat '()
;;               (f s)
;;               (apply concat
;;                      (map (partial recursive-filter test f)
;;                           s)))
;;       (apply concat
;;              (map (partial recursive-filter test f)
;;                   s)))
;;     '()))
;;  ([test s]
;;   (recursive-filter test #(conj '() %) s)))
;;
;;(defn get-names
;;  "searches through a seq 's' for sequences starting with a
;;   symbol 'sym' then gets the second value of the sequence.
;;   (in the name of )
;;   returns results as a list.
;;
;;   input: (get-defs 'defn
;;                    '(defn a
;;                       [x]
;;                       (defn b
;;                         [y]
;;                         (inc y))
;;                       (b a)))
;;
;;  output: '(a b)
;;
;;  Why would you want this? Mainly in the case of nested
;;  transformer blocks to get all the stored functions a stored
;;  function depends upon."
;;  [sym s]
;;  (recursive-filter
;;   #(= (first %) sym) ;; the test function
;;   second ;; the function applied to a tested sequenct
;;   s)) ;; the sequence itself

(defmacro ->let
  "Input:
          (->let
            (defn plus-2
              [a]
              (+ 2 a))
            (fn [b]
              (/ (plus-2 b) 3)))

  Output (macroexpansion):
          (let
            [plus-2 (fn [a]
                      (+ 2 a))]
            (fn [b] (/ (plus-2 b) 3)))
  The above actually returns a function, Because that's what
  the macroexpanded form evaluates to. Essentially, it takes
  every expression but the last that macroexpands to (def name
  expression) and makes it so that the name and expression are
  part of a let expression:
       (let [name expression
             ...
             name expression]
         last-expression)
  Any expressions (except the last) that don't macroexpand
  out to (def something expression) are simply thrown out."
  [& forms]
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

(defn read-string
  "read-string that reads time-literals and, importantly,
   doesn't evaluate what it reads."
  [string]
  (edn/read-string
   {:readers time-literals/tags}
   string))

(defn stringify-keyword
  "Input:    :key
   Output:    \"key\""
  [key]
  (string/replace (str key) #":" ""))

(defn get-entity-param
  "input: (get-entity-param :test :transformer)
   output :transformer/test"
  [param function-type]
  (keyword
   (stringify-keyword function-type)
   (if (keyword? param)
     (stringify-keyword param)
     param)))

(defn generate-message
  "Lets you insert a keyword into a string, then turn
   that into a keyword.
   Message:    \"happy-%-message\"
   Key:        :little
   Result:     :happy-little-message"
  [key message]
  (keyword
   (string/replace
    message #"%" (stringify-keyword key))))

(defn request-status
  "It was such a common hassle to have to manually write
   out maps like {:status :failure} or {:status :success}
   that I wrote out this and the following two functions.

   Input: (request-status :success
                          :function-created
                          <function>)
   Output: {:status :success
            :message :function-created
            :details <function>}"
  ([status message]
   {:status status
    :message message})
  ([status message details]
   (assoc (request-status status message)
          :details
          details)))

(def failure
  "failure status message. takes 1-2 args"
  (partial request-status :failure))

(def success
  "success status message. takes 1-2 args"
  (partial request-status :success))

(defn response-status
  "This takes a yada context as well as the status and body
   that you want it to have and returns a yada response with
   that status and body"
  [{:keys [response]} status body]
  (assoc response
         :status status
         :body body))

(defn if-failure-response
  "A convenience function that allows an http-status-code
   for failure responses."
  [ctx response http-failure-code]
  (if (= (:status response) :failure)
    (response-status ctx http-failure-code response)
    response))

(defn vec-ify
  [maybe-coll]
  (if (coll? maybe-coll)
    (into [] maybe-coll)
    [maybe-coll]))

(defn blank-field?
  "Checks to see whether the specified parameters are blank
   or nil. m must be a map. fields must be keywords. Values
   must be strings"
  ([{:stored-function/keys [type] :as m} & fields]
   (loop [fields fields]
     (if fields
       (let [field (first fields)]
         (println "Checking for blank:" field)
         (if (string/blank? (get m (get-entity-param field type)))
           {:status :failure
            :message (generate-message
                      field "%-cannot-be-blank")}
           (recur (next fields))))
       m))))

(defn missing-field?
  "Checks to see whether the specified parameters are missing.
   m must be a map. fields must be keywords."
  ([{:stored-function/keys [type] :as m} & fields]
   (loop [fields fields]
     (if fields
       (let [field (first fields)]
         (println "Checking for null:" field)
         (if (nil? (get m (get-entity-param field type)))
           {:status :failure
            :message (generate-message
                      field "%-must-have-a-value")}
           (recur (next fields))))
       m))))

(defn empty-field-collection?
  "Checks to see whether the specified paramaters are empty,
   and whether they are collections. m must be a map.
   fields must be keywords"
  ([{:stored-function/keys [type] :as m} & fields]
   (loop [fields fields]
     (if fields
       (let [field (first fields)]
         (println "Checking for empty collection:" field)
         (if-let [val (get m (get-entity-param field type))]
           (cond (not (coll? val))
                 (failure
                  (generate-message
                   field "%-must-be-collection"))
                 (empty? val)
                 (failure
                  (generate-message
                   field "%-cannot-be-empty"))
                 :else (recur (next fields)))
           (failure
            (generate-message
             field "%-required"))))
       m))))

(defn valid-name?
  "Ensures that name can be converted to keyword.
   Returns name as keyword."
  [{:stored-function/keys [type] :as params}]
  (println "validating name" (get params (get-entity-param :name type)))
  (let [name ((get-entity-param :name type) params)]
    (cond
      (keyword? name) params
      (not (string? name))
      {:status :failure
       :message :name-must-be-string}
      (string/includes? name ":")
      {:status :failure
       :message :name-cannot-include-colon}
      (string/starts-with? name "/")
      {:status :failure
       :message :name-cannot-start-with-slash}
      (string/includes? name " ")
      {:status :failure
       :message :name-cannot-include-whitespace}
      :else (assoc params (get-entity-param :name type) (keyword name)))))

(defn parseable?
  "Checks whether a string is parseable to edn.
   Doesn't eval. Accepts time-literals."
  [{:stored-function/keys [type] :crux.db/keys [id] :as params} key]
  (println "Checking" key "parseability: " id)
  (if-let [value (get params (get-entity-param key type))]
    (try (assoc params (get-entity-param key type) (read-string value))
         (catch Exception e
           {:status :failure
            :message (generate-message
                      key "unable-to-parse-%")
            :details (.getMessage e)}))
    params))

(defn updating-correct-function?
  "Checks name param against provided param."
  [{:stored-function/keys [type] :as params} path-name]
  (let [key (get-entity-param :name type)
        name (get params key)]
    (println "Checking for correct function: " path-name "," name)
    (if name
      (if (= name (keyword path-name))
        params
        {:status :failure
         :message :name-param-does-not-match-path
         :details
         {:name-in-path (keyword path-name)
          :name-in-params name
          :msg (str "We don't let you rename stored functions.\n"
                    "If it's changed enough to be renamed, "
                    "it's a different function at that point\n"
                    "Best thing is to retire the old function "
                    "and create a new one.")}})
      (assoc params key path-name))))

(defn has-params?
  "Checks for presence of params in new function. If absent,
   it adds them from the current function. function-type and
   params must be keywords."
  [params & params?]
  (let [function-type (:stored-function/type params)
        current-function (:current/function params)]
    (loop [params? params?
           input params]

      (if (and params?
               (not= (:status input) :failure))
        (recur (next params?)
               (let [param (first params?)
                     key (get-entity-param param function-type)]
                 (println "Checking for" param)
                 (if-not (get params param)
                   (assoc input key (key current-function))
                   input)))
        input))))

(defn has-parsed-params?
  "Checks for presence of params that are meant to be parsed
   in new function. If present, it parses them. If absent,
   it adds them from the current function. function-type and
   params must be keywords."
  [params & params?]
  (let [function-type (:stored-function/type params)
        current-function (:current/function params)]
    (loop [params? params?
           input params]
      (println "input: " input)
      (if (and params?
               (not= (:status input) :failure))
        (recur (next params?)
               (let [param (first params?)
                     key (get-entity-param param function-type)]
                 (println "Checking for" param)
                 (if-not (get params key)
                   (assoc params key (key current-function))
                   (parseable? input param))))
        input))))

(defn valid-update?
  "Checks if any of the params? have been changed from
   the current stored function. If they have, then we
   return the params map. If they haven't, then we
   return the failure map"
  [params & params?]
  (let [function-type (:stored-function/type params)
        current-function (:current/function params)]
    (println "Checking if valid update.")
    (if (every? #(= (get params %)
                    (get current-function
                         (get-entity-param % function-type)))
                params?)
      {:status :failure
       :message (generate-message function-type
                                  "no-change-from-existing-%")}
      params)))

(defn function?
  "Checks if function is function."
  [function]
  (if (fn? function)
    function
    {:status :failure
     :message :function-param-does-not-evaluate-to-function}))

(defn select-ns-keys
  "Like select keys, but for the namespaces of keys"
  [m & ns]
  (into
   {}
   (filter
    (fn [[k v]]
      (some
       (partial = (namespace k))
       (map stringify-keyword ns))))
   m))

(defn ns-keys
  "Adds ns to all keys in map params"
  [params ns]
  (into {}
        (map
         (fn [[k v]]
           [(keyword (stringify-keyword ns)
                     (stringify-keyword k))
            v]))
        params))

(defn set-ns
  "Adds a namespace to keys, along with :stored-function/type and
   :crux.db/id"
  [params fn-type]
  (merge
   {:stored-function/type fn-type
    :crux.db/id (get-entity-param (:name params) fn-type)}
   (ns-keys params fn-type)))

(defn exclude-ns-keys
  "Like dissoc but for namespaces of keys"
  [m & ns]
  (into
   {}
   (filter
    (fn [[k v]]
      (not-any?
       (partial = (namespace k))
       (map stringify-keyword ns))))
   m))


;;(defn dependencies?
;;  "I don't think we actually use this anymore. But it's probably
;;   still nice to have the data for debugging purposes."
;;  [params]
;;  (let [function-type (:stored-function/type params)
;;        dependencies
;;        (into #{}
;;              cat
;;              ((juxt
;;                (comp
;;                 (partial map
;;                          #(get-entity-param
;;                            (keyword %)
;;                            :transformer))
;;                 (partial get-names 'transformers))
;;                (partial recursive-filter
;;                         #(= (first %) 'transact!)
;;                         #(conj '()
;;                                (get-entity-param
;;                                 (second %)
;;                                 :transactor)))
;;                (partial recursive-filter
;;                         #(= (first %) 'stream!)
;;                         #(conj '()
;;                                (second %))))
;;               ((get-entity-param :function function-type)
;;                params)))]
;;    (if-not (empty? dependencies)
;;      (assoc params
;;             :stored-function/dependencies
;;             dependencies)
;;      params)))
;;
;;
;;(defn paths
;;  "returns all paths from start to end along the graph
;;   specified via edges. (not used by the app anymore)"
;;  ([edges start end]
;;   (recursive-filter #(keyword? (first %))
;;                     (paths edges start end (list start))))
;;  ([edges start end l]
;;   (let [deps (filter #(= (first l)
;;                          (last %))
;;                      edges)
;;         set-deps (into #{}
;;                        (map first deps))]
;;     (if (some some? (map set-deps l))
;;       (throw (Exception. (str "circular dependency: "
;;                               (conj l
;;                                     (some identity
;;                                           (map set-deps
;;                                                l)))))))
;;     (if-not (= (first l) end)
;;       (map (partial paths edges start end)
;;            (map #(conj l %)
;;                 (map first
;;                      deps)))
;;       l))))
;;
;;(defn order-nodes
;;  "returns the list of nodes in order of longest
;;   distance to the root node, along the graph
;;   specified via edges.
;;   This is useful for figuring out the order in which
;;   to recompile functions depending on the root function
;;   in which the dependency relations are specified in edges.
;;   (not used by the app anymore)"
;;  [root edges]
;;  (map second
;;       (sort-by first
;;                (map (juxt
;;                      (comp (partial apply max)
;;                            (partial map count)
;;                            (partial paths edges root))
;;                      identity)
;;                     (map first
;;                          edges)))))

(defn recursive-replace
  "Recursive find and replace. Will replace anything with anything
   regardless of whether it's in a list, a map, a set or any of those
   nested one inside another. No pattern matching. Naive implementation.
   Is probably slow."
  [form sym replacement]
  (let [once-more (partial map #(recursive-replace % sym replacement))]
    (cond
      (map? form) (into {} (once-more form))
      (vector? form) (into [] (once-more form))
      (set? form) (into #{} (once-more form))
      (seq? form) (once-more form)
      (= form sym) replacement
      :else form)))

(defn replace-these
  "Wrapper around recursive-replace to accept multiple find-request
   tuples. Caveats of recursive-replace apply."
  [form & tuples]
  (if-not (empty? tuples)
    (recur
     (apply (partial recursive-replace form)
            (take 2 tuples))
     (drop 2 tuples))
    form))

(defn print-cont
  "print that doesn't return nil. useful to put in middle of data flows
   or on transducers when you want to see intermediate data passing
   between functions."
  [print-me]
  (clojure.pprint/pprint print-me)
  print-me)

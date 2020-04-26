(ns dataworks.common
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
            status? `(fn ~'[{:keys [status] :as params}]
                       (if (= ~'status :failure)
                         ~'params
                         (-> ~'params ~next-form)))]
        (recur (list status? x) (next forms))))))

(defn recursive-filter
  "recursively filters through nested sequences, applying the
   test 'test' to each sequence and if a function 'f' is
   specified, applyed to that sequence, returning the result of
   f as though you had done (map f (filter test s))."
  ([test f s]
   (if (coll? s)
     (if (test s)
       (concat '()
               (f s)
               (apply concat
                      (map (partial recursive-filter test f)
                           s)))
       (apply concat
              (map (partial recursive-filter test f)
                   s)))
     '()))
  ([test s]
   (recursive-filter test #(conj '() %) s)))

(defn get-names
  "searches through a seq 's' for sequences starting with a
   symbol 'sym' then gets the second value of the sequence.
   (in the name of )
   returns results as a list.

   input: (get-defs 'defn
                    '(defn a
                       [x]
                       (defn b
                         [y]
                         (inc y))
                       (b a)))

  output: '(a b)

  Why would you want this? Mainly in the case of nested
  transformer blocks to get all the stored functions a stored
  function depends upon."
  [sym s]
  (recursive-filter
   #(= (first %) sym) ;; the test function
   second ;; the function applied to a tested sequenct
   s)) ;; the sequence itself

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
     (string/replace
      (stringify-keyword param)
      #"/" ".")
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

(defmacro if-vector-first
  "Input: params
   Output: (function params)
   OR
   Input: [params & others]
   Output [(function params) & others]
   Requires functions to be named functions. Naming the function
   using (let [function expression] (if-vector-first ...)) seems
   to work.
   Uses the validation macro (->?), so if your function returns
   a map with {:status :failure} it will return the map instead."
  [params function expression]
  `(if (vector? ~params)
     (let [~'new (first ~params)
           ~'others (rest ~params)
           ~'my-conj #(into [] (conj ~'others %))]
       (->? ~'new
            ~function
            ~'my-conj))
     ~expression))

(defmacro if-vector-conj
  "Input: params
   Output: [params (function params)]
   OR
   Input: [params & others]
   Output [params other-1 ... other-n (function params)]
   Relies on variable capture to substitute the first of the
   parameter vector for the parameters field in your function.
   Uses the validation macro (->?), so if your function returns
   a map with {:status :failure} it will return the map instead."
  [params quoted-param-variable expression]
  `(let [~'plist (if (vector? ~params)
                   ~params
                   [~params])
         ~(symbol quoted-param-variable)
         (if (vector? ~params)
           (first ~params)
           ~params)
         ~'my-conj #(into []
                          (reverse
                           (conj
                            (reverse
                             ~'plist)
                            %)))]
     (->? ~expression
          ~'my-conj)))

(defn vec-ify
  [maybe-coll]
  (if (coll? maybe-coll)
    (into [] maybe-coll)
    [maybe-coll]))

(defn blank-field?
  "Checks to see whether the specified parameters are blank
   or nil. m must be a map. fields must be keywords. Values
   must be strings"
  ([m & fields]
   (loop [fields fields]
     (if fields
       (let [field (first fields)]
         (println "Checking for blank:" field)
         (if (string/blank? (get-in m (vec-ify field)))
           {:status :failure
            :message (generate-message
                      field "%-cannot-be-blank")}
           (recur (next fields))))
       m))))

(defn missing-field?
  "Checks to see whether the specified parameters are missing.
   m must be a map. fields must be keywords."
  ([m & fields]
   (loop [fields fields]
     (if fields
       (let [field (first fields)]
         (println "Checking for null:" field)
         (if (nil? (get-in m (vec-ify field)))
           {:status :failure
            :message (generate-message
                      field "%-must-have-a-value")}
           (recur (next fields))))
       m))))

(defn empty-field-collection?
  "Checks to see whether the specified paramaters are empty,
   and whether they are collections. m must be a map.
   fields must be keywords"
  ([m & fields]
   (loop [fields fields]
     (if fields
       (let [field (first fields)]
         (println "Checking for empty collection:" field)
         (if-let [val (get-in m (vec-ify field))]
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
  [{:keys [name] :as params}]
  (println "validating name" name)
  (cond (not (string? name))
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
        :else params))

(defn parseable?
  "Checks whether a string is parseable to edn.
   Doesn't eval. Accepts time-literals."
  [{:keys [name] :as params} key]
  (println "Checking" key "parseability: " name)
  (if-let [value (get params key)]
    (try (assoc params key (read-string value))
         (catch Exception e
           {:status :failure
            :message (generate-message
                      key "unable-to-parse-%")
            :details (.getMessage e)}))
    params))

(defn updating-correct-function?
  "Checks name param against provided param."
  [{:keys [name] :as params} path-name]
  (println "Checking for correct function: "
           path-name "," name)
  (if name
    (if (= name path-name)
      params
      {:status :failure
       :message :name-param-does-not-match-path
       :details
       (str "We don't let you rename stored functions.\n"
            "If it's changed enough to be renamed, "
            "it's a different function at that point\n"
            "Best thing is to retire the old function "
            "and create a new one.")})
    (assoc params :name path-name)))

(defn has-params?
  "Checks for presence of params in new function. If absent,
   it adds them from the current function. function-type and
   params must be keywords."
  [[params current-function] function-type & params?]
  (loop [params? params?
         input [params current-function]]

    (if (and params?
             (not= (:status input) :failure))
      (recur (next params?)
             (let [param (first params?)]
               (println "Checking for" param)
               (if-not (get params param)
                 [(assoc params
                         param
                         ((keyword
                           (stringify-keyword function-type)
                           param)
                          current-function))
                  current-function]
                 [params current-function])))
      input)))

(defn has-parsed-params?
  "Checks for presence of params that are meant to be parsed
   in new function. If present, it parses them. If absent,
   it adds them from the current function. function-type and
   params must be keywords."
  [[params current-function] function-type & params?]
  (loop [params? params?
         input [params current-function]]
    (if (and params?
             (not= (:status input) :failure))
      (recur (next params?)
             (let [param (first params?)]
               (println "Checking for" param)
               (if-not (get params param)
                 [(assoc params param
                         ((keyword
                           (stringify-keyword function-type)
                           param)
                          current-function))
                  current-function]
                 (let [parsed (parseable? params param)
                       not-parsed (:status parsed)]
                   (if (= not-parsed :failure)
                     not-parsed
                     [parsed current-function])))))
      input)))

(defn valid-update?
  "Checks if any of the params? have been changed from
   the current stored function. If they have, then we
   return the params map. If they haven't, then we
   return the failure map"
  [[params current-function] function-type & params?]
  (println "Checking if valid update.")
  (if (every? #(= (get params %)
                  (get current-function
                       (get-entity-param % function-type)))
              params?)
    {:status :failure
     :message (generate-message function-type
                                "no-change-from-existing-%")}
    [params current-function]))

(defn function?
  "Checks if function is function."
  [function]
  (if (fn? function)
    function
    {:status :failure
     :message :function-param-does-not-evaluate-to-function}))

;; Thanks whocaresanyway on stack exchange.
(defn arg-count [f]
  (let [m (first (.getDeclaredMethods (class f)))
        p (.getParameterTypes m)]
    (alength p)))

(defn one-arg? [function]
  (if (= 1
         (arg-count function))
    function
    {:status :failure
     :message :function-param-must-have-single-arg}))

(defn if-assoc
  [map & kvs]
  (loop [map map
         kvs kvs]
    (let [return (if (second kvs)
                   (apply assoc map (take 2 kvs))
                   map)]
      (if kvs
        (recur return
               (next (next kvs)))
        return))))

(defn if-conj
  [coll & values]
  (loop [coll coll
         values (reverse values)]
    (if values
      (if (first values)
        (recur (conj coll (first values))
               (next values))
        (recur coll
               (next values)))
      coll)))

(defn dependencies?
  [params function-type]
  (if-vector-first params
                   dependencies?
                   (let [dependencies (into #{}
                                            (map keyword)
                                            (get-names
                                             'transformers
                                             ((get-entity-param
                                               :function
                                               function-type)
                                              params)))]
                     (if-not (empty? dependencies)
                       (assoc params
                              :stored-function/dependencies
                              dependencies)
                       params))))

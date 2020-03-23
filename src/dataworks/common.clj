(ns dataworks.common
  (:require
   [clojure.string :as string]
   [crux.api :as crux]
   [dataworks.db.app-db :refer [app-db]]))

(defmacro ->?
  "A utility function for validation of data and transactions
   Like the threading macro (->), except after each function,
   it looks to see whether the function returned a map with
   the key :status and the value :faiure. If the function did
   return such a map, then the macro returns that same map,
   other wise the macro passes the result to the next function
   via the threading macro (->)."
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

(defn read-string
  "read-string that reads time-literals and, importantly,
   doesn't evaluate what it reads."
  [string]
  (clojure.edn/read-string
   {:readers time-literals.read-write/tags}
   string))

(defn stringify-keyword
  "Input:    :key
   Output:    \"key\""
  [key]
  (string/replace (str key) #":" ""))

(defn get-entity-param
  [param function-type]
  (keyword
   (stringify-keyword function-type)
   param))

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

(defn blank-field?
  "Checks to see whether the specified parameters are blank
   or nil. m must be a map. fields must be keywords."
  ([m & fields]
   (loop [fields fields]
     (if fields
       (let [field (first fields)]
         (println "Checking for blank:" field)
         (if (string/blank? (field m))
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
        (if (nil? (field m))
         {:status :failure
          :message (generate-message
                    field "%-must-have-a-value" )}
         (recur (next fields))) )
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
  "Checks whether a string is parseable to edn. Doesn't eval.
   Accepts time-literals."
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

(defn get-stored-function
  ([eid]
   (let [db (crux/db app-db)]
     (crux/entity db eid)))
  ([name function-type]
   (get-stored-function
    (get-entity-param name function-type))))

(defn get-stored-functions
  ([]
   (map (comp get-stored-function first)
        (crux/q (crux/db app-db)
                {:find '[e]
                 :where [['e :stored-function/type]]})))
  ([function-type]
   (map (comp get-stored-function first)
        (crux/q (crux/db app-db)
                {:find '[e]
                 :where [['e :stored-function/type
                          function-type]]}))))

(defn function-already-exists?
  [{:keys [name] :as params} function-type]
  (println "checking for duplicate" name)
  (if (get-stored-function name function-type)
    {:status :failure
     :message (generate-message function-type
                                "%-already-exists")}
    params))

(defn updating-correct-function?
  [{:keys [name] :as params} path-name]
  (println "Checking for correct function: "
           path-name "," name)
  (println params)
  (println path-name)
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

(defn add-current-stored-function
  "Takes the map received by the endpoints for creation
   and modification of stored functions and returns a
   vector containing that map, as well as a map of the
   current stored function. Useful for threading through
   functions which require both the new stored function
   and the current stored function for comparison."
  [{:keys [name] :as params} function-type]
  (println "Adding current" function-type ":" name ".")
  [params (get-stored-function name function-type)])

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
               (println "Checking for" param ".")
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
               (println "Checking for" param ".")
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
                     [parsed current-function]))) ))
      input)))

(defn general-valid-update?
  "Checks if any of the params? have been changed from
   the current stored function. If they have, then we
   return the params map. If they haven't, then we
   return the failure map"
  [[params current-function] function-type & params?]
  (println "Checking if valid update.")
  (if (every? #(= (% params)
                  ((get-entity-param % function-type)
                   current-function))
              params?)
    {:status :failure
     :message (generate-message function-type
                                "no-change-from-existing-%")}
    params))

(defn general-added-to-db?
  [params db-fy]
  (println "adding-to-db")
  (let [db-fn (db-fy (first params))
        success [db-fn (last params)]
        failure {:status :failure
                 :message :db-failed-to-update}]
    (if (let [tx (cond (> 3 (count params))
                       [:crux.tx/put db-fn]
                       :else
                       [:crux.tx/cas db-fn
                        (second params)])]
          (println tx)
          (crux/submit-tx app-db [tx]))
      success
      failure)))

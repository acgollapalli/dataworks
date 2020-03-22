(ns dataworks.common
  (:require
   [clojure.string :as string]))

(defmacro ->?
  "A utility function for validation of data and transactions"
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
  "Read String that reads time-literals"
  [string]
  (clojure.edn/read-string {:readers time-literals.read-write/tags} string))


(defn blank-field?
  ([m & fields]
   (loop [fields fields]
     (if fields
       (do
         (println "Checking for blank:" (first fields))
         (if (string/blank? ((first fields) m))
           {:status :failure
            :message (keyword (string/replace (str (first fields) "-cannot be blank") #":" ""))})
         (recur (next fields)))
       m))))

(defn missing-field?
  ([m & fields]
   (loop [fields fields]
     (println "Checking for null:" (first fields))
     (if fields
       (if (nil? ((first fields) m))
         {:status :failure
          :message (keyword (string/replace (str (first fields) "-must-have-a-value") #":" ""))}
         (recur (next fields)))
       m))))

(defn valid-name? [{:keys [name] :as params}]
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

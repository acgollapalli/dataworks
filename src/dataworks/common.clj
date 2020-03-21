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

(defn blank-field?
  ([m & fields]
   (loop [fs fields]
     (if fields
       (if (string/blank? ((first fields) m))
         {:status :failure
          :message (keyword (string/replace (str (first fields) "-cannot be blank") #":" ""))}
         (recur (next fs)))))))

(defn missing-field?
  ([m & fields]
   (loop [fs fields]
     (if fields
       (if (nil? ((first fields) m))
         {:status :failure
          :message (keyword (string/replace (str (first fields) "-must-have-a-value") #":" ""))}
         (recur (next fs)))))))

(defn valid-name? [{:keys [name] :as params}]
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

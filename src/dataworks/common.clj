(ns dataworks.common)

(defmacro ->? [x & forms]
  "A utility function for validation of data and transactions"
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
       (if (blank? ((first fields) m))
         {:status :failure
          :message (keyword (next (str (first fields) "-cannot be blank")))}
         (recur (next fs)))))))

(defn missing-field?
  ([m & fields]
   (loop [fs fields]
     (if fields
       (if (blank? ((first fields) m))
         {:status :failure
          :message (keyword (next (str (first fields) "-must-have-a-value")))}
         (recur (next fs)))))))

(defn valid-name? [{:keys [name] :as params}]
  (cond (not (string? name))
        {:status :failure
         :message :name-must-be-string}
        (clojure.string/include? name ":")
        {:status :failure
         :message :name-cannot-include-colon}
        (clojure.string/starts-with? name "/")
        {:status :failure
         :message :name-cannot-start-with-slash}
        (clojure.string/includes? name " ")
        {:status :failure
         :message :name-cannot-include-whitespace}
        :else params))

(ns calculator-server
  (:require [io.modelcontext.cljc-sdk.server :as server]
            [io.modelcontext.cljc-sdk.transport.stdio :as stdio]
            [me.vedang.logger.interface :as log]))

(defn validate-array
  "Helper function to validate array inputs"
  [arr]
  (when-not (and (sequential? arr) (every? number? arr))
    (throw (ex-info "Invalid input: Expected array of numbers" {:input arr}))))

(def calculator-server-spec
  {:name "calculator",
   :version "1.0.0",
   :tools
   [;; Basic arithmetic operations
    {:name "add",
     :description "Add two numbers together",
     :schema {:type "object",
              :properties {"a" {:type "number", :description "First number"},
                           "b" {:type "number", :description "Second number"}},
              :required ["a" "b"]},
     :handler (fn [{:keys [a b]}] {:type "text", :text (str (+ a b))})}
    {:name "subtract",
     :description "Subtract second number from first",
     :schema {:type "object",
              :properties {"a" {:type "number", :description "First number"},
                           "b" {:type "number", :description "Second number"}},
              :required ["a" "b"]},
     :handler (fn [{:keys [a b]}] {:type "text", :text (str (- a b))})}
    {:name "multiply",
     :description "Multiply two numbers",
     :schema {:type "object",
              :properties {"a" {:type "number", :description "First number"},
                           "b" {:type "number", :description "Second number"}},
              :required ["a" "b"]},
     :handler (fn [{:keys [a b]}] {:type "text", :text (str (* a b))})}
    {:name "divide",
     :description "Divide first number by second",
     :schema {:type "object",
              :properties {"a" {:type "number", :description "First number"},
                           "b" {:type "number",
                                :description "Second number (non-zero)"}},
              :required ["a" "b"]},
     :handler
     (fn [{:keys [a b]}]
       (if (zero? b)
         {:type "text", :text "Error: Cannot divide by zero", :is-error true}
         {:type "text", :text (str (/ a b))}))}
    ;; Advanced mathematical operations
    {:name "power",
     :description "Raise a number to a power",
     :schema {:type "object",
              :properties {"base" {:type "number", :description "Base number"},
                           "exponent" {:type "number",
                                       :description "Exponent"}},
              :required ["base" "exponent"]},
     :handler (fn [{:keys [base exponent]}]
                (try {:type "text", :text (str (Math/pow base exponent))}
                     (catch Exception e
                       {:type "text",
                        :text (str "Error calculating power: " (.getMessage e)),
                        :is-error true})))}
    {:name "square-root",
     :description "Calculate the square root of a number",
     :schema {:type "object",
              :properties {"number" {:type "number",
                                     :description
                                     "Number to find square root of"}},
              :required ["number"]},
     :handler (fn [{:keys [number]}]
                (if (neg? number)
                  {:type "text",
                   :text
                   "Error: Cannot calculate square root of negative number",
                   :is-error true}
                  {:type "text", :text (str (Math/sqrt number))}))}
    ;; Array operations to test complex inputs
    {:name "sum-array",
     :description "Calculate the sum of an array of numbers",
     :schema {:type "object",
              :properties {"numbers" {:type "array",
                                      :items {:type "number"},
                                      :description "Array of numbers to sum"}},
              :required ["numbers"]},
     :handler (fn [{:keys [numbers]}]
                (try (validate-array numbers)
                     {:type "text", :text (str (reduce + numbers))}
                     (catch Exception e
                       {:type "text",
                        :text (str "Error: " (.getMessage e)),
                        :is-error true})))}
    {:name "average",
     :description "Calculate the average of an array of numbers",
     :schema {:type "object",
              :properties {"numbers" {:type "array",
                                      :items {:type "number"},
                                      :description
                                      "Array of numbers to average"}},
              :required ["numbers"]},
     :handler (fn [{:keys [numbers]}]
                (try (validate-array numbers)
                     (if (empty? numbers)
                       {:type "text",
                        :text "Error: Cannot calculate average of empty array",
                        :is-error true}
                       {:type "text",
                        :text (str (double (/ (reduce + numbers)
                                              (count numbers))))})
                     (catch Exception e
                       {:type "text",
                        :text (str "Error: " (.getMessage e)),
                        :is-error true})))}
    ;; Test long-running operation
    {:name "factorial",
     :description
     "Calculate the factorial of a number (demonstrates longer computation)",
     :schema {:type "object",
              :properties {"number" {:type "number",
                                     :description
                                     "Number to calculate factorial of"}},
              :required ["number"]},
     :handler
     (fn [{:keys [number]}]
       (if (or (neg? number) (not (integer? number)))
         {:type "text",
          :text "Error: Factorial requires a non-negative integer",
          :is-error true}
         (try
           ;; Simulate longer computation for large numbers
           (when (> number 10) (Thread/sleep 1000))
           {:type "text", :text (str (reduce * (range 1 (inc number))))}
           (catch Exception e
             {:type "text",
              :text (str "Error calculating factorial: " (.getMessage e)),
              :is-error true}))))}]})

(defn -main
  [& _args]
  (log/info :msg "Starting calculator server")
  (let [transport (stdio/create-stdio-transport)]
    (-> calculator-server-spec
        server/make-server
        (server/start! transport))
    (log/info :msg "Calculator server started")
    @(promise))) ; Keep the server running

(when (= *file* (System/getProperty "babashka.file")) (-main))

(comment
  ;; Test error handling
  "What's the square root of -4?"
  "Calculate the factorial of -1"
    ;; Test overflow
    "What's 2 to the power of 1000?"
  ;; Test array operations
  "What's the average of [1, 2, 3, 4, 5]?"
    "Sum up the numbers [10, 20, 30, 40, 50]"
  "Calculate the average of []" ; Test empty array
    ;; Test long computation
    "What's the factorial of 15?" ; Should take > 1 second
)

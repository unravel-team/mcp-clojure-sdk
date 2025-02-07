(ns examples.calculator-server
  (:require [io.modelcontext.cljc-sdk.server :as server]
            [io.modelcontext.cljc-sdk.transport.stdio :as stdio]
            [me.vedang.logger.interface :as log]))

(def calculator-server-spec
  {:name "calculator",
   :version "1.0.0",
   :tools
   [{:name "add",
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
         {:type "text", :text (str (/ a b))}))}]})

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

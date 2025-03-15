(ns code-analysis-server
  (:gen-class)
  (:require [io.modelcontext.clojure-sdk.stdio-server :as io-server]
            [me.vedang.logger.interface :as log]))

(def prompt-analyze-code
  {:name "analyze-code",
   :description "Analyze code for potential improvements",
   :arguments
   [{:name "language", :description "Programming language", :required true}
    {:name "code", :description "The code to analyze", :required true}],
   :handler (fn analyze-code [args]
              {:messages [{:role "assistant",
                           :content
                           {:type "text",
                            :text (str "Analysis of "
                                       (:language args)
                                       " code:\n"
                                       "Here are potential improvements for:\n"
                                       (:code args))}}]})})

(def prompt-poem-about-code
  {:name "poem-about-code",
   :description "Write a poem describing what this code does",
   :arguments
   [{:name "poetry_type",
     :description
     "The style in which to write the poetry: sonnet, limerick, haiku",
     :required true}
    {:name "code",
     :description "The code to write poetry about",
     :required true}],
   :handler (fn [args]
              {:messages [{:role "assistant",
                           :content {:type "text",
                                     :text (str "Write a " (:poetry_type args)
                                                " Poem about:\n" (:code
                                                                   args))}}]})})

(def code-analysis-server-spec
  {:name "code-analysis",
   :version "1.0.0",
   :prompts [prompt-analyze-code prompt-poem-about-code]})

(defn -main
  [& _args]
  (let [server-id (random-uuid)]
    (log/debug "[MAIN] Starting code analysis server: " server-id)
    @(io-server/run! (assoc code-analysis-server-spec :server-id server-id))))

(comment
  ;; Test power / maybe overflow
  "What's 2 to the power of 1000?"
  ;; Test array operations
  "What's the average of [1, 2, 3, 4, 5]?"
    "Sum up the numbers [10, 20, 30, 40, 50]"
  "Calculate the average of []" ; Test empty array
    ;; Test long computation
    "What's the factorial of 15?" ; Should take > 1 second
  ;; Test error handling
  "What's the square root of -4? Use the square-root tool"
    "Calculate the factorial of -1")

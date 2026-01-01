(ns integration.prompts-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [integration.fixture :as fixture]
            [integration.mcp :as mcp]))

(mcp/clean-after-test)

(defn- initialize!
  []
  (let [response (mcp/request! (fixture/initialize-request))]
    (mcp/notify! (fixture/initialized-notification))
    response))

(deftest prompts
  (mcp/start-process!)
  (testing "prompts/list and prompts/get"
    (let [init-response (initialize!)
          server-name (get-in init-response [:serverInfo :name])]
      (when (= "code-analysis" server-name)
        (let [prompts-response (mcp/request! (fixture/list-prompts-request))
              prompt-names (set (map :name (:prompts prompts-response)))]
          (is (= #{"analyze-code" "poem-about-code"} prompt-names)))
        (let [prompt-response (mcp/request! (fixture/get-prompt-request
                                              "analyze-code"
                                              {:language "Clojure",
                                               :code "(+ 1 2)"}))
              text (get-in prompt-response [:messages 0 :content :text])]
          (is (string/includes? text "Analysis of Clojure")))))))

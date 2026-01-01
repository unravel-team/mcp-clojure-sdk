(ns integration.tools-test
  (:require [clojure.test :refer [deftest is testing]]
            [integration.fixture :as fixture]
            [integration.mcp :as mcp]))

(mcp/clean-after-test)

(def tools-by-server
  {"calculator" #{"add" "subtract" "multiply" "divide" "power" "square-root"
                  "sum-array" "average" "factorial"},
   "vegalite" #{"save-data" "visualize-data"}})

(defn- initialize!
  []
  (let [response (mcp/request! (fixture/initialize-request))]
    (mcp/notify! (fixture/initialized-notification))
    response))

(deftest tools
  (mcp/start-process!)
  (testing "tools/list and tools/call"
    (let [init-response (initialize!)
          server-name (get-in init-response [:serverInfo :name])
          expected-tools (get tools-by-server server-name)]
      (when expected-tools
        (let [tools-response (mcp/request! (fixture/list-tools-request))
              tool-names (set (map :name (:tools tools-response)))]
          (is (= expected-tools tool-names)))
        (case server-name
          "calculator" (let [result (mcp/request! (fixture/call-tool-request
                                                    "add"
                                                    {:a 1, :b 2}))]
                         (is (= "3"
                                (-> result
                                    :content
                                    first
                                    :text))))
          "vegalite" (let [result (mcp/request! (fixture/call-tool-request
                                                  "save-data"
                                                  {:name "sample",
                                                   :data [{:x 1, :y 2}]}))]
                       (is (= "Data saved to table 'sample'"
                              (-> result
                                  :content
                                  first
                                  :text))))
          nil)))))

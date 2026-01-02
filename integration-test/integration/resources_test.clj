(ns integration.resources-test
  (:require [clojure.test :refer [deftest is testing]]
            [integration.fixture :as fixture]
            [integration.mcp :as mcp]))

(mcp/clean-after-test)

(defn- initialize!
  []
  (let [response (mcp/request! (fixture/initialize-request))]
    (mcp/notify! (fixture/initialized-notification))
    response))

(deftest resources
  (mcp/start-process!)
  (testing "resources/list and resources/read"
    (let [init-response (initialize!)
          server-name (get-in init-response [:serverInfo :name])]
      (cond
        (= "calculator" server-name)
        (let [resources-response (mcp/request! (fixture/list-resources-request))
              resource-uris (set (map :uri (:resources resources-response)))
              read-response (mcp/request! (fixture/read-resource-request
                                            "resource://constants"))
              content (-> read-response :contents first)]
          (is (contains? resource-uris "resource://constants"))
          (is (= "resource://constants" (:uri content)))
          (is (= "application/json" (:mimeType content)))
          (is (= "{\"pi\":3.14159}" (:text content))))

        (#{"python-echo-server" "typescript-echo-server"} server-name)
        (let [resources-response (mcp/request! (fixture/list-resources-request))
              resource-uris (set (map :uri (:resources resources-response)))
              read-response (mcp/request! (fixture/read-resource-request
                                            "test://greeting"))
              content (-> read-response :contents first)
              greeting (case server-name
                         "python-echo-server" "Hello from Python MCP server!"
                         "Hello from TypeScript MCP server!")]
          (is (contains? resource-uris "test://greeting"))
          (is (= "test://greeting" (:uri content)))
          (is (= "text/plain" (:mimeType content)))
          (is (= greeting (:text content))))))))

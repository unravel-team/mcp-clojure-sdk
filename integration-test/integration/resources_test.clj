(ns integration.resources-test
  (:require
   [clojure.test :refer [deftest is testing]]
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
      (when (= "calculator" server-name)
        (let [resources-response (mcp/request! (fixture/list-resources-request))
              resource-uris (set (map :uri (:resources resources-response)))]
          (is (contains? resource-uris "resource://constants")))
        (let [read-response (mcp/request!
                              (fixture/read-resource-request
                                "resource://constants"))
              content (-> read-response :contents first)]
          (is (= "resource://constants" (:uri content)))
          (is (= "application/json" (:mimeType content)))
          (is (= "{\"pi\":3.14159}" (:text content))))))))

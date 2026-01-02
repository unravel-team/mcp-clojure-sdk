(ns integration.initialize-test
  (:require [clojure.test :refer [deftest is testing]]
            [integration.fixture :as fixture]
            [integration.mcp :as mcp]))

(mcp/clean-after-test)

(deftest initialize
  (mcp/start-process!)
  (testing "initialize request"
    (let [response (mcp/request! (fixture/initialize-request))]
      (is (string? (:protocolVersion response)))
      (is (map? (:capabilities response)))
      (is (string? (get-in response [:serverInfo :name])))
      (is (string? (get-in response [:serverInfo :version])))))
  (testing "initialized notification"
    (mcp/notify! (fixture/initialized-notification)))
  (testing "ping request"
    (let [response (mcp/request! (fixture/ping-request))]
      (is (or (= "pong" response) (= {} response))))))

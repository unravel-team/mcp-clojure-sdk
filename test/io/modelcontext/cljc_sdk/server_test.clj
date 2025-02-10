(ns io.modelcontext.cljc-sdk.server-test
  (:require [babashka.json :as json]
            [clojure.core.async :as a]
            [clojure.test :refer [deftest is testing]]
            [io.modelcontext.cljc-sdk.core :as core]
            [io.modelcontext.cljc-sdk.server :as server]
            [io.modelcontext.cljc-sdk.transport.stdio :as stdio]))

(deftest server-basic-functionality
  (testing "Server creation and tool registration"
    (let [server (server/make-server
                   {:name "test-server",
                    :version "1.0.0",
                    :tools [{:name "greet",
                             :description "Greet someone",
                             :schema {:type "object",
                                      :properties {"name" {:type "string"}}},
                             :handler (fn [{:keys [name]}]
                                        {:type "text",
                                         :text (str "Hello, " name "!")})}],
                    :prompts [],
                    :resources []})]
      (is (some? server))
      (testing "Tool listing"
        (let [tools (-> @(:tools server)
                        vals
                        first
                        :tool)]
          (is (= "greet" (:name tools)))
          (is (= "Greet someone" (:description tools)))))
      (testing "Tool execution"
        (let [handler (-> @(:tools server)
                          (get "greet")
                          :handler)
              result (handler {:name "World"})]
          (is (= {:type "text", :text "Hello, World!"} result)))))))

(deftest server-start-stop
  (testing "Starting and stopping the server successfully"
    (let [transport (stdio/create-stdio-transport)
          server (server/make-server
                   {:name "test-server",
                    :version "1.0.0",
                    :tools [{:name "greet",
                             :description "Greet someone",
                             :schema {:type "object",
                                      :properties {"name" {:type "string"}}},
                             :handler (fn [{:keys [name]}]
                                        {:type "text",
                                         :text (str "Hello, " name "!")})}],
                    :prompts [],
                    :resources []})]
      (try (server/start! server transport)
           ;; Here we'd typically simulate client requests through the
           ;; transport but we'll keep this simple for now
           (finally (server/stop! server))))))

;; Mock transport for testing
(defrecord MockTransport [sent-messages sent-ch received-ch running?]
  core/Transport
    (start! [this] (reset! running? true) this)
    (stop! [_this]
      (reset! running? false)
      (a/close! received-ch)
      (a/close! sent-ch))
    (send! [_this message]
      (when @running?
        (swap! sent-messages conj message)
        (a/put! sent-ch message)))
    (receive! [_this] (a/<!! received-ch)))

(defn create-mock-transport
  []
  (->MockTransport (atom []) (a/chan) (a/chan 1024) (atom false)))

(deftest server-initialization
  (testing "Server creation with basic configuration"
    (let [server (server/make-server {:name "test-server",
                                      :version "1.0.0",
                                      :tools [],
                                      :prompts [],
                                      :resources []})]
      (is (= "test-server" (:server-name server)))
      (is (= "1.0.0" (:server-version server)))
      (is (= {} @(:tools server)))
      (is (= {} @(:resources server)))
      (is (= {} @(:prompts server))))))

(deftest tool-registration
  (testing "Tool registration and validation"
    (let [server (server/create-server "test-server" "1.0.0")
          valid-tool {:name "test-tool",
                      :description "A test tool",
                      :schema {:type "object",
                               :properties {"arg" {:type "string"}}}}]
      (server/register-tool! server
                             (:name valid-tool)
                             (:description valid-tool)
                             (:schema valid-tool)
                             (fn [_] {:type "text", :text "success"}))
      (is (= 1 (count @(:tools server))))
      (is (get @(:tools server) "test-tool"))
      (testing "Tool validation"
        (is (thrown? Exception
                     (server/register-tool! server
                                            "invalid"
                                            "desc"
                                            {:invalid "schema"}
                                            identity)))))))

(deftest tool-execution
  (testing "Tool execution through protocol"
    (let [transport (create-mock-transport)
          server (server/make-server
                   {:name "test-server",
                    :version "1.0.0",
                    :tools [{:name "echo",
                             :description "Echo input",
                             :schema {:type "object",
                                      :properties {"message" {:type "string"}},
                                      :required ["message"]},
                             :handler (fn [{:keys [message]}]
                                        {:type "text", :text message})}]})
          _ (server/start! server transport)]
      ;; // use a/alts! in a a/go block here to wait on a timeout of 500 or
      (testing "Tool list request"
        (a/>!! (:received-ch transport)
               "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"tools/list\"}")
        (let [response (first @(:sent-messages transport))]
          (is (string? response))
          (is (-> response
                  json/read-str
                  :result
                  :tools
                  first
                  :name
                  (= "echo")))))
      (testing "Tool execution request"
        (a/>!!
          (:received-ch transport)
          "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"message\":\"test\"}}}")
        (let [response (second @(:sent-messages transport))]
          (is (string? response))
          (is (-> response
                  json/read-str
                  :result
                  :content
                  first
                  :text
                  (= "test")))))
      (testing "Invalid tool request"
        (a/>!!
          (:received-ch transport)
          "{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"method\":\"tools/call\",\"params\":{\"name\":\"invalid\"}}")
        (let [response (nth @(:sent-messages transport) 2)]
          (is (string? response))
          (is (-> response
                  json/read-str
                  :error
                  :code
                  (= -32603)))))
      (server/stop! server))))

(deftest server-lifecycle
  (testing "Server startup and shutdown"
    (let [transport (create-mock-transport)
          server (server/make-server
                   {:name "test-server", :version "1.0.0", :tools []})]
      (testing "Server start"
        (server/start! server transport)
        (is @(:running? transport)))
      (testing "Server stop"
        (server/stop! server)
        (is (not @(:running? transport)))))))

(deftest initialization-protocol
  (testing "Initialize request handling"
    (let [transport (create-mock-transport)
          server (server/make-server
                   {:name "test-server", :version "1.0.0", :tools []})
          _ (server/start! server transport)]
      (a/>!!
        (:received-ch transport)
        "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"DRAFT-2025-v1\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test-client\",\"version\":\"1.0.0\"}}}")
      (let [response (first @(:sent-messages transport))]
        (is (string? response))
        (is (re-find #"DRAFT-2025-v1" response))
        (is (re-find #"test-server" response)))
      (server/stop! server))))

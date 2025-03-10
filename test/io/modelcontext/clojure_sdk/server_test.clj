(ns io.modelcontext.clojure-sdk.server-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [io.modelcontext.clojure-sdk.server :as server]
            [io.modelcontext.clojure-sdk.test-helper :as h]
            [lsp4clj.lsp.requests :as lsp.requests]
            [lsp4clj.server :as lsp.server]
            [io.modelcontext.clojure-sdk.specs :as specs]))

;;; Tools
(def tool-greet
  {:name "greet",
   :description "Greet someone",
   :inputSchema {:type "object", :properties {"name" {:type "string"}}},
   :handler (fn [{:keys [name]}]
              {:type "text", :text (str "Hello, " name "!")})})

(def tool-echo
  {:name "echo",
   :description "Echo input",
   :inputSchema {:type "object",
                 :properties {"message" {:type "string"}},
                 :required ["message"]},
   :handler (fn [{:keys [message]}] {:type "text", :text message})})

;;; Prompts
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

;;; Resources
(def resource-test-json
  {:description "Test JSON data",
   :mimeType "application/json",
   :name "Test Data",
   :uri "file:///data.json",
   :handler
   (fn read-resource [uri]
     {:uri uri, :mimeType "application/json", :blob "Hello from Test Data"})})

(def resource-test-file
  {:description "A test file",
   :mimeType "text/plain",
   :name "Test File",
   :uri "file:///test.txt",
   :handler
   (fn read-resource [uri]
     {:uri uri, :mimeType "text/plain", :text "Hello from Test File"})})

;;; Tests

(deftest server-basic-functionality
  (testing "Server creation and tool registration"
    (let [context (server/create-context! {:name "test-server",
                                           :version "1.0.0",
                                           :tools [tool-greet],
                                           :prompts [],
                                           :resources []})]
      (testing "Tool listing"
        (let [tools (-> @(:tools context)
                        vals
                        first
                        :tool)]
          (is (= "greet" (:name tools)))
          (is (= "Greet someone" (:description tools)))))
      (testing "Tool execution"
        (let [handler (-> @(:tools context)
                          (get "greet")
                          :handler)
              result (handler {:name "World"})]
          (is (= {:type "text", :text "Hello, World!"} result))))))
  (testing "Server creation with basic configuration"
    (let [context (server/create-context! {:name "test-server",
                                           :version "1.0.0",
                                           :tools [],
                                           :prompts [],
                                           :resources []})]
      (is (= "test-server" (get-in context [:server-info :name])))
      (is (= "1.0.0" (get-in context [:server-info :version])))
      (is (= {} @(:tools context)))
      (is (= {} @(:resources context)))
      (is (= {} @(:prompts context))))))

(deftest tool-registration
  (testing "Tool registration and validation"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0", :tools []})]
      (server/register-tool!
        context
        {:name "test-tool",
         :description "A test tool",
         :inputSchema {:type "object", :properties {"arg" {:type "string"}}}}
        (fn [_] {:type "text", :text "success"}))
      (is (= 1 (count @(:tools context))))
      (is (get @(:tools context) "test-tool"))
      (testing "Tool validation"
        (is (thrown? Exception
                     (server/register-tool! context
                                            {:name "invalid",
                                             :description "desc",
                                             :inputSchema {:invalid "schema"}}
                                            identity)))))))

(deftest initialization
  (testing "Connection initialization through initialize"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0", :tools [tool-echo]})
          server (server/chan-server)
          _join (server/start! server context)]
      (testing "Client initialization"
        (async/put! (:input-ch server)
                    (lsp.requests/request
                      1
                      "initialize"
                      {:protocolVersion "2024-11-05",
                       :capabilities {:roots {:listChanged true}, :sampling {}},
                       :clientInfo {:name "ExampleClient", :version "1.0.0"}}))
        (is (= {:jsonrpc specs/jsonrpc-version,
                :id 1,
                :result {:protocolVersion specs/stable-protocol-version,
                         :capabilities {:tools {}, :resources {}, :prompts {}},
                         :serverInfo {:name "test-server", :version "1.0.0"}}}
               (h/take-or-timeout (:output-ch server) 200))))
      (lsp.server/shutdown server))))

(deftest tool-execution
  (testing "Tool execution through protocol"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0", :tools [tool-echo]})
          server (server/chan-server)
          _join (server/start! server context)]
      (testing "Tool list request"
        (async/put! (:input-ch server) (lsp.requests/request 1 "tools/list" {}))
        (is (= {:jsonrpc specs/jsonrpc-version,
                :id 1,
                :result {:tools [{:name "echo",
                                  :description "Echo input",
                                  :inputSchema {:type "object",
                                                :properties {"message"
                                                             {:type "string"}},
                                                :required ["message"]}}]}}
               (h/assert-take (:output-ch server)))))
      (testing "Tool execution request"
        (async/put! (:input-ch server)
                    (lsp.requests/request 2
                                          "tools/call"
                                          {:name "echo",
                                           :arguments {:message "test"}}))
        (is (= {:jsonrpc specs/jsonrpc-version,
                :id 2,
                :result {:content [{:type "text", :text "test"}]}}
               (h/assert-take (:output-ch server)))))
      (testing "Invalid tool request"
        (async/put! (:input-ch server)
                    (lsp.requests/request 3 "tools/call" {:name "invalid"}))
        (is (= {:jsonrpc specs/jsonrpc-version,
                :id 3,
                :error {:code specs/method-not-found,
                        :message "Tool Not Found!",
                        :data {:tool-name "invalid"}}}
               (h/assert-take (:output-ch server)))))
      (lsp.server/shutdown server))))

(deftest prompt-listing
  (testing "Listing available prompts"
    (let [context (server/create-context! {:name "test-server",
                                           :version "1.0.0",
                                           :tools [],
                                           :prompts [prompt-analyze-code
                                                     prompt-poem-about-code]})
          server (server/chan-server)
          _join (server/start! server context)]
      (testing "Prompts list request"
        (async/put! (:input-ch server)
                    (lsp.requests/request 1 "prompts/list" {}))
        (let [response (h/assert-take (:output-ch server))]
          (is (= 2 (count (:prompts (:result response)))))
          (let [analyze (first (:prompts (:result response)))
                poem (second (:prompts (:result response)))]
            (is (= "analyze-code" (:name analyze)))
            (is (= "Analyze code for potential improvements"
                   (:description analyze)))
            (is (= [{:name "language",
                     :description "Programming language",
                     :required true}
                    {:name "code",
                     :description "The code to analyze",
                     :required true}]
                   (:arguments analyze)))
            (is (= "poem-about-code" (:name poem)))
            (is (= "Write a poem describing what this code does"
                   (:description poem)))
            (is
              (=
                [{:name "poetry_type",
                  :description
                  "The style in which to write the poetry: sonnet, limerick, haiku",
                  :required true}
                 {:name "code",
                  :description "The code to write poetry about",
                  :required true}]
                (:arguments poem))))))
      (lsp.server/shutdown server))))

(deftest prompt-getting
  (testing "Getting specific prompts"
    (let [server (server/chan-server)
          context (server/create-context! {:name "test-server",
                                           :version "1.0.0",
                                           :tools [],
                                           :prompts [prompt-analyze-code
                                                     prompt-poem-about-code]})
          _join (server/start! server context)]
      (testing "Get analyze-code prompt"
        (async/put! (:input-ch server)
                    (lsp.requests/request 1
                                          "prompts/get"
                                          {:name "analyze-code",
                                           :arguments {:language "Clojure",
                                                       :code "(defn foo [])"}}))
        (let [response (h/assert-take (:output-ch server))
              result (:result response)]
          (is (= 1 (count (:messages result))))
          (is
            (=
              "Analysis of Clojure code:\nHere are potential improvements for:\n(defn foo [])"
              (-> result
                  :messages
                  first
                  :content
                  :text)))))
      (testing "Get poem-about-code prompt"
        (async/put! (:input-ch server)
                    (lsp.requests/request 2
                                          "prompts/get"
                                          {:name "poem-about-code",
                                           :arguments {:poetry_type "haiku",
                                                       :code "(defn foo [])"}}))
        (let [response (h/assert-take (:output-ch server))
              result (:result response)]
          (is (= 1 (count (:messages result))))
          (is (= "Write a haiku Poem about:\n(defn foo [])"
                 (-> result
                     :messages
                     first
                     :content
                     :text)))))
      (testing "Invalid prompt request"
        (async/put!
          (:input-ch server)
          (lsp.requests/request 3 "prompts/get" {:name "invalid-prompt"}))
        (let [response (h/assert-take (:output-ch server))
              error (:error response)]
          (is (= specs/method-not-found (:code error)))
          (is (= "Prompt Not Found!" (:message error)))))
      (lsp.server/shutdown server))))

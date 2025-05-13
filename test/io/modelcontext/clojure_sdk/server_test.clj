(ns io.modelcontext.clojure-sdk.server-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [io.modelcontext.clojure-sdk.mcp.errors :as mcp.errors]
            [io.modelcontext.clojure-sdk.server :as server]
            [io.modelcontext.clojure-sdk.specs :as specs]
            [io.modelcontext.clojure-sdk.test-helper :as h]
            [lsp4clj.lsp.requests :as lsp.requests]
            [lsp4clj.lsp.responses :as lsp.responses]
            [lsp4clj.server :as lsp.server]))

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
      (is (get @(:tools context) "test-tool")))))

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
        (is (= (lsp.responses/response
                 1
                 {:protocolVersion specs/stable-protocol-version,
                  :capabilities {:tools {}, :resources {}, :prompts {}},
                  :serverInfo {:name "test-server", :version "1.0.0"}})
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
        (is (= (lsp.responses/response 1
                                       {:tools [{:name "echo",
                                                 :description "Echo input",
                                                 :inputSchema
                                                 {:type "object",
                                                  :properties
                                                  {"message" {:type "string"}},
                                                  :required ["message"]}}]})
               (h/assert-take (:output-ch server)))))
      (testing "Tool execution request"
        (async/put! (:input-ch server)
                    (lsp.requests/request 2
                                          "tools/call"
                                          {:name "echo",
                                           :arguments {:message "test"}}))
        (is (= (lsp.responses/response 2
                                       {:content [{:type "text",
                                                   :text "test"}]})
               (h/assert-take (:output-ch server)))))
      (testing "Invalid tool request"
        (async/put! (:input-ch server)
                    (lsp.requests/request 3 "tools/call" {:name "invalid"}))
        (is (= (lsp.responses/error (lsp.responses/response 3)
                                    (mcp.errors/body :tool-not-found
                                                     {:tool-name "invalid"}))
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
        (is (= (lsp.responses/error (lsp.responses/response 3)
                                    (mcp.errors/body :prompt-not-found
                                                     {:prompt-name
                                                      "invalid-prompt"}))
               (h/assert-take (:output-ch server)))))
      (lsp.server/shutdown server))))

(deftest resource-listing
  (testing "Listing available resources"
    (let [server (server/chan-server)
          context (server/create-context! {:name "test-server",
                                           :version "1.0.0",
                                           :tools [],
                                           :prompts [],
                                           :resources [resource-test-file
                                                       resource-test-json]})
          _join (server/start! server context)]
      (testing "Resources list request"
        (async/put! (:input-ch server)
                    (lsp.requests/request 1 "resources/list" {}))
        (let [response (h/assert-take (:output-ch server))
              result (:result response)]
          (is (= 2 (count (:resources result))))
          (let [file-resource (first (:resources result))
                json-resource (second (:resources result))]
            (is (= "file:///test.txt" (:uri file-resource)))
            (is (= "Test File" (:name file-resource)))
            (is (= "A test file" (:description file-resource)))
            (is (= "text/plain" (:mimeType file-resource)))
            (is (= "file:///data.json" (:uri json-resource)))
            (is (= "Test Data" (:name json-resource)))
            (is (= "Test JSON data" (:description json-resource)))
            (is (= "application/json" (:mimeType json-resource)))))
        (lsp.server/shutdown server)))))

(deftest resource-reading
  (testing "Reading resources"
    (let [server (server/chan-server)
          context (server/create-context! {:name "test-server",
                                           :version "1.0.0",
                                           :tools [],
                                           :prompts [],
                                           :resources [resource-test-file
                                                       resource-test-json]})
          _join (server/start! server context)]
      (testing "Read text file resource"
        (async/put!
          (:input-ch server)
          (lsp.requests/request 2 "resources/read" {:uri "file:///test.txt"}))
        (let [response (h/assert-take (:output-ch server))
              result (:result response)]
          (is (= 1 (count (:contents result))))
          (let [content (first (:contents result))]
            (is (= "file:///test.txt" (:uri content)))
            (is (= "text/plain" (:mimeType content)))
            (is (contains? content :text)))))
      (testing "Read JSON resource"
        (async/put!
          (:input-ch server)
          (lsp.requests/request 3 "resources/read" {:uri "file:///data.json"}))
        (let [response (h/assert-take (:output-ch server))
              result (:result response)]
          (is (= 1 (count (:contents result))))
          (let [content (first (:contents result))]
            (is (= "file:///data.json" (:uri content)))
            (is (= "application/json" (:mimeType content)))
            (is (contains? content :blob)))))
      (testing "Invalid resource request"
        (async/put! (:input-ch server)
                    (lsp.requests/request 4
                                          "resources/read"
                                          {:uri "file:///invalid.txt"}))
        (is (= (lsp.responses/error (lsp.responses/response 4)
                                    (mcp.errors/body :resource-not-found
                                                     {:uri
                                                      "file:///invalid.txt"}))
               (h/assert-take (:output-ch server)))))
      (lsp.server/shutdown server))))

(deftest coerce-tool-response-test
  (testing "Coercing tool responses"
    (testing "Tool with sequential response"
      (let [tool {:name "list-maker",
                  :description "Creates a list of items",
                  :inputSchema {:type "object",
                                :properties {"count" {:type "number"}}}}
            handler-response [{:type "text", :text "item 1"}
                              {:type "text", :text "item 2"}]
            coerced (server/coerce-tool-response tool handler-response)]
        (is (= {:content [{:type "text", :text "item 1"}
                          {:type "text", :text "item 2"}]}
               coerced))
        (is
          (not (contains? coerced :structuredContent))
          "Should not include structuredContent when tool has no outputSchema")))
    (testing "Tool with non-sequential response"
      (let [tool {:name "echo",
                  :description "Echoes input",
                  :inputSchema {:type "object",
                                :properties {"message" {:type "string"}}}}
            handler-response {:type "text", :text "single item"}
            coerced (server/coerce-tool-response tool handler-response)]
        (is (= {:content [{:type "text", :text "single item"}]} coerced))
        (is (vector? (:content coerced))
            "Response should be wrapped in a vector")))
    (testing "Tool with outputSchema"
      (let [tool {:name "calculator",
                  :description "Performs calculations",
                  :inputSchema {:type "object",
                                :properties {"expression" {:type "string"}}},
                  :outputSchema {:type "object",
                                 :properties {"result" {:type "number"}}}}
            handler-response {:result 42}
            coerced (server/coerce-tool-response tool handler-response)]
        (is (= {:content [{:result 42}], :structuredContent [{:result 42}]}
               coerced))
        (is (contains? coerced :structuredContent)
            "Should include structuredContent when tool has outputSchema")))))

(deftest validate-spec-test
  (testing "Validating server specifications"
    (let [valid-tool {:name "valid-tool",
                      :description "A valid tool",
                      :inputSchema {:type "object"},
                      :handler identity}
          valid-resource {:uri "file:///valid.txt",
                          :name "Valid Resource",
                          :handler (fn [_]
                                     {:uri "file:///valid.txt", :text "valid"})}
          valid-prompt {:name "valid-prompt", :handler (fn [_] {:messages []})}
          valid-server-info {:name "test-server", :version "1.0.0"}
          invalid-tool-schema {:name "invalid-tool",
                               :description "Bad schema",
                               :inputSchema {:invalid "schema"}, ; Invalid key
                               :handler identity}
          invalid-resource-missing-name
            {:uri "file:///invalid.txt",
             ;; Missing :name
             :handler (fn [_] {:uri "file:///invalid.txt", :text "invalid"})}
          invalid-prompt-missing-name {;; Missing :name
                                       :handler (fn [_] {:messages []})}
          invalid-handler-tool {:name "invalid-handler-tool",
                                :description "Non-fn handler",
                                :inputSchema {:type "object"},
                                :handler "not-a-function"}
          invalid-server-info-missing-name {:version "1.0.0"}
          invalid-server-info-missing-version {:name "test-server"}
          invalid-server-info-bad-type {:name 123, :version "1.0.0"}]
      (testing "Valid specification"
        (is (server/validate-spec! (merge valid-server-info
                                          {:tools [valid-tool],
                                           :prompts [valid-prompt],
                                           :resources [valid-resource]}))
            "A completely valid spec should not throw"))
      (testing "Empty specification (requires server info)"
        (is (server/validate-spec! valid-server-info)
            "A spec with only server info should be valid")
        (is (server/validate-spec!
              (merge valid-server-info {:tools [], :prompts [], :resources []}))
            "A spec with server info and empty lists should be valid"))
      (testing "Partially valid specification (requires server info)"
        (is (server/validate-spec! (merge valid-server-info
                                          {:tools [valid-tool]}))
            "A spec with only valid tools should be valid")
        (is (server/validate-spec! (merge valid-server-info
                                          {:prompts [valid-prompt]}))
            "A spec with only valid prompts should be valid")
        (is (server/validate-spec! (merge valid-server-info
                                          {:resources [valid-resource]}))
            "A spec with only valid resources should be valid"))
      (testing "Invalid server info - missing name"
        (is (thrown? Exception
                     (server/validate-spec! invalid-server-info-missing-name))
            "Spec with missing server name should throw"))
      (testing "Invalid server info - missing version"
        (is (thrown? Exception
                     (server/validate-spec!
                       invalid-server-info-missing-version))
            "Spec with missing server version should throw"))
      (testing "Invalid server info - bad type"
        (is (thrown? Exception
                     (server/validate-spec! invalid-server-info-bad-type))
            "Spec with invalid server info type should throw"))
      (testing "Invalid tool schema"
        (is (thrown? Exception
                     (server/validate-spec! (merge valid-server-info
                                                   {:tools
                                                    [invalid-tool-schema]})))
            "Spec with invalid tool schema should throw"))
      (testing "Invalid resource definition"
        (is (thrown? Exception
                     (server/validate-spec!
                       (merge valid-server-info
                              {:resources [invalid-resource-missing-name]})))
            "Spec with invalid resource definition should throw"))
      (testing "Invalid prompt definition"
        (is (thrown? Exception
                     (server/validate-spec!
                       (merge valid-server-info
                              {:prompts [invalid-prompt-missing-name]})))
            "Spec with invalid prompt definition should throw"))
      (testing "Invalid handler type"
        (is (thrown? Exception
                     (server/validate-spec! (merge valid-server-info
                                                   {:tools
                                                    [invalid-handler-tool]})))
            "Spec with a non-function handler should throw")))))

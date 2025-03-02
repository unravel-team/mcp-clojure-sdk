(ns io.modelcontext.cljc-sdk.server-test
  (:require [babashka.json :as json]
            [clojure.core.async :as a]
            [clojure.test :refer [deftest is testing]]
            [io.modelcontext.cljc-sdk.core :as core]
            [io.modelcontext.cljc-sdk.server :as server]
            [io.modelcontext.cljc-sdk.specs :as specs]
            [io.modelcontext.cljc-sdk.transport.stdio :as stdio]))

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
   :handler identity})

(def resource-test-file
  {:description "A test file",
   :mimeType "text/plain",
   :name "Test File",
   :uri "file:///test.txt",
   :handler identity})

;;; Tests

(deftest server-basic-functionality
  (testing "Server creation and tool registration"
    (let [server (server/make-server {:name "test-server",
                                      :version "1.0.0",
                                      :tools [tool-greet],
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
          server (server/make-server {:name "test-server",
                                      :version "1.0.0",
                                      :tools [tool-greet],
                                      :prompts [],
                                      :resources []})]
      (try (server/start! server transport)
           ;; Here we'd typically simulate client requests through the
           ;; transport but we'll keep this simple for now
           (finally (server/stop! server))))))

;;; Mock transport for testing

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
      (is (= "test-server" (:name server)))
      (is (= "1.0.0" (:version server)))
      (is (= {} @(:tools server)))
      (is (= {} @(:resources server)))
      (is (= {} @(:prompts server))))))

(deftest tool-registration
  (testing "Tool registration and validation"
    (let [server (server/create-server "test-server" "1.0.0")]
      (server/register-tool!
        server
        {:name "test-tool",
         :description "A test tool",
         :inputSchema {:type "object", :properties {"arg" {:type "string"}}}}
        (fn [_] {:type "text", :text "success"}))
      (is (= 1 (count @(:tools server))))
      (is (get @(:tools server) "test-tool"))
      (testing "Tool validation"
        (is (thrown? Exception
                     (server/register-tool! server
                                            {:name "invalid",
                                             :description "desc",
                                             :inputSchema {:invalid "schema"}}
                                            identity)))))))

(deftest tool-execution
  (testing "Tool execution through protocol"
    (let [transport (create-mock-transport)
          server (server/make-server
                   {:name "test-server", :version "1.0.0", :tools [tool-echo]})
          _ (server/start! server transport)]
      (testing "Tool list request"
        (a/>!! (:received-ch transport)
               "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"tools/list\"}")
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:sent-ch transport) timeout])]
          (is (some? response) "Response received before timeout")
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
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:sent-ch transport) timeout])]
          (is (some? response) "Response received before timeout")
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
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:sent-ch transport) timeout])]
          (is (some? response) "Response received before timeout")
          (is (string? response))
          (is (-> response
                  json/read-str
                  :error
                  :code
                  (= specs/method-not-found)))))
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
      (let [timeout (a/timeout 500)
            [response _] (a/alts!! [(:sent-ch transport) timeout])]
        (is (some? response) "Response received before timeout")
        (is (string? response))
        (let [resp (json/read-str response)]
          (is (-> resp
                  :result
                  :protocolVersion
                  (= "DRAFT-2025-v1")))
          (is (-> resp
                  :result
                  :serverInfo
                  :name
                  (= "test-server")))))
      (server/stop! server))))

(deftest prompt-listing
  (testing "Listing available prompts"
    (let [transport (create-mock-transport)
          server (server/make-server {:name "test-server",
                                      :version "1.0.0",
                                      :tools [],
                                      :prompts [prompt-analyze-code
                                                prompt-poem-about-code]})
          _ (server/start! server transport)]
      (testing "Prompts list request"
        (a/>!! (:received-ch transport)
               "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"prompts/list\"}")
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:sent-ch transport) timeout])]
          (is (some? response) "Response received before timeout")
          (let [result (-> response
                           json/read-str
                           :result)]
            (is (= 2 (count (:prompts result))))
            (let [analyze (first (:prompts result))
                  poem (second (:prompts result))]
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
                  (:arguments poem)))))))
      (server/stop! server))))

(deftest prompt-getting
  (testing "Getting specific prompts"
    (let [transport (create-mock-transport)
          server (server/make-server {:name "test-server",
                                      :version "1.0.0",
                                      :tools [],
                                      :prompts [prompt-analyze-code
                                                prompt-poem-about-code]})
          _ (server/start! server transport)]
      (testing "Get analyze-code prompt"
        (a/>!!
          (:received-ch transport)
          "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"prompts/get\",\"params\":{\"name\":\"analyze-code\",\"arguments\":{\"language\":\"Clojure\",\"code\":\"(defn foo [])\"}}}")
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:sent-ch transport) timeout])]
          (is (some? response) "Response received before timeout")
          (let [result (-> response
                           json/read-str
                           :result)]
            (is (= 1 (count (:messages result))))
            (is
              (=
                "Analysis of Clojure code:\nHere are potential improvements for:\n(defn foo [])"
                (-> result
                    :messages
                    first
                    :content
                    :text))))))
      (testing "Get poem-about-code prompt"
        (a/>!!
          (:received-ch transport)
          "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"prompts/get\",\"params\":{\"name\":\"poem-about-code\",\"arguments\":{\"poetry_type\":\"haiku\",\"code\":\"(defn foo [])\"}}}")
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:sent-ch transport) timeout])]
          (is (some? response) "Response received before timeout")
          (let [result (-> response
                           json/read-str
                           :result)]
            (is (= 1 (count (:messages result))))
            (is (= "Write a haiku Poem about:\n(defn foo [])"
                   (-> result
                       :messages
                       first
                       :content
                       :text))))))
      (testing "Invalid prompt request"
        (a/>!!
          (:received-ch transport)
          "{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"method\":\"prompts/get\",\"params\":{\"name\":\"invalid-prompt\"}}")
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:sent-ch transport) timeout])]
          (is (some? response) "Response received before timeout")
          (let [error (-> response
                          json/read-str
                          :error)]
            (is (= specs/method-not-found (:code error)))
            (is (= "Prompt not found" (:message error))))))
      (server/stop! server))))

(deftest resource-listing
  (testing "Listing available resources"
    (let [transport (create-mock-transport)
          server (server/make-server {:name "test-server",
                                      :version "1.0.0",
                                      :tools [],
                                      :prompts [],
                                      :resources [resource-test-file
                                                  resource-test-json]})
          _ (server/start! server transport)]
      (testing "Resources list request"
        (a/>!!
          (:received-ch transport)
          "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"resources/list\"}")
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:sent-ch transport) timeout])]
          (is (some? response) "Response received before timeout")
          (let [result (-> response
                           json/read-str
                           :result)]
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
              (is (= "application/json" (:mimeType json-resource))))))
        (server/stop! server)))))

;; ## Reading resources
;; To read a resource, clients make a `resources/read` request with the resource
;; URI.
;;
;; The server responds with a list of resource contents:
;;
;; ```typescript
;; {
;;   contents: [
;;     {
;;       uri: string;        // The URI of the resource
;;       mimeType?: string;  // Optional MIME type
;;
;;       // One of:
;;       text?: string;      // For text resources
;;       blob?: string;      // For binary resources (base64 encoded)
;;     }
;;   ]
;; }
;; ```
;;
;; <Tip>
;;   Servers may return multiple resources in response to one `resources/read`
;;   request. This could be used, for example, to return a list of files inside
;;   a directory when the directory is read.
;; </Tip>
;;
;; // add a test to read resources, similar to test for getting prompts ai!
(deftest stdio-transport-encoding
  (testing "Stdio transport with different encodings"
    (testing "Default UTF-8 encoding"
      (let [transport (stdio/create-stdio-transport)]
        (is (= "UTF-8" (:encoding transport)))
        (is (some? (:in transport)))
        (is (some? (:out transport)))))
    (testing "Custom encoding creation"
      (let [transport (stdio/create-stdio-transport :encoding "UTF-16")]
        (is (= "UTF-16" (:encoding transport)))
        (is (some? (:in transport)))
        (is (some? (:out transport)))))
    (testing "ASCII encoding"
      (let [transport (stdio/create-stdio-transport :encoding "ASCII")]
        (is (= "ASCII" (:encoding transport)))
        (is (some? (:in transport)))
        (is (some? (:out transport)))))
    (testing "Invalid encoding handling"
      (is (thrown? IllegalArgumentException
                   (stdio/create-stdio-transport :encoding
                                                 "INVALID-ENCODING"))))
    ;; (testing "Special characters with different encodings"
    ;;   (let [test-messages {"UTF-8" "Hello, 世界! 🌍",
    ;;                        "UTF-16" "Hello, 世界! 🌍",
    ;;                        "ASCII" "Hello, World!"}]
    ;;     (doseq [[encoding message] test-messages]
    ;;       (testing (str "Testing " encoding " encoding")
    ;;         (let [transport (stdio/create-stdio-transport :encoding
    ;;         encoding)
    ;;               received-ch (a/chan 1)]
    ;;           (core/start! transport)
    ;;           (a/go (let [received (core/receive! transport)]
    ;;                   (a/>! received-ch received)))
    ;;           (core/send! transport message)
    ;;           (let [timeout (a/timeout 500)
    ;;                 [received _] (a/alts!! [received-ch timeout])]
    ;;             (is (some? received) "Message should be received")
    ;;             (is (= message received)
    ;;                 "Received message should match sent message"))
    ;;           (core/stop! transport))))))
    ;; (testing "Process transport with custom encoding"
    ;;   (let [transport (stdio/create-process-transport {:command "echo",
    ;;                                                    :args ["Hello"],
    ;;                                                    :encoding
    ;;                                                    "UTF-16",
    ;;                                                    :buffer-size
    ;;                                                    4096})]
    ;;     (is (= "UTF-16" (:encoding transport)))
    ;;     (is (some? (:process transport)))
    ;;     (core/start! transport)
    ;;     (core/stop! transport)))
    ;; (testing "Large messages with different buffer sizes"
    ;;   (doseq [buffer-size [1024 4096 16384]]
    ;;     (testing (str "Buffer size: " buffer-size)
    ;;       (let [transport (stdio/create-stdio-transport :buffer-size
    ;;                                                     buffer-size)
    ;;             large-message (apply str (repeat 10000 "x"))]
    ;;         (core/start! transport)
    ;;         (core/send! transport large-message)
    ;;         (core/stop! transport)))))
    ;; (testing "Multiple encodings in process transport"
    ;;   (doseq [encoding ["UTF-8" "UTF-16" "ASCII"]]
    ;;     (testing (str "Process transport with " encoding)
    ;;       (let [transport (stdio/create-process-transport {:command
    ;;       "cat",
    ;;                                                        :encoding
    ;;                                                        encoding})
    ;;             test-message "Hello, World!"]
    ;;         (core/start! transport)
    ;;         (let [received-ch (a/chan 1)]
    ;;           (a/go (let [received (core/receive! transport)]
    ;;                   (a/>! received-ch received)))
    ;;           (core/send! transport test-message)
    ;;           (let [timeout (a/timeout 500)
    ;;                 [received _] (a/alts!! [received-ch timeout])]
    ;;             (is (some? received) "Message should be received")
    ;;             (is (= test-message received)
    ;;                 "Received message should match sent message")))
    ;;         (core/stop! transport)))))
    ;; (testing "Encoding validation"
    ;;   (testing "Valid encodings"
    ;;     (doseq [encoding ["UTF-8" "UTF-16" "UTF-16BE" "UTF-16LE" "ASCII"
    ;;                       "ISO-8859-1"]]
    ;;       (is (stdio/validate-encoding! encoding)
    ;;           (str encoding " should be valid"))))
    ;;   (testing "Invalid encodings"
    ;;     (doseq [encoding ["INVALID" "UTF-99" "ASCII-2"]]
    ;;       (is (thrown? IllegalArgumentException
    ;;                    (stdio/validate-encoding! encoding))
    ;;           (str encoding " should be invalid")))))
    ;; (testing "Buffer size validation"
    ;;   (testing "Valid buffer sizes"
    ;;     (doseq [size [1024 4096 8192 16384]]
    ;;       (let [transport (stdio/create-stdio-transport :buffer-size
    ;;       size)]
    ;;         (is (some? transport)))))
    ;;   (testing "Default buffer size"
    ;;     (let [transport (stdio/create-stdio-transport)]
    ;;       (is (some? transport)))))
  ))

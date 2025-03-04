(ns io.modelcontext.cljc-sdk.server-test
  (:require [babashka.json :as json]
            [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [io.modelcontext.cljc-sdk.core :as core]
            [io.modelcontext.cljc-sdk.server :as server]
            [io.modelcontext.cljc-sdk.specs :as specs]
            [io.modelcontext.cljc-sdk.transport.stdio :as stdio]
            [me.vedang.logger.interface :as log]))

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

(defrecord MockTransport [in-ch out-ch msg-ch running? sent-messages]
  core/Transport
    (start! [this]
      (reset! running? true)
      (a/go-loop []
        (when @running?
          (try (when-let [msg (a/<! in-ch)] (a/>! msg-ch msg))
               (catch Exception e
                 (log/error :msg "Error reading from Mocked stdin" :error e)))
          (recur)))
      this)
    (stop! [_this]
      (reset! running? false)
      (a/close! msg-ch)
      (a/close! in-ch)
      (a/close! out-ch))
    (send! [_this message]
      (when @running?
        (swap! sent-messages conj message)
        (a/>!! out-ch message)))
    (receive! [_this] (a/<!! msg-ch)))

(defn create-mock-transport
  []
  (->MockTransport (a/chan) (a/chan) (a/chan 1024) (atom false) (atom [])))

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
        (a/>!! (:in-ch transport)
               "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"tools/list\"}")
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:out-ch transport) timeout])]
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
          (:in-ch transport)
          "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"message\":\"test\"}}}")
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:out-ch transport) timeout])]
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
          (:in-ch transport)
          "{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"method\":\"tools/call\",\"params\":{\"name\":\"invalid\"}}")
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:out-ch transport) timeout])]
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
        (:in-ch transport)
        "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"DRAFT-2025-v1\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test-client\",\"version\":\"1.0.0\"}}}")
      (let [timeout (a/timeout 500)
            [response _] (a/alts!! [(:out-ch transport) timeout])]
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
        (a/>!! (:in-ch transport)
               "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"prompts/list\"}")
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:out-ch transport) timeout])]
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
          (:in-ch transport)
          "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"prompts/get\",\"params\":{\"name\":\"analyze-code\",\"arguments\":{\"language\":\"Clojure\",\"code\":\"(defn foo [])\"}}}")
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:out-ch transport) timeout])]
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
          (:in-ch transport)
          "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"prompts/get\",\"params\":{\"name\":\"poem-about-code\",\"arguments\":{\"poetry_type\":\"haiku\",\"code\":\"(defn foo [])\"}}}")
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:out-ch transport) timeout])]
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
          (:in-ch transport)
          "{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"method\":\"prompts/get\",\"params\":{\"name\":\"invalid-prompt\"}}")
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:out-ch transport) timeout])]
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
          (:in-ch transport)
          "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"resources/list\"}")
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:out-ch transport) timeout])]
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

(deftest resource-reading
  (testing "Reading resources"
    (let [transport (create-mock-transport)
          server (server/make-server {:name "test-server",
                                      :version "1.0.0",
                                      :tools [],
                                      :prompts [],
                                      :resources [resource-test-file
                                                  resource-test-json]})
          _ (server/start! server transport)]
      (testing "Read text file resource"
        (a/>!!
          (:in-ch transport)
          "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"resources/read\",\"params\":{\"uri\":\"file:///test.txt\"}}")
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:out-ch transport) timeout])]
          (is (some? response) "Response received before timeout")
          (let [result (-> response
                           json/read-str
                           :result)]
            (is (= 1 (count (:contents result))))
            (let [content (first (:contents result))]
              (is (= "file:///test.txt" (:uri content)))
              (is (= "text/plain" (:mimeType content)))
              (is (contains? content :text))))))
      (testing "Read JSON resource"
        (a/>!!
          (:in-ch transport)
          "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"resources/read\",\"params\":{\"uri\":\"file:///data.json\"}}")
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:out-ch transport) timeout])]
          (is (some? response) "Response received before timeout")
          (let [result (-> response
                           json/read-str
                           :result)]
            (is (= 1 (count (:contents result))))
            (let [content (first (:contents result))]
              (is (= "file:///data.json" (:uri content)))
              (is (= "application/json" (:mimeType content)))
              (is (contains? content :blob))))))
      (testing "Invalid resource request"
        (a/>!!
          (:in-ch transport)
          "{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"method\":\"resources/read\",\"params\":{\"uri\":\"file:///invalid.txt\"}}")
        (let [timeout (a/timeout 500)
              [response _] (a/alts!! [(:out-ch transport) timeout])]
          (is (some? response) "Response received before timeout")
          (let [error (-> response
                          json/read-str
                          :error)]
            (is (= specs/method-not-found (:code error)))
            (is (= "Resource not found" (:message error))))))
      (server/stop! server))))

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
    (testing "Special characters with different encodings"
      (let [test-messages {"UTF-8" "Hello 1, 世界! 🌍",
                           "UTF-16" "Hello 2, 世界! 🌍",
                           "ASCII" "Hello 3, World!"}]
        (doseq [[encoding message] test-messages]
          (testing (str "Testing " encoding " encoding")
            ;; Create piped streams for controlled input
            (let [input-stream (java.io.PipedInputStream.)
                  output-stream (java.io.PipedOutputStream.)
                  original-in System/in]
              ;; Connect the pipes
              (.connect input-stream output-stream)
              ;; Replace System.in temporarily
              (System/setIn input-stream)
              (try
                ;; Create and start the transport with our controlled stdin
                (let [transport (stdio/create-stdio-transport :encoding
                                                              encoding)]
                  (core/start! transport)
                  ;; Write to our controlled output stream with proper
                  ;; encoding
                  (with-open [writer
                                (io/writer output-stream :encoding encoding)]
                    (.write writer (str message "\n"))
                    (.flush writer)
                    ;; Allow some time for processing
                    (Thread/sleep 100)
                    (let [received (core/receive! transport)]
                      (is (some? received) "Message should be received")
                      (is (= message received)
                          "Received message should match sent message"))
                    (core/stop! transport)))
                (finally
                  ;; Restore original stdin
                  (System/setIn original-in))))))))
    (testing "Process transport with custom encoding"
      (let [transport (stdio/create-process-transport {:command "echo",
                                                       :args ["Hello"],
                                                       :encoding "UTF-16",
                                                       :buffer-size 4096})]
        (is (= "UTF-16" (:encoding transport)))
        (is (some? (:process transport)))
        (core/start! transport)
        (core/stop! transport)))
    (testing "Large messages with different buffer sizes"
      (doseq [buffer-size [1024 4096 16384]]
        (testing (str "Buffer size: " buffer-size)
          (let [transport (stdio/create-stdio-transport :buffer-size
                                                        buffer-size)
                large-message (apply str (repeat 10000 "x"))]
            (core/start! transport)
            (core/send! transport large-message)
            (core/stop! transport)))))
    (testing "Multiple encodings in process transport"
      (doseq [encoding ["UTF-8" "UTF-16" "ASCII"]]
        (testing (str "Process transport with " encoding)
          (let [test-message "Hello, World!"
                transport (stdio/create-process-transport {:command "cat",
                                                           :encoding encoding})]
            (core/start! transport)
            ;; Get the process output stream (which is the input to the
            ;; process)
            (let [proc (:process transport)
                  proc-input (.getOutputStream proc)]
              (with-open [writer (io/writer proc-input :encoding encoding)]
                (.write writer (str test-message "\n"))
                (.flush writer)
                ;; Allow some time for processing
                (Thread/sleep 100)
                (let [received (core/receive! transport)]
                  (is (some? received) "Message should be received")
                  (is (= test-message received)
                      "Received message should match sent message"))
                (core/stop! transport)))))))
    (testing "Encoding validation"
      (testing "Valid encodings"
        (doseq [encoding ["UTF-8" "UTF-16" "UTF-16BE" "UTF-16LE" "ASCII"
                          "ISO-8859-1"]]
          (is (stdio/validate-encoding! encoding)
              (str encoding " should be valid"))))
      (testing "Invalid encodings"
        (doseq [encoding ["INVALID" "UTF-99" "ASCII-2"]]
          (is (thrown? IllegalArgumentException
                       (stdio/validate-encoding! encoding))
              (str encoding " should be invalid")))))
    (testing "Buffer size validation"
      (testing "Valid buffer sizes"
        (doseq [size [1024 4096 8192 16384]]
          (let [transport (stdio/create-stdio-transport :buffer-size size)]
            (is (some? transport)))))
      (testing "Default buffer size"
        (let [transport (stdio/create-stdio-transport)]
          (is (some? transport)))))))

;;; calculator mcp server

(defn validate-array
  "Helper function to validate array inputs"
  [arr]
  (when-not (and (sequential? arr) (every? number? arr))
    (throw (ex-info "Invalid input: Expected array of numbers" {:input arr}))))

(def tool-add
  {:name "add",
   :description "Add two numbers together",
   :inputSchema {:type "object",
                 :properties {"a" {:type "number", :description "First number"},
                              "b" {:type "number",
                                   :description "Second number"}},
                 :required ["a" "b"]},
   :handler (fn [{:keys [a b]}] {:type "text", :text (str (+ a b))})})

(def tool-subtract
  {:name "subtract",
   :description "Subtract second number from first",
   :inputSchema {:type "object",
                 :properties {"a" {:type "number", :description "First number"},
                              "b" {:type "number",
                                   :description "Second number"}},
                 :required ["a" "b"]},
   :handler (fn [{:keys [a b]}] {:type "text", :text (str (- a b))})})

(def tool-multiply
  {:name "multiply",
   :description "Multiply two numbers",
   :inputSchema {:type "object",
                 :properties {"a" {:type "number", :description "First number"},
                              "b" {:type "number",
                                   :description "Second number"}},
                 :required ["a" "b"]},
   :handler (fn [{:keys [a b]}] {:type "text", :text (str (* a b))})})

(def tool-divide
  {:name "divide",
   :description "Divide first number by second",
   :inputSchema {:type "object",
                 :properties {"a" {:type "number", :description "First number"},
                              "b" {:type "number",
                                   :description "Second number (non-zero)"}},
                 :required ["a" "b"]},
   :handler
   (fn [{:keys [a b]}]
     (if (zero? b)
       {:type "text", :text "Error: Cannot divide by zero", :is-error true}
       {:type "text", :text (str (/ a b))}))})

(def tool-power
  {:name "power",
   :description "Raise a number to a power",
   :inputSchema {:type "object",
                 :properties
                 {"base" {:type "number", :description "Base number"},
                  "exponent" {:type "number", :description "Exponent"}},
                 :required ["base" "exponent"]},
   :handler (fn [{:keys [base exponent]}]
              (try {:type "text", :text (str (Math/pow base exponent))}
                   (catch Exception e
                     {:type "text",
                      :text (str "Error calculating power: " (.getMessage e)),
                      :is-error true})))})

(def tool-square-root
  {:name "square-root",
   :description "Calculate the square root of a number",
   :inputSchema {:type "object",
                 :properties {"number" {:type "number",
                                        :description
                                        "Number to find square root of"}},
                 :required ["number"]},
   :handler (fn [{:keys [number]}]
              (if (neg? number)
                {:type "text",
                 :text "Error: Cannot calculate square root of negative number",
                 :is-error true}
                {:type "text", :text (str (Math/sqrt number))}))})

(def tool-sum-array
  {:name "sum-array",
   :description "Calculate the sum of an array of numbers",
   :inputSchema {:type "object",
                 :properties {"numbers" {:type "array",
                                         :items {:type "number"},
                                         :description
                                         "Array of numbers to sum"}},
                 :required ["numbers"]},
   :handler (fn [{:keys [numbers]}]
              (try (validate-array numbers)
                   {:type "text", :text (str (reduce + numbers))}
                   (catch Exception e
                     {:type "text",
                      :text (str "Error: " (.getMessage e)),
                      :is-error true})))})

(def tool-average
  {:name "average",
   :description "Calculate the average of an array of numbers",
   :inputSchema {:type "object",
                 :properties {"numbers" {:type "array",
                                         :items {:type "number"},
                                         :description
                                         "Array of numbers to average"}},
                 :required ["numbers"]},
   :handler (fn [{:keys [numbers]}]
              (try (validate-array numbers)
                   (if (empty? numbers)
                     {:type "text",
                      :text "Error: Cannot calculate average of empty array",
                      :is-error true}
                     {:type "text",
                      :text (str (double (/ (reduce + numbers)
                                            (count numbers))))})
                   (catch Exception e
                     {:type "text",
                      :text (str "Error: " (.getMessage e)),
                      :is-error true})))})

(def tool-factorial
  {:name "factorial",
   :description
   "Calculate the factorial of a number (demonstrates longer computation)",
   :inputSchema {:type "object",
                 :properties {"number" {:type "number",
                                        :description
                                        "Number to calculate factorial of"}},
                 :required ["number"]},
   :handler
   (fn [{:keys [number]}]
     (let [number (parse-long number)]
       (if (or (neg? number) (not (integer? number)))
         {:type "text",
          :text "Error: Factorial requires a non-negative integer",
          :is-error true}
         (try
           ;; Simulate longer computation for large numbers
           (when (> number 10) (Thread/sleep 1000))
           {:type "text", :text (str (reduce * (range 1 (inc number))))}
           (catch Exception e
             {:type "text",
              :text (str "Error calculating factorial: " (.getMessage e)),
              :is-error true})))))})

(def calculator-server-spec
  {:name "calculator",
   :version "1.0.0",
   :tools [;; Basic arithmetic operations
           tool-add tool-subtract tool-multiply tool-divide
           ;; Advanced mathematical operations
           tool-power tool-square-root
           ;; Array operations to test complex inputs
           tool-sum-array tool-average
           ;; Test long-running operation
           tool-factorial]})

(ns io.modelcontext.clojure-sdk.http-transport-test
  "End-to-end tests for the Streamable HTTP transport.

  Starts a real Pedestal/Jetty server on an ephemeral port and connects
  the HTTP client to it over localhost."
  (:require [clj-http.client :as http]
            [clojure.test :refer [deftest is testing]]
            [io.modelcontext.clojure-sdk.client :as client]
            [io.modelcontext.clojure-sdk.http-client :as http-client]
            [io.modelcontext.clojure-sdk.http-server :as http-server]
            [io.modelcontext.clojure-sdk.server :as server]))

(def tool-echo
  {:name "echo",
   :description "Echo input",
   :inputSchema {:type "object",
                 :properties {"message" {:type "string"}},
                 :required ["message"]},
   :handler (fn [{:keys [message]}] {:type "text", :text message})})

(def test-spec
  {:name "http-test-server",
   :version "1.0.0",
   :tools [tool-echo],
   :prompts [],
   :resources []})

(def client-info {:name "http-test-client", :version "1.0.0"})

(defn- mcp-url [handle] (str "http://127.0.0.1:" (:port handle) "/mcp"))

(defn- with-http-server
  [f]
  (let [handle (http-server/start! test-spec {:host "127.0.0.1", :port 0})]
    (try (f handle) (finally (http-server/stop! handle)))))

(defn- await-result [pending] (client/deref-or-cancel pending 5000 ::timeout))

(deftest http-round-trip
  (testing "initialize, list tools, call tool and ping over HTTP"
    (with-http-server
      (fn [handle]
        (let [{:keys [client error]} (http-client/run! client-info
                                                       (mcp-url handle)
                                                       {:timeout-ms 5000})]
          (is (nil? error) (str "Unexpected error: " error))
          (when client
            (try (is (client/initialized? client))
                 (is (= "http-test-server" (:name (client/server-info client))))
                 (let [tools-result (await-result (client/list-tools! client))]
                   (is (= ["echo"] (mapv :name (:tools tools-result)))))
                 (let [call-result (await-result (client/call-tool!
                                                   client
                                                   "echo"
                                                   {:message "over http"}))]
                   (is (= "over http"
                          (-> call-result
                              :content
                              first
                              :text))))
                 (let [ping-result (await-result (client/ping! client))]
                   (is (= {} ping-result)))
                 (finally (http-client/shutdown! client)))))))))

(deftest http-sse-notifications
  (testing "server-initiated notifications reach the client over SSE"
    (with-http-server
      (fn [handle]
        (let [tools-changed (promise)
              {:keys [client error]} (http-client/run! client-info
                                                       (mcp-url handle)
                                                       {:timeout-ms 5000,
                                                        :on-tool-list-changed
                                                        #(deliver tools-changed
                                                                  true)})]
          (is (nil? error) (str "Unexpected error: " error))
          (when client
            (try
              ;; Give the client's GET stream a moment to connect, then
              ;; notify through the session's server endpoint.
              (Thread/sleep 500)
              (let [endpoint (-> @(:sessions handle)
                                 vals
                                 first
                                 :endpoint)]
                (is (some? endpoint) "Session should be registered")
                (server/notify-tools-list-changed! endpoint))
              (is (true? (deref tools-changed 5000 ::timeout))
                  "Client should receive the list_changed notification")
              (finally (http-client/shutdown! client)))))))))

(deftest http-session-lifecycle
  (testing "session handling over raw HTTP"
    (with-http-server
      (fn [handle]
        (let [url (mcp-url handle)]
          (testing "non-initialize request without a session id is rejected"
            (let
              [resp
                 (http/post
                   url
                   {:headers {"Content-Type" "application/json",
                              "Accept" "application/json, text/event-stream"},
                    :body
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}",
                    :throw-exceptions false})]
              (is (= 400 (:status resp)))))
          (testing "unknown session id is rejected with 404"
            (let
              [resp
                 (http/post
                   url
                   {:headers {"Content-Type" "application/json",
                              "Accept" "application/json, text/event-stream",
                              "Mcp-Session-Id" "no-such-session"},
                    :body
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}",
                    :throw-exceptions false})]
              (is (= 404 (:status resp)))))
          (testing "DELETE terminates the session"
            (let [{:keys [client error]}
                    (http-client/run! client-info url {:timeout-ms 5000})]
              (is (nil? error) (str "Unexpected error: " error))
              (when client
                (let [session-id (http-client/session-id client)]
                  (is (string? session-id))
                  (http-client/shutdown! client)
                  (let
                    [resp
                       (http/post
                         url
                         {:headers {"Content-Type" "application/json",
                                    "Accept"
                                    "application/json, text/event-stream",
                                    "Mcp-Session-Id" session-id},
                          :body
                          "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\",\"params\":{}}",
                          :throw-exceptions false})]
                    (is (= 404 (:status resp))
                        "Session should be gone after DELETE")))))))))))

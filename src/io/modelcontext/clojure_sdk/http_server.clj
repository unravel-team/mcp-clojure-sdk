(ns io.modelcontext.clojure-sdk.http-server
  "Streamable HTTP transport (server side) for MCP.

  Implements the MCP Streamable HTTP transport (spec revision 2025-03-26)
  on top of Pedestal with Jetty. A single endpoint (\"/mcp\") accepts:

  - POST: one JSON-RPC message per request. Requests are answered with
    the corresponding JSON-RPC response (200, application/json), while
    notifications and responses are acknowledged with 202.
  - GET: opens an SSE stream over which server-initiated messages
    (notifications and requests) are pushed to the client.
  - DELETE: terminates the session.

  The server assigns an Mcp-Session-Id header on the response to the
  `initialize` POST. Clients must send that header on all subsequent
  requests. Each session gets its own jsonrpc endpoint; all sessions
  share the tools/resources/prompts registered in the server context."
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [io.modelcontext.clojure-sdk.io-chan :as mcp.io-chan]
            [io.modelcontext.clojure-sdk.server :as core]
            [io.pedestal.http :as http]
            [jsonrpc4clj.server :as jsonrpc.server]
            [me.vedang.logger.interface :as log])
  (:import [org.eclipse.jetty.server Server ServerConnector]))

(set! *warn-on-reflection* true)

;; How long a POST-ed JSON-RPC request waits for its response before the
;; HTTP request is answered with 504 Gateway Timeout.
(def ^:private request-timeout-ms 30000)

;;; ============================================================================
;;; Responses
;;; ============================================================================

(defn- error-body
  "Build a JSON-RPC error message for transport-level failures."
  [code message]
  {:jsonrpc "2.0", :id nil, :error {:code code, :message message}})

(defn- json-response
  [status msg]
  {:status status,
   :headers {"Content-Type" "application/json"},
   :body (mcp.io-chan/message->json-str msg)})

;;; ============================================================================
;;; Sessions
;;; ============================================================================

(defn- monitor-server-logs
  [log-ch]
  ;; NOTE: We don't do this in `initialize`, because if anything bad
  ;; happened before `initialize`, we wouldn't get any logs.
  (async/go-loop []
    (when-let [log-args (async/<! log-ch)]
      (log/trace :level (first log-args) :args (rest log-args))
      (recur))))

(defn- route-output-messages!
  "Route outgoing messages from the session's jsonrpc endpoint.

  Responses to in-flight POST requests are delivered to the waiting
  HTTP thread through the promise registered in `pending-posts`.
  Everything else (server-initiated requests and notifications) goes to
  the session's SSE stream. Closes `sse-ch` when `output-ch` closes."
  [output-ch sse-ch pending-posts]
  (async/thread
    (loop []
      (if-let [msg (async/<!! output-ch)]
        (let [pending (when (and (some? (:id msg)) (not (:method msg)))
                        (get @pending-posts (:id msg)))]
          (if pending
            (do (swap! pending-posts dissoc (:id msg)) (deliver pending msg))
            (async/>!! sse-ch msg))
          (recur))
        (async/close! sse-ch)))))

(defn- create-session!
  "Create a new session: a dedicated jsonrpc endpoint with its own
  channels, an SSE channel for server-initiated messages and a registry
  of in-flight POST requests. Registers the session in `sessions` and
  returns it."
  [sessions context]
  (let [session-id (str (random-uuid))
        input-ch (async/chan 16)
        output-ch (async/chan 16)
        log-ch (async/chan (async/sliding-buffer 20))
        endpoint (jsonrpc.server/chan-server
                   {:input-ch input-ch, :output-ch output-ch, :log-ch log-ch})
        ;; A sliding buffer so that notifications sent before the client
        ;; connects its SSE stream are buffered instead of blocking.
        sse-ch (async/chan (async/sliding-buffer 32))
        pending-posts (atom {})]
    (log/debug :fn :create-session! :session-id session-id)
    (monitor-server-logs log-ch)
    (jsonrpc.server/start endpoint (assoc context :server endpoint))
    (route-output-messages! output-ch sse-ch pending-posts)
    (let [session {:id session-id,
                   :endpoint endpoint,
                   :input-ch input-ch,
                   :output-ch output-ch,
                   :sse-ch sse-ch,
                   :pending-posts pending-posts}]
      (swap! sessions assoc session-id session)
      session)))

(defn- stop-session!
  "Shut down the session's jsonrpc endpoint and remove it from the
  registry. Shutting down the endpoint closes its channels, which in
  turn ends the session's SSE stream."
  [sessions session-id]
  (when-let [session (get @sessions session-id)]
    (log/debug :fn :stop-session! :session-id session-id)
    (swap! sessions dissoc session-id)
    (jsonrpc.server/shutdown (:endpoint session))))

(defn- resolve-session
  "Resolve the session for a request from its Mcp-Session-Id header.

  Returns [session nil] when found, [nil error-response] otherwise."
  [sessions request]
  (if-let [session-id (get-in request [:headers "mcp-session-id"])]
    (if-let [session (get @sessions session-id)]
      [session nil]
      [nil (json-response 404 (error-body -32001 "Session not found"))])
    [nil
     (json-response 400 (error-body -32600 "Missing Mcp-Session-Id header"))]))

;;; ============================================================================
;;; Handlers
;;; ============================================================================

(defn- respond-to-request
  "Put a JSON-RPC request on the session's input channel and block until
  the matching response is produced, then return it as an HTTP response."
  [session msg extra-headers]
  (let [pending (promise)]
    (swap! (:pending-posts session) assoc (:id msg) pending)
    (async/>!! (:input-ch session) msg)
    (let [response (deref pending request-timeout-ms ::timeout)]
      (if (= ::timeout response)
        (do (swap! (:pending-posts session) dissoc (:id msg))
            (json-response 504 (error-body -32603 "Request timed out")))
        {:status 200,
         :headers (merge {"Content-Type" "application/json"} extra-headers),
         :body (mcp.io-chan/message->json-str response)}))))

(defn- handle-post
  "Handle a POST-ed JSON-RPC message.

  An `initialize` request creates a new session and returns its id in
  the Mcp-Session-Id header. Other requests are answered with their
  JSON-RPC response; notifications and responses are acknowledged with
  202 Accepted."
  [sessions context request]
  (let [msg (mcp.io-chan/json-str->message (some-> (:body request)
                                                   slurp))]
    (cond (= :parse-error msg) (json-response 400
                                              (error-body -32700 "Parse error"))
          (= "initialize" (:method msg))
            (let [session (create-session! sessions context)]
              (respond-to-request session msg {"Mcp-Session-Id" (:id session)}))
          :else (let [[session error-response] (resolve-session sessions
                                                                request)]
                  (cond error-response error-response
                        ;; A request: block for the matching response.
                        (and (some? (:id msg)) (:method msg))
                          (respond-to-request session msg {})
                        ;; A notification or a response to a
                        ;; server-initiated request: accept and move on.
                        :else (do (async/>!! (:input-ch session) msg)
                                  {:status 202}))))))

(defn- sse-body
  "Return a streaming response body fn which writes every message taken
  from `sse-ch` as an SSE event. Ends when `sse-ch` closes (session
  terminated) or when the client disconnects."
  [sse-ch]
  (fn [^java.io.OutputStream output-stream]
    (let [^java.io.Writer writer (io/writer output-stream)]
      (try
        ;; Commit the response headers right away so the client's GET
        ;; returns without waiting for the first event.
        (doto writer (.write ": connected\n\n") (.flush))
        (loop []
          (when-let [msg (async/<!! sse-ch)]
            (doto writer
              (.write (str "data: " (mcp.io-chan/message->json-str msg) "\n\n"))
              (.flush))
            (recur)))
        (catch Exception e
          (log/debug :fn :sse-body :msg "SSE stream closed" :ex e))))))

(defn- handle-get
  "Open the SSE stream over which server-initiated messages are pushed."
  [sessions request]
  (let [[session error-response] (resolve-session sessions request)]
    (or error-response
        {:status 200,
         :headers {"Content-Type" "text/event-stream",
                   "Cache-Control" "no-cache"},
         :body (sse-body (:sse-ch session))})))

(defn- handle-delete
  "Terminate the session identified by the Mcp-Session-Id header."
  [sessions request]
  (let [[session error-response] (resolve-session sessions request)]
    (or error-response
        (do (stop-session! sessions (:id session)) {:status 204}))))

;;; ============================================================================
;;; Server Lifecycle
;;; ============================================================================

(defn- bound-port
  "Return the port Jetty is actually bound to. Resolves an ephemeral
  port request (:port 0) to the real port."
  [runtime]
  (let [^Server jetty (::http/server runtime)]
    (.getLocalPort ^ServerConnector (first (.getConnectors jetty)))))

(defn start!
  "Start a Streamable HTTP MCP server for `spec` (see
  `io.modelcontext.clojure-sdk.server/create-context!`).

  Args:

  - spec: The MCP server spec (:name, :version, :tools, ...)

  - opts: Map of:
    :host - The host to bind to (default \"127.0.0.1\")
    :port - The port to bind to; 0 picks an ephemeral port (default 0)

  Returns a handle map of:
    :port     - The actual bound port
    :sessions - Atom of session-id -> session (each session contains the
                jsonrpc :endpoint for server-initiated messages)
    :stop!    - Zero-arg fn that stops the server (see `stop!`)"
  [spec {:keys [host port], :or {host "127.0.0.1", port 0}}]
  (core/validate-spec! spec)
  (log/info :msg "[HTTP SERVER] Starting server...")
  (let [context (core/create-context! spec)
        sessions (atom {})
        routes #{["/mcp" :post (partial handle-post sessions context)
                  :route-name ::mcp-post]
                 ["/mcp" :get (partial handle-get sessions) :route-name
                  ::mcp-get]
                 ["/mcp" :delete (partial handle-delete sessions) :route-name
                  ::mcp-delete]}
        runtime (-> {::http/routes routes,
                     ::http/type :jetty,
                     ::http/host host,
                     ::http/port port,
                     ::http/join? false,
                     ::http/request-logger nil}
                    http/create-server
                    http/start)]
    {:port (bound-port runtime),
     :sessions sessions,
     :context context,
     :runtime runtime,
     :stop! (fn []
              (log/info :msg "[HTTP SERVER] Stopping server...")
              (doseq [session-id (keys @sessions)]
                (stop-session! sessions session-id))
              (http/stop runtime))}))

(defn stop!
  "Stop the HTTP server and shut down all session endpoints."
  [handle]
  ((:stop! handle)))

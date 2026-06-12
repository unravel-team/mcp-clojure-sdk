(ns io.modelcontext.clojure-sdk.http-client
  "Streamable HTTP transport (client side) for MCP.

  Implements the client half of the MCP Streamable HTTP transport (spec
  revision 2025-03-26) on top of clj-http. Outgoing JSON-RPC messages
  are POST-ed to the server's single MCP endpoint; JSON-RPC responses
  delivered in the POST response body are fed back into the client's
  input channel. After initialization a GET request opens an SSE stream
  over which server-initiated messages (notifications and requests) are
  received."
  (:require [clj-http.client :as http]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [io.modelcontext.clojure-sdk.client :as client]
            [io.modelcontext.clojure-sdk.io-chan :as mcp.io-chan]
            [me.vedang.logger.interface :as log])
  (:import [java.io BufferedReader InputStream InputStreamReader]
           [java.nio.charset StandardCharsets])
  (:refer-clojure :exclude [run!]))

(set! *warn-on-reflection* true)

;;; ============================================================================
;;; Sending Messages (POST)
;;; ============================================================================

(defn- post-headers
  [session-id]
  (cond-> {"Content-Type" "application/json",
           "Accept" "application/json, text/event-stream"}
    session-id (assoc "Mcp-Session-Id" session-id)))

(defn- handle-post-response
  "Feed a JSON-RPC response delivered in a POST response body into the
  client's input channel. 202 acknowledgements carry no message."
  [{:keys [status headers body]} input-ch]
  (cond (and (= 200 status)
             (string? body)
             (str/includes? (str (get headers "content-type"))
                            "application/json"))
          (let [msg (mcp.io-chan/json-str->message body)]
            (when-not (= :parse-error msg) (async/>!! input-ch msg)))
        (= 202 status) nil
        :else (log/debug :fn :handle-post-response
                         :msg "Unexpected POST response"
                         :status status
                         :body body)))

(defn- start-sender!
  "Take outgoing messages from `output-ch` and POST them to `url`.

  Captures the Mcp-Session-Id header from the response to the
  `initialize` request into `session-id*`. Exits when `output-ch`
  closes (endpoint shutdown). Logs and continues on errors."
  [url session-id* input-ch output-ch]
  (async/thread
    (loop []
      (when-let [msg (async/<!! output-ch)]
        (try (let [resp (http/post url
                                   {:headers (post-headers @session-id*),
                                    :body (mcp.io-chan/message->json-str msg),
                                    :throw-exceptions false})]
               (when (= "initialize" (:method msg))
                 ;; clj-http lowercases response header keys.
                 (when-let [sid (get-in resp [:headers "mcp-session-id"])]
                   (reset! session-id* sid)))
               (handle-post-response resp input-ch))
             (catch Exception e (log/error :fn :start-sender! :ex e)))
        (recur)))))

;;; ============================================================================
;;; Receiving Server-Initiated Messages (SSE)
;;; ============================================================================

(defn- pump-sse-events!
  "Read SSE events off `reader`, feeding each event's data payload into
  `input-ch` as a parsed message. Returns when the stream ends or the
  stop flag is set."
  [^BufferedReader reader input-ch sse-stop*]
  (loop [data-lines []]
    (let [line (.readLine reader)]
      (cond (or (nil? line) @sse-stop*) nil
            ;; A blank line ends the current event.
            (str/blank? line) (do (when (seq data-lines)
                                    (let [msg (mcp.io-chan/json-str->message
                                                (str/join "\n" data-lines))]
                                      (when-not (= :parse-error msg)
                                        (async/>!! input-ch msg))))
                                  (recur []))
            (str/starts-with? line "data:")
              (recur (conj data-lines (str/trim (subs line 5))))
            ;; Ignore comments and other SSE fields.
            :else (recur data-lines)))))

(defn- start-sse-listener!
  "Open the SSE GET stream for the client's session and pump
  server-initiated messages into the client's input channel."
  [client]
  (let [{:keys [url session-id* sse-stop* sse-resp*]} (:transport client)
        input-ch (:input-ch client)
        session-id @session-id*]
    (async/thread
      (try (let [resp (http/get url
                                {:headers {"Accept" "text/event-stream",
                                           "Mcp-Session-Id" session-id},
                                 :as :stream,
                                 :throw-exceptions false})]
             (if (= 200 (:status resp))
               (do (reset! sse-resp* resp)
                   (with-open [reader (BufferedReader.
                                        (InputStreamReader.
                                          ^InputStream (:body resp)
                                          StandardCharsets/UTF_8))]
                     (pump-sse-events! reader input-ch sse-stop*)))
               (log/debug :fn :start-sse-listener!
                          :msg "SSE stream rejected"
                          :status (:status resp))))
           (catch Exception e
             (when-not @sse-stop*
               (log/debug :fn :start-sse-listener!
                          :msg "SSE stream closed"
                          :ex e)))))))

;;; ============================================================================
;;; High-Level API
;;; ============================================================================

(def ^:private callback-opts
  [:roots :on-progress :on-log :on-resource-updated :on-resource-list-changed
   :on-tool-list-changed :on-prompt-list-changed :sampling-handler])

(defn http-client
  "Create an MCP client connected to a server via Streamable HTTP."
  [client-info url opts]
  (log/trace :fn :http-client :client-info client-info :url url)
  (let [client (client/create-client client-info
                                     (select-keys opts callback-opts))
        input-ch (async/chan 16)
        output-ch (async/chan 16)
        session-id* (atom nil)
        connected-client (client/connect! client input-ch output-ch)]
    (start-sender! url session-id* input-ch output-ch)
    (assoc connected-client
      :transport {:url url,
                  :session-id* session-id*,
                  :sse-stop* (atom false),
                  :sse-resp* (atom nil)})))

(defn session-id
  "Return the session id assigned by the server (nil before initialize)."
  [client]
  (some-> client
          :transport
          :session-id*
          deref))

(defn shutdown!
  "Shutdown the HTTP client: terminate the server session (DELETE), stop
  the SSE listener, and shut down the jsonrpc endpoint."
  [client]
  (log/trace :fn :shutdown!)
  (let [{:keys [url session-id* sse-stop* sse-resp*]} (:transport client)]
    (reset! sse-stop* true)
    (when-let [sid @session-id*]
      (try (http/delete url
                        {:headers {"Mcp-Session-Id" sid},
                         :throw-exceptions false})
           (catch Exception e
             (log/debug :fn :shutdown! :msg "DELETE failed" :ex e))))
    (when-let [^InputStream stream (:body @sse-resp*)]
      (try (.close stream) (catch Exception _ nil)))
    (client/shutdown! client)))

(defn run!
  "Create, connect, initialize, and start an MCP client over HTTP.

  Args:

  - client-info: Map of :name and :version identifying the client

  - url: The MCP endpoint URL, e.g. \"http://127.0.0.1:8080/mcp\"

  - opts: Map of :timeout-ms (default 30000) plus the callback opts
  understood by `io.modelcontext.clojure-sdk.client/create-client`
  (:roots, :on-progress, :on-log, :on-resource-updated,
  :on-resource-list-changed, :on-tool-list-changed,
  :on-prompt-list-changed, :sampling-handler).

  Returns {:client client} on success or {:error {...}} on failure."
  [client-info url {:keys [timeout-ms], :or {timeout-ms 30000}, :as opts}]
  (log/trace :fn :run! :client-info client-info :url url)
  (try (let [client (http-client client-info url opts)
             _join (client/start! client)
             init-pending (client/initialize! client)
             init-result
               (client/deref-or-cancel init-pending timeout-ms ::timeout)]
         (cond (= ::timeout init-result)
                 (do (log/error :fn :run! :error :timeout)
                     (shutdown! client)
                     {:error {:code -32603,
                              :message "Initialization timed out"}})
               (:error init-result) (do (log/error :fn :run! :error init-result)
                                        (shutdown! client)
                                        {:error (:error init-result)})
               :else (do (client/process-initialize-result! client init-result)
                         (client/initialized! client)
                         (start-sse-listener! client)
                         (log/info :fn :run!
                                   :msg "Client initialized successfully"
                                   :server-info (:serverInfo init-result))
                         {:client client})))
       (catch Exception e
         (log/error :fn :run! :exception e)
         {:error {:code -32603,
                  :message (.getMessage e),
                  :data {:exception-class (.getName (class e))}}})))

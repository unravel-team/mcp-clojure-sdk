(ns io.modelcontext.clojure-sdk.client
  "MCP Client implementation."
  (:require [clojure.core.async :as async]
            [io.modelcontext.clojure-sdk.specs :as specs]
            [jsonrpc4clj.coercer :as coercer]
            [jsonrpc4clj.server :as jsonrpc.server]
            [me.vedang.logger.interface :as log]))

(set! *warn-on-reflection* true)

;;; ============================================================================
;;; Client State Management
;;; ============================================================================

(defn- initial-state
  "Create the initial state for a new client."
  [client-info opts]
  {:client-info client-info,
   :server-info nil,
   :server-capabilities nil,
   :protocol-version nil,
   :initialized? false,
   :roots (or (:roots opts) []),
   :subscriptions #{},
   :on-progress (:on-progress opts),
   :on-log (:on-log opts),
   :on-resource-updated (:on-resource-updated opts),
   :on-resource-list-changed (:on-resource-list-changed opts),
   :on-tool-list-changed (:on-tool-list-changed opts),
   :on-prompt-list-changed (:on-prompt-list-changed opts),
   :sampling-handler (:sampling-handler opts)})

(defn- get-client-capabilities
  "Build the client capabilities map based on configured options."
  [state]
  (cond-> {}
    (seq (:roots state)) (assoc :roots {:listChanged true})
    (:sampling-handler state) (assoc :sampling {})))

;;; ============================================================================
;;; Spec Validation Helpers
;;; ============================================================================

(defmacro conform-or-log
  "Provides log function for conformation, while preserving line numbers."
  [spec value]
  (let [fmeta (assoc (meta &form)
                :file *file*
                :ns-str (str *ns*))]
    `(coercer/conform-or-log
       (fn [& args#]
         (cond (= 2 (count args#)) (log/error :msg (first args#)
                                              :explain (second args#)
                                              :meta ~fmeta)
               (= 4 (count args#)) (log/error :ex (first args#)
                                              :msg (second args#)
                                              :spec ~spec
                                              :value ~value
                                              :meta ~fmeta)
               :else (throw (ex-info "Unknown Conform Error" :args args#))))
       ~spec
       ~value)))

;;; ============================================================================
;;; Client Creation
;;; ============================================================================

(defn create-client
  "Create a new MCP client."
  [client-info & {:as opts}]
  (log/trace :fn :create-client :client-info client-info)
  {:state (atom (initial-state client-info opts)),
   :endpoint nil,
   :process nil,
   :input-ch nil,
   :output-ch nil,
   :log-ch nil})

;;; ============================================================================
;;; Connection and Lifecycle
;;; ============================================================================

(defn connect!
  "Connect the client to input/output channels."
  [client input-ch output-ch]
  (log/trace :fn :connect!)
  (let [log-ch (async/chan (async/sliding-buffer 20))
        endpoint (jsonrpc.server/chan-server
                   {:input-ch input-ch, :output-ch output-ch, :log-ch log-ch})]
    (assoc client
      :endpoint endpoint
      :input-ch input-ch
      :output-ch output-ch
      :log-ch log-ch)))

(defn- monitor-client-logs
  [log-ch]
  (async/go-loop []
    (when-let [log-args (async/<! log-ch)]
      (log/trace :level (first log-args) :args (rest log-args))
      (recur))))

(defn start!
  "Start the client endpoint."
  [client]
  (log/trace :fn :start!)
  (monitor-client-logs (:log-ch client))
  (jsonrpc.server/start (:endpoint client) {:client client}))

(defn shutdown!
  "Shutdown the client connection."
  [client]
  (log/trace :fn :shutdown!)
  (when-let [process (:process client)] (.destroy ^Process process))
  (when-let [endpoint (:endpoint client)] (jsonrpc.server/shutdown endpoint)))

;;; ============================================================================
;;; MCP Protocol: Initialization
;;; ============================================================================

(defn initialize!
  "Initialize the connection with the server."
  [client]
  (log/trace :fn :initialize!)
  (let [state @(:state client)
        params {:protocolVersion (first specs/supported-protocol-versions),
                :capabilities (get-client-capabilities state),
                :clientInfo (:client-info state)}]
    (conform-or-log ::specs/initialize-request params)
    (jsonrpc.server/send-request (:endpoint client) "initialize" params)))

(defn process-initialize-result!
  "Process the result of an initialize request, updating client state."
  [client result]
  (swap! (:state client) assoc
    :server-info (:serverInfo result)
    :server-capabilities (:capabilities result)
    :protocol-version (:protocolVersion result))
  result)

(defn initialized!
  "Send the initialized notification to the server."
  [client]
  (log/trace :fn :initialized!)
  (jsonrpc.server/send-notification (:endpoint client)
                                    "notifications/initialized"
                                    {})
  (swap! (:state client) assoc :initialized? true)
  nil)

;;; ============================================================================
;;; MCP Protocol: Health Check
;;; ============================================================================

(defn ping!
  "Send a ping to the server."
  [client]
  (log/trace :fn :ping!)
  (jsonrpc.server/send-request (:endpoint client) "ping" {}))

;;; ============================================================================
;;; MCP Protocol: Tools
;;; ============================================================================

(defn list-tools!
  "Request the list of tools from the server."
  [client & {:keys [cursor]}]
  (log/trace :fn :list-tools! :cursor cursor)
  (let [params (cond-> {} cursor (assoc :cursor cursor))]
    (jsonrpc.server/send-request (:endpoint client) "tools/list" params)))

(defn call-tool!
  "Call a tool on the server."
  [client name arguments]
  (log/trace :fn :call-tool! :name name :arguments arguments)
  (let [params (cond-> {:name name} arguments (assoc :arguments arguments))]
    (conform-or-log ::specs/call-tool-request params)
    (jsonrpc.server/send-request (:endpoint client) "tools/call" params)))

;;; ============================================================================
;;; MCP Protocol: Resources
;;; ============================================================================

(defn list-resources!
  "Request the list of resources from the server."
  [client & {:keys [cursor]}]
  (log/trace :fn :list-resources! :cursor cursor)
  (let [params (cond-> {} cursor (assoc :cursor cursor))]
    (jsonrpc.server/send-request (:endpoint client) "resources/list" params)))

(defn list-resource-templates!
  "Request the list of resource templates from the server."
  [client & {:keys [cursor]}]
  (log/trace :fn :list-resource-templates! :cursor cursor)
  (let [params (cond-> {} cursor (assoc :cursor cursor))]
    (jsonrpc.server/send-request (:endpoint client)
                                 "resources/templates/list"
                                 params)))

(defn read-resource!
  "Read a resource from the server."
  [client uri]
  (log/trace :fn :read-resource! :uri uri)
  (jsonrpc.server/send-request (:endpoint client) "resources/read" {:uri uri}))

(defn subscribe!
  "Subscribe to updates for a resource."
  [client uri]
  (log/trace :fn :subscribe! :uri uri)
  (swap! (:state client) update :subscriptions conj uri)
  (jsonrpc.server/send-request (:endpoint client)
                               "resources/subscribe"
                               {:uri uri}))

(defn unsubscribe!
  "Unsubscribe from updates for a resource."
  [client uri]
  (log/trace :fn :unsubscribe! :uri uri)
  (swap! (:state client) update :subscriptions disj uri)
  (jsonrpc.server/send-request (:endpoint client)
                               "resources/unsubscribe"
                               {:uri uri}))

;;; ============================================================================
;;; MCP Protocol: Prompts
;;; ============================================================================

(defn list-prompts!
  "Request the list of prompts from the server."
  [client & {:keys [cursor]}]
  (log/trace :fn :list-prompts! :cursor cursor)
  (let [params (cond-> {} cursor (assoc :cursor cursor))]
    (jsonrpc.server/send-request (:endpoint client) "prompts/list" params)))

(defn get-prompt!
  "Get a prompt from the server."
  [client name & {:keys [arguments]}]
  (log/trace :fn :get-prompt! :name name :arguments arguments)
  (let [params (cond-> {:name name} arguments (assoc :arguments arguments))]
    (conform-or-log ::specs/get-prompt-request params)
    (jsonrpc.server/send-request (:endpoint client) "prompts/get" params)))

;;; ============================================================================
;;; MCP Protocol: Completion & Logging
;;; ============================================================================

(defn complete!
  "Request completion suggestions from the server."
  [client ref argument-name argument-value]
  (log/trace :fn :complete! :ref ref :arg-name argument-name)
  (let [params {:ref ref,
                :argument {:name argument-name, :value argument-value}}]
    (conform-or-log ::specs/complete-request params)
    (jsonrpc.server/send-request (:endpoint client)
                                 "completion/complete"
                                 params)))

(defn set-logging-level!
  "Set the logging level for server messages."
  [client level]
  (log/trace :fn :set-logging-level! :level level)
  (jsonrpc.server/send-request (:endpoint client)
                               "logging/setLevel"
                               {:level level}))

;;; ============================================================================
;;; Client Notifications (Client -> Server)
;;; ============================================================================

(defn notify-cancelled!
  "Send a cancellation notification for a request."
  [client request-id & {:keys [reason]}]
  (log/trace :fn :notify-cancelled! :request-id request-id)
  (let [params (cond-> {:requestId request-id} reason (assoc :reason reason))]
    (jsonrpc.server/send-notification (:endpoint client)
                                      "notifications/cancelled"
                                      params)))

(defn notify-roots-list-changed!
  "Notify the server that the roots list has changed."
  [client]
  (log/trace :fn :notify-roots-list-changed!)
  (jsonrpc.server/send-notification (:endpoint client)
                                    "notifications/roots/list_changed"
                                    {}))

;;; ============================================================================
;;; Server-Initiated Request Handlers
;;; ============================================================================

(defmethod jsonrpc.server/receive-request "ping"
  [_method _context params]
  (log/trace :fn :receive-request :method "ping" :params params)
  (conform-or-log ::specs/ping-request params)
  ;; The schema requires a Result object, so return an empty map rather
  ;; than a bare string.
  {})

(defmethod jsonrpc.server/receive-request "roots/list"
  [_method {:keys [client]} params]
  (if client
    (do (log/trace :fn :client-receive-request :method "roots/list")
        (conform-or-log ::specs/list-roots-request params)
        {:roots (:roots @(:state client))})
    ::jsonrpc.server/method-not-found))

(defmethod jsonrpc.server/receive-request "sampling/createMessage"
  [_method {:keys [client]} params]
  (if client
    (do (log/trace :fn :client-receive-request :method "sampling/createMessage")
        (conform-or-log ::specs/sampling-create-message-request params)
        (if-let [handler (:sampling-handler @(:state client))]
          (try (handler params)
               (catch Exception e
                 (log/error :fn :sampling-handler :ex e)
                 {:error {:code -32603,
                          :message (str "Sampling handler error: "
                                        (.getMessage e))}}))
          {:error {:code -32601, :message "Client does not support sampling"}}))
    ::jsonrpc.server/method-not-found))

;;; ============================================================================
;;; Server-Initiated Notification Handlers
;;; ============================================================================

(defmethod jsonrpc.server/receive-notification "notifications/cancelled"
  [_method {:keys [client]} params]
  (if client
    (do (conform-or-log ::specs/cancelled-notification params)
        (log/trace :fn :client-receive-notification
                   :method "notifications/cancelled"
                   :request-id (:requestId params)))
    ::jsonrpc.server/method-not-found))

(defmethod jsonrpc.server/receive-notification "notifications/progress"
  [_method {:keys [client]} params]
  (if client
    (do (conform-or-log ::specs/progress-notification params)
        (log/trace :fn :client-receive-notification
                   :method "notifications/progress")
        (when-let [on-progress (:on-progress @(:state client))]
          (on-progress (:progressToken params)
                       (:progress params)
                       (:total params)
                       (:message params))))
    ::jsonrpc.server/method-not-found))

(defmethod jsonrpc.server/receive-notification "notifications/message"
  [_method {:keys [client]} params]
  (if client
    (do (conform-or-log ::specs/logging-message-notification params)
        (log/trace :fn :client-receive-notification
                   :method "notifications/message")
        (when-let [on-log (:on-log @(:state client))]
          (on-log (:level params) (:logger params) (:data params))))
    ::jsonrpc.server/method-not-found))

(defmethod jsonrpc.server/receive-notification "notifications/resources/updated"
  [_method {:keys [client]} params]
  (if client
    (do (conform-or-log ::specs/resource-updated-notification params)
        (log/trace :fn :client-receive-notification
                   :method "notifications/resources/updated"
                   :uri (:uri params))
        (when-let [on-resource-updated (:on-resource-updated @(:state client))]
          (on-resource-updated (:uri params))))
    ::jsonrpc.server/method-not-found))

(defmethod jsonrpc.server/receive-notification
  "notifications/resources/list_changed"
  [_method {:keys [client]} _params]
  (if client
    (do (log/trace :fn :client-receive-notification
                   :method "notifications/resources/list_changed")
        (when-let [callback (:on-resource-list-changed @(:state client))]
          (callback)))
    ::jsonrpc.server/method-not-found))

(defmethod jsonrpc.server/receive-notification
  "notifications/tools/list_changed"
  [_method {:keys [client]} _params]
  (if client
    (do (log/trace :fn :client-receive-notification
                   :method "notifications/tools/list_changed")
        (when-let [callback (:on-tool-list-changed @(:state client))]
          (callback)))
    ::jsonrpc.server/method-not-found))

(defmethod jsonrpc.server/receive-notification
  "notifications/prompts/list_changed"
  [_method {:keys [client]} _params]
  (if client
    (do (log/trace :fn :client-receive-notification
                   :method "notifications/prompts/list_changed")
        (when-let [callback (:on-prompt-list-changed @(:state client))]
          (callback)))
    ::jsonrpc.server/method-not-found))

;;; ============================================================================
;;; Convenience Functions
;;; ============================================================================

(defn deref-or-cancel
  "Deref a pending request with timeout, cancelling if timeout is reached."
  [pending-request timeout-ms timeout-val]
  (jsonrpc.server/deref-or-cancel pending-request timeout-ms timeout-val))

(defn server-info
  "Get the server info from an initialized client."
  [client]
  (:server-info @(:state client)))

(defn server-capabilities
  "Get the server capabilities from an initialized client."
  [client]
  (:server-capabilities @(:state client)))

(defn initialized?
  "Check if the client has completed initialization."
  [client]
  (:initialized? @(:state client)))

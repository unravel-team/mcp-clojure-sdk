(ns io.modelcontext.clojure-sdk.server
  (:require [clojure.core.async :as async]
            [io.modelcontext.clojure-sdk.mcp.errors :as mcp.errors]
            [io.modelcontext.clojure-sdk.specs :as specs]
            [jsonrpc4clj.coercer :as coercer]
            [jsonrpc4clj.server :as jsonrpc.server]
            [me.vedang.logger.interface :as log]
            [promesa.core :as p]))

;; [tag: async_request_handlers]
;;
;; jsonrpc4clj delivers a response whenever the handler's return value
;; resolves: plain values respond immediately, futures/promises respond
;; on completion. Handlers that invoke user code (tool/prompt/resource/
;; completion handlers) run inside `eventually` (a promesa thread) so a
;; slow handler never blocks the message loop — other requests (ping,
;; lists, parallel tool calls) keep being served concurrently.
(defmacro eventually [& body] `(p/thread ~@body))

;;; Helper functions
;; Logging and Spec Checking
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

;;; Request Handlers

(defn store-client-info!
  [context client-info client-capabilities]
  (let [client-id (random-uuid)]
    (swap! (:connected-clients context) assoc
      client-id
      {:client-info client-info, :capabilities client-capabilities})
    client-id))

(defn- supported-protocol-version
  "Return the version of MCP protocol as part of connection initialization."
  [version]
  ;; [ref: version_negotiation]
  (if ((set specs/supported-protocol-versions) version)
    version
    (first specs/supported-protocol-versions)))

(defn- handle-initialize
  [context params]
  (let [client-info (:clientInfo params)
        client-capabilities (:capabilities params)
        server-info (:server-info context)
        server-capabilities @(:capabilities context)
        client-id (store-client-info! context client-info client-capabilities)]
    (log/trace :fn :handle-initialize
               :msg "[Initialize] Client connected!"
               :client-info client-info
               :client-id client-id)
    {:protocolVersion (supported-protocol-version (:protocolVersion params)),
     :capabilities server-capabilities,
     :serverInfo server-info}))

(defn- handle-ping
  [_context _params]
  (log/trace :fn :handle-ping)
  ;; The schema requires a Result object, so return an empty map rather
  ;; than a bare string.
  {})

(defn- handle-list-tools
  [context _params]
  (log/trace :fn :handle-list-tools)
  {:tools (mapv :tool (vals @(:tools context)))})

(defn coerce-tool-response
  "Coerces a tool response into the expected format.
   A map containing :content is treated as a complete CallToolResult and
   passed through untouched, so handlers can set :isError (tool-level
   failures the LLM should see) or :structuredContent themselves.
   Otherwise: if the response is not sequential, wraps it in a vector.
   If the tool has an outputSchema, adds structuredContent."
  [tool response]
  (if (and (map? response) (contains? response :content))
    response
    (let [response (if (sequential? response) (vec response) [response])
          base-map {:content response}]
      ;; @TODO: [ref:
      ;; structured-content-should-match-output-schema-exactly]
      (cond-> base-map
        (:outputSchema tool) (assoc :structuredContent response)))))

(defn- handle-call-tool
  [context params]
  (log/trace :fn :handle-call-tool
             :tool (:name params)
             :args (:arguments params))
  (let [tools @(:tools context)
        tool-name (:name params)
        arguments (:arguments params)]
    (if-let [{:keys [tool handler]} (get tools tool-name)]
      (try (coerce-tool-response tool (handler arguments))
           (catch Exception e
             {:content [{:type "text", :text (str "Error: " (.getMessage e))}],
              :isError true}))
      (do
        (log/debug :fn :handle-call-tool :tool tool-name :error :tool-not-found)
        {:error (mcp.errors/body :tool-not-found {:tool-name tool-name})}))))

(defn- handle-list-resources
  [context _params]
  (log/trace :fn :handle-list-resources)
  {:resources (mapv :resource (vals @(:resources context)))})

(defn- handle-read-resource
  [context params]
  (log/trace :fn :handle-read-resource :resource (:uri params))
  (let [resources @(:resources context)
        uri (:uri params)]
    (if-let [{:keys [handler]} (get resources uri)]
      ;; A handler may return a single contents map or a sequence of
      ;; them (a resource can have multiple contents per the schema).
      (let [result (handler uri)]
        {:contents (if (sequential? result) (vec result) [result])})
      (do (log/debug :fn :handle-read-resource
                     :resource uri
                     :error :resource-not-found)
          {:error (mcp.errors/body :resource-not-found {:uri uri})}))))

(defn- handle-list-prompts
  [context _params]
  (log/trace :fn :handle-list-prompts)
  {:prompts (mapv :prompt (vals @(:prompts context)))})

(defn- handle-get-prompt
  [context params]
  (log/trace :fn :handle-get-prompt
             :prompt (:name params)
             :args (:arguments params))
  (let [prompts @(:prompts context)
        prompt-name (:name params)
        arguments (:arguments params)]
    (if-let [{:keys [handler]} (get prompts prompt-name)]
      (handler arguments)
      (do (log/debug :fn :handle-get-prompt
                     :prompt prompt-name
                     :error :prompt-not-found)
          {:error (mcp.errors/body :prompt-not-found
                                   {:prompt-name prompt-name})}))))

(defn- handle-list-resource-templates
  [context _params]
  (log/trace :fn :handle-list-resource-templates)
  {:resourceTemplates (mapv identity (vals @(:resource-templates context)))})

(defn- handle-subscribe-resource
  [context params]
  (log/trace :fn :handle-subscribe-resource :resource (:uri params))
  (swap! (:subscriptions context) conj (:uri params))
  {})

(defn- handle-unsubscribe-resource
  [context params]
  (log/trace :fn :handle-unsubscribe-resource :resource (:uri params))
  (swap! (:subscriptions context) disj (:uri params))
  {})

(defn- handle-set-logging-level
  [context params]
  (log/trace :fn :handle-set-logging-level :level (:level params))
  (reset! (:log-level context) (:level params))
  {})

(defn- handle-complete
  [context params]
  (log/trace :fn :handle-complete
             :ref (:ref params)
             :argument (:argument params))
  (let [completions @(:completions context)]
    (if-let [handler (get completions (:ref params))]
      {:completion (handler (:argument params))}
      (do (log/debug :fn :handle-complete
                     :ref (:ref params)
                     :msg "No completion handler registered for ref")
          {:completion {:values [], :total 0, :hasMore false}}))))

;;; Protocol: Requests and Notifications

;; [ref: initialize_request]
(defmethod jsonrpc.server/receive-request "initialize"
  [_ context params]
  (log/trace :fn :receive-request :method "initialize" :params params)
  ;; [tag: log_bad_input_params]
  ;;
  ;; If the input is non-conformant, we should log it. But we shouldn't
  ;; take any other action. The principle we want to follow is Postel's
  ;; law: https://en.wikipedia.org/wiki/Robustness_principle
  (conform-or-log ::specs/initialize-request params)
  (->> params
       (handle-initialize context)
       (conform-or-log ::specs/initialize-response)))

;; [ref: initialized_notification]
(defmethod jsonrpc.server/receive-notification "notifications/initialized"
  [_ _ params]
  (conform-or-log ::specs/initialized-notification params))

;; [ref: ping_request]
(defmethod jsonrpc.server/receive-request "ping"
  [_ context params]
  (log/trace :fn :receive-request :method "ping" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/ping-request params)
  (->> params
       (handle-ping context)))

;; [ref: list_tools_request]
(defmethod jsonrpc.server/receive-request "tools/list"
  [_ context params]
  (log/trace :fn :receive-request :method "tools/list" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/list-tools-request params)
  (->> params
       (handle-list-tools context)
       (conform-or-log ::specs/list-tools-response)))

;; [ref: call_tool_request]
(defmethod jsonrpc.server/receive-request "tools/call"
  [_ context params]
  (log/trace :fn :receive-request :method "tools/call" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/call-tool-request params)
  ;; [ref: async_request_handlers]
  (eventually (->> params
                   (handle-call-tool context)
                   (conform-or-log ::specs/call-tool-response))))

;; [ref: list_resources_request]
(defmethod jsonrpc.server/receive-request "resources/list"
  [_ context params]
  (log/trace :fn :receive-request :method "resources/list" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/list-resources-request params)
  (->> params
       (handle-list-resources context)
       (conform-or-log ::specs/list-resources-response)))

;; [ref: read_resource_request]
(defmethod jsonrpc.server/receive-request "resources/read"
  [_ context params]
  (log/trace :fn :receive-request :method "resources/read" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/read-resource-request params)
  ;; [ref: async_request_handlers]
  (eventually (->> params
                   (handle-read-resource context)
                   (conform-or-log ::specs/read-resource-response))))

;; [ref: list_prompts_request]
(defmethod jsonrpc.server/receive-request "prompts/list"
  [_ context params]
  (log/trace :fn :receive-request :method "prompts/list" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/list-prompts-request params)
  (->> params
       (handle-list-prompts context)
       (conform-or-log ::specs/list-prompts-response)))

;; [ref: get_prompt_request]
(defmethod jsonrpc.server/receive-request "prompts/get"
  [_ context params]
  (log/trace :fn :receive-request :method "prompts/get" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/get-prompt-request params)
  ;; [ref: async_request_handlers]
  (eventually (->> params
                   (handle-get-prompt context)
                   (conform-or-log ::specs/get-prompt-response))))

;; [ref: list_resource_templates_request]
(defmethod jsonrpc.server/receive-request "resources/templates/list"
  [_ context params]
  (log/trace :fn :receive-request
             :method "resources/templates/list"
             :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/list-resource-templates-request params)
  (->> params
       (handle-list-resource-templates context)
       (conform-or-log ::specs/list-resource-templates-response)))

;; [ref: resource_subscribe_unsubscribe_request]
(defmethod jsonrpc.server/receive-request "resources/subscribe"
  [_ context params]
  (log/trace :fn :receive-request :method "resources/subscribe" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/resource-subscribe-unsubscribe-request params)
  (handle-subscribe-resource context params))

;; [ref: resource_subscribe_unsubscribe_request]
(defmethod jsonrpc.server/receive-request "resources/unsubscribe"
  [_ context params]
  (log/trace :fn :receive-request
             :method "resources/unsubscribe"
             :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/resource-subscribe-unsubscribe-request params)
  (handle-unsubscribe-resource context params))

;; [ref: set_logging_level_request]
(defmethod jsonrpc.server/receive-request "logging/setLevel"
  [_ context params]
  (log/trace :fn :receive-request :method "logging/setLevel" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/set-logging-level-request params)
  (handle-set-logging-level context params))

;; [ref: complete_request]
(defmethod jsonrpc.server/receive-request "completion/complete"
  [_ context params]
  (log/trace :fn :receive-request :method "completion/complete" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/complete-request params)
  ;; [ref: async_request_handlers]
  (eventually (->> params
                   (handle-complete context)
                   (conform-or-log ::specs/complete-response))))

;; [ref: cancelled_notification]
(defmethod jsonrpc.server/receive-notification "notifications/cancelled"
  [_method _context params]
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/cancelled-notification params)
  ;; This notification indicates that the result of the request will be
  ;; unused. We log the cancellation; any associated processing SHOULD
  ;; cease.
  (log/debug :fn :receive-notification
             :method "notifications/cancelled"
             :request-id (:requestId params)
             :reason (:reason params))
  nil)

;; @TODO: Implement send-notification "notifications/cancelled" when request is
;; cancelled

;; [ref: progress_notification]
(defmethod jsonrpc.server/receive-notification "notifications/progress"
  [_method context params]
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/progress-notification params)
  (log/trace :fn :receive-notification
             :method "notifications/progress"
             :params params)
  (when (fn? (:on-progress context)) ((:on-progress context) params))
  nil)

;;; Server -> Client Notifications and Requests

;; [ref: tool_list_changed_notification]
(defn notify-tools-list-changed!
  "Notify the client that the list of tools available on the server has
  changed.

  Args:

  - server: The jsonrpc server endpoint (see `chan-server`,
  `stdio-server`)."
  [server]
  (log/trace :fn :notify-tools-list-changed!)
  (jsonrpc.server/send-notification server
                                    "notifications/tools/list_changed"
                                    {}))

;; [ref: resource_list_changed_notification]
(defn notify-resources-list-changed!
  "Notify the client that the list of resources available on the server
  has changed.

  Args:

  - server: The jsonrpc server endpoint (see `chan-server`,
  `stdio-server`)."
  [server]
  (log/trace :fn :notify-resources-list-changed!)
  (jsonrpc.server/send-notification server
                                    "notifications/resources/list_changed"
                                    {}))

;; [ref: prompt_list_changed_notification]
(defn notify-prompts-list-changed!
  "Notify the client that the list of prompts available on the server has
  changed.

  Args:

  - server: The jsonrpc server endpoint (see `chan-server`,
  `stdio-server`)."
  [server]
  (log/trace :fn :notify-prompts-list-changed!)
  (jsonrpc.server/send-notification server
                                    "notifications/prompts/list_changed"
                                    {}))

;; [ref: resource_updated_notification]
(defn notify-resource-updated!
  "Notify the client that a resource it subscribed to has been updated.

  Only sends the notification when the client previously subscribed to
  the URI through resources/subscribe. Returns nil otherwise.

  Args:

  - server: The jsonrpc server endpoint (see `chan-server`,
  `stdio-server`)

  - context: Map containing all state for the current server. See:
  `create-empty-context`

  - uri: The URI of the updated resource."
  [server context uri]
  (log/trace :fn :notify-resource-updated! :resource uri)
  (when (contains? @(:subscriptions context) uri)
    (jsonrpc.server/send-notification server
                                      "notifications/resources/updated"
                                      {:uri uri})))

;; The severity of a log message. These map to syslog message severities,
;; as specified in RFC-5424. [ref: logging_message_notification]
(def ^:private log-level->severity
  (zipmap ["debug" "info" "notice" "warning" "error" "critical" "alert"
           "emergency"]
          (range)))

;; [ref: logging_message_notification]
(defn notify-log-message!
  "Send a log message notification to the client.

  Respects the minimum log level set by the client through
  logging/setLevel: messages with a severity lower than the configured
  level are suppressed. If no level has been set, the message is sent.

  Args:

  - server: The jsonrpc server endpoint (see `chan-server`,
  `stdio-server`)

  - context: Map containing all state for the current server. See:
  `create-empty-context`

  - level: The severity of the message, one of the RFC-5424 levels
  (\"debug\", \"info\", \"notice\", \"warning\", \"error\", \"critical\",
  \"alert\", \"emergency\")

  - data: The data to be logged. Any JSON-serializable value.

  - opts: Optional map of:
    :logger - An optional name of the logger issuing this message."
  ([server context level data]
   (notify-log-message! server context level data {}))
  ([server context level data {:keys [logger]}]
   (log/trace :fn :notify-log-message! :level level :logger logger)
   (let [min-level @(:log-level context)]
     (when (or (nil? min-level)
               (>= (log-level->severity level -1)
                   (log-level->severity min-level -1)))
       (jsonrpc.server/send-notification server
                                         "notifications/message"
                                         (cond-> {:level level, :data data}
                                           logger (assoc :logger logger)))))))

;; [ref: progress_notification]
(defn notify-progress!
  "Send a progress notification to the client for a long-running request.

  Args:

  - server: The jsonrpc server endpoint (see `chan-server`,
  `stdio-server`)

  - token: The progress token of the original request

  - progress: The progress thus far. This should increase every time
  progress is made, even if the total is unknown.

  - opts: Optional map of:
    :total   - Total number of items to process, if known
    :message - An optional message describing the current progress."
  ([server token progress] (notify-progress! server token progress {}))
  ([server token progress {:keys [total message]}]
   (log/trace :fn :notify-progress! :token token :progress progress)
   (jsonrpc.server/send-notification server
                                     "notifications/progress"
                                     (cond-> {:progressToken token,
                                              :progress progress}
                                       total (assoc :total total)
                                       message (assoc :message message)))))

;; method: "roots/list"
(defn request-roots!
  "Request the list of root URIs from the client.

  Returns the pending request, which can be deref-ed for the response.

  Args:

  - server: The jsonrpc server endpoint (see `chan-server`,
  `stdio-server`)."
  [server]
  (log/trace :fn :request-roots!)
  (jsonrpc.server/send-request server "roots/list" {}))

;; method: "sampling/createMessage"
(defn request-sampling!
  "Request the client to sample an LLM on the server's behalf.

  Returns the pending request, which can be deref-ed for the response.

  Args:

  - server: The jsonrpc server endpoint (see `chan-server`,
  `stdio-server`)

  - params: The sampling request params, containing :messages,
  :maxTokens and other optional keys. See:
  `::specs/sampling-create-message-request`."
  [server params]
  (log/trace :fn :request-sampling! :params params)
  (jsonrpc.server/send-request server "sampling/createMessage" params))

;;; Server Spec Implementation

(defn validate-spec!
  [server-spec]
  (when-not (specs/valid-server-spec? server-spec)
    (let [msg "Invalid server-spec definition"]
      (log/debug :msg msg :spec server-spec)
      (throw (ex-info msg (specs/explain-server-spec server-spec)))))
  server-spec)

(defn register-tool!
  "Register a tool against the MCP server.

  Args:

  - context: Map containing all state for the current server. See:
  `create-empty-context`

  - tool: Map defining the actual tool definition as understood by the
  MCP spec. It contains the following keys:
    :name         - The name of the tool
    :description  - A description of what the tool does
    :inputSchema  - JSON schema for the tool's input parameters
                    {:type \"object\" :properties {...} :required [...]}
                    See: [ref: tool_schema_definition]

  - handler: The function that implements the tool's logic. Signature:
     (fn [args-map] ... )
       * arg-map      - map with string keys representing the mcp tool call args"
  [context tool handler]
  (swap! (:tools context) assoc (:name tool) {:tool tool, :handler handler})
  ;; [ref: auto_list_changed_notifications]
  (when-let [server (some-> (:server* context)
                            deref)]
    (notify-tools-list-changed! server)))

(defn unregister-tool!
  "Remove the tool named `tool-name` from the MCP server. Notifies
  connected clients when the server is running.

  Args:

  - context: Map containing all state for the current server. See:
  `create-empty-context`

  - tool-name: The name the tool was registered under."
  [context tool-name]
  (swap! (:tools context) dissoc tool-name)
  ;; [ref: auto_list_changed_notifications]
  (when-let [server (some-> (:server* context)
                            deref)]
    (notify-tools-list-changed! server)))

(defn register-resource!
  "Register a resource against the MCP server.

  Args:

  - context: Map containing all state for the current server. See:
  `create-empty-context`

  - resource: Map defining the actual resource definition as understood by the
  MCP spec. It contains the following keys:
    :uri          - The URI of the resource
    :name         - The name of the resource
    :description  - A description of what the resource does
    :mimeType     - JSON schema for the resource's input parameters
                    {:type \"object\" :properties {...} :required [...]}
                    See: [ref: resource_schema_definition]

  - handler: The function that implements the resource's logic. Signature:
     (fn [uri] ... )
       * uri      - the same value as registered as the resource URI"
  [context resource handler]
  (swap! (:resources context) assoc
    (:uri resource)
    {:resource resource, :handler handler})
  ;; [ref: auto_list_changed_notifications]
  (when-let [server (some-> (:server* context)
                            deref)]
    (notify-resources-list-changed! server)))

(defn unregister-resource!
  "Remove the resource identified by `uri` from the MCP server. Notifies
  connected clients when the server is running.

  Args:

  - context: Map containing all state for the current server. See:
  `create-empty-context`

  - uri: The URI the resource was registered under."
  [context uri]
  (swap! (:resources context) dissoc uri)
  ;; [ref: auto_list_changed_notifications]
  (when-let [server (some-> (:server* context)
                            deref)]
    (notify-resources-list-changed! server)))

(defn register-prompt!
  "Register a prompt against the MCP server.

  Args:

  - context: Map containing all state for the current server. See:
  `create-empty-context`

  - prompt: Map defining the actual prompt definition as understood by the
  MCP spec. It contains the following keys:
    :name         - The name of the prompt
    :description  - A description of what the prompt does
    :arguments    - A vector of maps, each defining an argument:
                    {:name \"arg-name\" :description \"...\" :required? true/false}
                    See: [ref: prompt_schema_definition]

  - handler: The function that implements the prompt's logic. Signature:
     (fn [args-map] ... )
       * arg-map      - map with string keys representing the mcp prompt call args"
  [context prompt handler]
  (swap! (:prompts context) assoc
    (:name prompt)
    {:prompt prompt, :handler handler})
  ;; [ref: auto_list_changed_notifications]
  (when-let [server (some-> (:server* context)
                            deref)]
    (notify-prompts-list-changed! server)))

(defn unregister-prompt!
  "Remove the prompt named `prompt-name` from the MCP server. Notifies
  connected clients when the server is running.

  Args:

  - context: Map containing all state for the current server. See:
  `create-empty-context`

  - prompt-name: The name the prompt was registered under."
  [context prompt-name]
  (swap! (:prompts context) dissoc prompt-name)
  ;; [ref: auto_list_changed_notifications]
  (when-let [server (some-> (:server* context)
                            deref)]
    (notify-prompts-list-changed! server)))

(defn register-resource-template!
  "Register a resource template against the MCP server.

  Args:

  - context: Map containing all state for the current server. See:
  `create-empty-context`

  - template: Map defining the actual resource template definition as
  understood by the MCP spec. It contains the following keys:
    :uriTemplate  - A URI template (RFC 6570) that can be used to
                    construct resource URIs
    :name         - The name of the resource template
    :description  - A description of what the resource template is for
    :mimeType     - The MIME type for all resources that match this
                    template, if uniform.
                    See: [ref: list_resource_templates_request]"
  [context template]
  (swap! (:resource-templates context) assoc (:uriTemplate template) template))

(defn register-completion!
  "Register a completion handler against the MCP server.

  Args:

  - context: Map containing all state for the current server. See:
  `create-empty-context`

  - ref: Map identifying what the completion is for, as understood by
  the MCP spec. Either:
    {:type \"ref/prompt\" :name \"prompt-name\"}
    {:type \"ref/resource\" :uri \"resource-uri\"}
    See: [ref: complete_request]

  - handler: The function that implements the completion logic. Signature:
     (fn [argument] ... )
       * argument - map with :name and :value keys, representing the
         argument being completed and the value entered so far. The
         handler must return a map of the shape
         {:values [...] :total n :hasMore bool}"
  [context ref handler]
  (swap! (:completions context) assoc ref handler))

(defn- create-empty-context
  [name version capabilities]
  (log/trace :fn :create-empty-context)
  ;; [tag: context_must_be_a_map]
  ;;
  ;; Since so much of the state is "global" in nature, it's tempting to
  ;; just make the entire context global instead of defining atoms at each
  ;; key. However, do not do this!
  ;;
  ;; This context is passed to lsp4j, which expects the data-structure to
  ;; be `associative?` in nature and uses it further for it's own temporary
  ;; state.
  {:server-info {:name name, :version version},
   :tools (atom {}),
   :resources (atom {}),
   :resource-templates (atom {}),
   :prompts (atom {}),
   :subscriptions (atom #{}),
   :log-level (atom nil),
   :completions (atom {}),
   :protocol (atom nil),
   ;; [tag: auto_list_changed_notifications]
   ;;
   ;; Holds the jsonrpc server endpoint once `start!` runs. While a
   ;; server is attached, register-*!/unregister-*! automatically send
   ;; the matching list_changed notification, so live (un)registration
   ;; behaves like mcp-java-sdk's add/remove API. Registration during
   ;; `create-context!` happens before a server is attached and stays
   ;; silent. NOTE: The Streamable HTTP transport runs one endpoint per
   ;; session and does NOT set this atom; broadcast to HTTP sessions
   ;; explicitly via the notify-*-list-changed! senders.
   :server* (atom nil),
   ;; [tag: default_server_capabilities]
   ;;
   ;; The capabilities the server advertises by default, covering the
   ;; full protocol surface implemented in this namespace. Servers can
   ;; advertise a different set through the :capabilities key of the
   ;; server spec.
   :capabilities (atom (or capabilities
                           {:tools {:listChanged true},
                            :resources {:subscribe true, :listChanged true},
                            :prompts {:listChanged true},
                            :logging {},
                            :completions {}})),
   :connected-clients (atom {})})

(defn create-context!
  "Create and configure an MCP server from a configuration map.
   Config map should have the shape:
   {:name \"server-name\"
    :version \"1.0.0\"
    :tools [{:name \"tool-name\"
             :description \"Tool description\"
             :inputSchema {...}
             :handler (fn [args] ...)}]
    :prompts [{:name \"prompt-name\"
               :description \"Prompt description\"
               :handler (fn [args] ...)}]
    :resources [{:uri \"resource-uri\"
                 :type \"text\"
                 :handler (fn [uri] ...)}]
    :resource-templates [{:uriTemplate \"file:///{path}\"
                          :name \"Template name\"}]}

  For more details, see the doc-strings of `register-tool!`,
  `register-prompt!`, `register-resource!` and
  `register-resource-template!`."
  [{:keys [name version tools prompts resources resource-templates
           capabilities],
    :as spec}]
  (validate-spec! spec)
  (log/with-context {:action :create-context!}
    (let [context (create-empty-context name version capabilities)]
      (when (> (count tools) 0)
        (log/debug :num-tools (count tools)
                   :msg "Registering tools"
                   :server-info {:name name, :version version}))
      (doseq [tool tools]
        (register-tool! context (dissoc tool :handler) (:handler tool)))
      (when (> (count resources) 0)
        (log/debug :num-resources (count resources)
                   :msg "Registering resources"
                   :server-info {:name name, :version version}))
      (doseq [resource resources]
        (register-resource! context
                            (dissoc resource :handler)
                            (:handler resource)))
      (when (> (count resource-templates) 0)
        (log/debug :num-resource-templates (count resource-templates)
                   :msg "Registering resource templates"
                   :server-info {:name name, :version version}))
      (doseq [template resource-templates]
        (register-resource-template! context template))
      (when (> (count prompts) 0)
        (log/debug :num-prompts (count prompts)
                   :msg "Registering prompts"
                   :server-info {:name name, :version version}))
      (doseq [prompt prompts]
        (register-prompt! context (dissoc prompt :handler) (:handler prompt)))
      context)))

(defn start!
  [server context]
  (log/info :msg "[SERVER] Starting server...")
  ;; [ref: auto_list_changed_notifications]
  (when-let [server* (:server* context)] (reset! server* server))
  (jsonrpc.server/start server context))

(defn chan-server
  []
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)]
    (jsonrpc.server/chan-server {:output-ch output-ch, :input-ch input-ch})))

(ns io.modelcontext.clojure-sdk.server
  (:require [clojure.core.async :as async]
            [io.modelcontext.clojure-sdk.mcp.errors :as mcp.errors]
            [io.modelcontext.clojure-sdk.specs :as specs]
            [lsp4clj.coercer :as coercer]
            [lsp4clj.server :as lsp.server]
            [me.vedang.logger.interface :as log]))

;;; Helpers
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

;;; Helper functions for handling various requests

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

(defn- handle-ping [_context _params] (log/trace :fn :handle-ping) "pong")

(defn- handle-list-tools
  [context _params]
  (log/trace :fn :handle-list-tools)
  {:tools (mapv :tool (vals @(:tools context)))})

(defn coerce-tool-response
  "Coerces a tool response into the expected format.
   If the response is not sequential, wraps it in a vector.
   If the tool has an outputSchema, adds structuredContent."
  [tool response]
  (let [response (if (sequential? response) (vec response) [response])
        base-map {:content response}]
    ;; @TODO: [ref: structured-content-should-match-output-schema-exactly]
    (cond-> base-map (:outputSchema tool) (assoc :structuredContent response))))

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
      {:contents [(handler uri)]}
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

;;; Requests and Notifications

;; [ref: initialize_request]
(defmethod lsp.server/receive-request "initialize"
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
(defmethod lsp.server/receive-notification "notifications/initialized"
  [_ _ params]
  (conform-or-log ::specs/initialized-notification params))

;; [ref: ping_request]
(defmethod lsp.server/receive-request "ping"
  [_ context params]
  (log/trace :fn :receive-request :method "ping" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/ping-request params)
  (->> params
       (handle-ping context)))

;; [ref: list_tools_request]
(defmethod lsp.server/receive-request "tools/list"
  [_ context params]
  (log/trace :fn :receive-request :method "tools/list" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/list-tools-request params)
  (->> params
       (handle-list-tools context)
       (conform-or-log ::specs/list-tools-response)))

;; [ref: call_tool_request]
(defmethod lsp.server/receive-request "tools/call"
  [_ context params]
  (log/trace :fn :receive-request :method "tools/call" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/call-tool-request params)
  (->> params
       (handle-call-tool context)
       (conform-or-log ::specs/call-tool-response)))

;; [ref: list_resources_request]
(defmethod lsp.server/receive-request "resources/list"
  [_ context params]
  (log/trace :fn :receive-request :method "resources/list" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/list-resources-request params)
  (->> params
       (handle-list-resources context)
       (conform-or-log ::specs/list-resources-response)))

;; [ref: read_resource_request]
(defmethod lsp.server/receive-request "resources/read"
  [_ context params]
  (log/trace :fn :receive-request :method "resources/read" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/read-resource-request params)
  (->> params
       (handle-read-resource context)
       (conform-or-log ::specs/read-resource-response)))

;; [ref: list_prompts_request]
(defmethod lsp.server/receive-request "prompts/list"
  [_ context params]
  (log/trace :fn :receive-request :method "prompts/list" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/list-prompts-request params)
  (->> params
       (handle-list-prompts context)
       (conform-or-log ::specs/list-prompts-response)))

;; [ref: get_prompt_request]
(defmethod lsp.server/receive-request "prompts/get"
  [_ context params]
  (log/trace :fn :receive-request :method "prompts/get" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/get-prompt-request params)
  (->> params
       (handle-get-prompt context)
       (conform-or-log ::specs/get-prompt-response)))

;;; @TODO: Requests to Implement

;; [ref: list_resource_templates_request]
(defmethod lsp.server/receive-request "resources/templates/list"
  [_ _context params]
  (log/trace :fn :receive-request
             :method "resources/templates/list"
             :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/list-resource-templates-request params)
  (identity ::specs/list-resource-templates-response)
  ::lsp.server/method-not-found)

;; [ref: resource_subscribe_unsubscribe_request]
(defmethod lsp.server/receive-request "resources/subscribe"
  [_ _context params]
  (log/trace :fn :receive-request :method "resources/subscribe" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/resource-subscribe-unsubscribe-request params)
  ::lsp.server/method-not-found)

;; [ref: resource_subscribe_unsubscribe_request]
(defmethod lsp.server/receive-request "resources/unsubscribe"
  [_ _context params]
  (log/trace :fn :receive-request
             :method "resources/unsubscribe"
             :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/resource-subscribe-unsubscribe-request params)
  ::lsp.server/method-not-found)

;; [ref: set_logging_level_request]
(defmethod lsp.server/receive-request "logging/setLevel"
  [_ _context params]
  (log/trace :fn :receive-request :method "logging/setLevel" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/set-logging-level-request params)
  ::lsp.server/method-not-found)

;; [ref: complete_request]
(defmethod lsp.server/receive-request "completion/complete"
  [_ _context params]
  (log/trace :fn :receive-request :method "completion/complete" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/complete-request params)
  (identity ::specs/complete-response)
  ::lsp.server/method-not-found)

;;; @TODO: Notifications to Implement

;; [ref: cancelled_notification]
(defmethod lsp.server/receive-notification "notifications/cancelled"
  [_method _context _params]
  (identity ::specs/cancelled-notification)
  ::lsp.server/method-not-found)

;; @TODO: Implement send-notification "notifications/cancelled" when request is
;; cancelled

;; [ref: progress_notification]
(defmethod lsp.server/receive-notification "notifications/progress"
  [_method _context _params]
  (identity ::specs/progress-notification)
  ::lsp.server/method-not-found)

;; @TODO: Implement send-notification "notifications/progress" for long-lived
;; requests

;; @TODO: Implement [ref: resource_list_changed_notification] for when list of
;; resources available to the client changes.

;; @TODO: Implement [ref: resource_updated_notification] for when a resource is
;; updated at the server

;; @TODO: Implement [ref: prompt_list_changed_notification] for when list of
;; prompts available to the client changes.

;; @TODO: Implement [ref: tool_list_changed_notification] for when list of
;; tools available to the client changes.

;; @TODO: Implement [ref: logging_message_notification] for when server wants
;; to send a logging message to the client.

;;; Server Spec

(defn validate-spec!
  [server-spec]
  (when-not (specs/valid-server-spec? server-spec)
    (let [msg "Invalid server-spec definition"]
      (log/debug :msg msg :spec server-spec)
      (throw (ex-info msg (specs/explain-server-spec server-spec)))))
  server-spec)

(defn register-tool!
  [context tool handler]
  (swap! (:tools context) assoc (:name tool) {:tool tool, :handler handler}))

(defn register-resource!
  [context resource handler]
  (swap! (:resources context) assoc
    (:uri resource)
    {:resource resource, :handler handler}))

(defn register-prompt!
  [context prompt handler]
  (swap! (:prompts context) assoc
    (:name prompt)
    {:prompt prompt, :handler handler}))

(defn- create-empty-context
  [name version]
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
   :prompts (atom {}),
   :protocol (atom nil),
   :capabilities (atom {:tools {}, :resources {}, :prompts {}}),
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
                 :handler (fn [uri] ...)}]}"
  [{:keys [name version tools prompts resources], :as spec}]
  (validate-spec! spec)
  (log/with-context {:action :create-context!}
    (let [context (create-empty-context name version)]
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
  (lsp.server/start server context))

(defn chan-server
  []
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)]
    (lsp.server/chan-server {:output-ch output-ch, :input-ch input-ch})))

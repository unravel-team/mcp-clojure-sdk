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

(defn- handle-initialize
  [context params]
  (let [client-info (:clientInfo params)
        _client-capabilities (:capabilities params)
        server-info (:server-info context)
        server-capabilities @(:capabilities context)]
    (log/trace :fn :handle-initialize
               :msg "[Initialize] Client connected"
               :client client-info)
    {:protocolVersion specs/stable-protocol-version,
     :capabilities server-capabilities,
     :serverInfo server-info}))

(defn- handle-ping [_context _params] (log/trace :fn :handle-ping) "pong")

(defn- handle-list-tools
  [context _params]
  (log/debug :fn :handle-list-tools)
  {:tools (mapv :tool (vals @(:tools context)))})

(defn- handle-call-tool
  [context params]
  (log/debug :fn :handle-call-tool
             :tool (:name params)
             :args (:arguments params))
  (let [tools @(:tools context)
        tool-name (:name params)
        arguments (:arguments params)]
    (if-let [{:keys [handler]} (get tools tool-name)]
      (try {:content [(handler arguments)]}
           (catch Exception e
             {:content [{:type "text", :text (str "Error: " (.getMessage e))}],
              :isError true}))
      (do
        (log/debug :fn :handle-call-tool :tool tool-name :error :tool-not-found)
        {:error (mcp.errors/body :tool-not-found {:tool-name tool-name})}))))

(defn- handle-list-resources
  [context _params]
  (log/debug :fn :handle-list-resources)
  {:resources (mapv :resource (vals @(:resources context)))})

(defn- handle-read-resource
  [context params]
  (log/debug :fn :handle-read-resource :resource (:uri params))
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
  (log/debug :fn :handle-list-prompts)
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

(defmethod lsp.server/receive-request "tools/list"
  [_ context params]
  (log/trace :fn :receive-request :method "tools/list" :params params)
  (->> params
       (handle-list-tools context)
       (conform-or-log :response/list-tools-or-error)))

(defmethod lsp.server/receive-request "tools/call"
  [_ context params]
  (log/trace :fn :receive-request :method "tools/call" :params params)
  (->> params
       (handle-call-tool context)
       (conform-or-log :response/call-tool-or-error)))

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

(defmethod lsp.server/receive-request "prompts/list"
  [_ context params]
  (log/trace :fn :receive-request :method "prompts/list" :params params)
  (->> params
       (handle-list-prompts context)
       (conform-or-log :response/list-prompts-or-error)))

(defmethod lsp.server/receive-request "prompts/get"
  [_ context params]
  (log/trace :fn :receive-request :method "prompts/get" :params params)
  (->> params
       (handle-get-prompt context)
       (conform-or-log :response/get-prompt-or-error)))

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

;;; @TODO: Notifications to Implement

;; [ref: cancelled_notification]
(defmethod lsp.server/receive-notification "notifications/cancelled"
  [_method _context _params]
  (identity ::specs/cancelled-notification)
  ::lsp.server/method-not-found)

;; [ref: progress_notification]
(defmethod lsp.server/receive-notification "notifications/progress"
  [_method _context _params]
  (identity ::specs/progress-notification)
  ::lsp.server/method-not-found)

;; [ref: resource_list_changed_notification]
(defmethod lsp.server/receive-notification
  "notifications/resources/list_changed"
  [_method _context _params]
  (identity ::specs/resource-list-changed-notification)
  ::lsp.server/method-not-found)

;;; Server Spec

(defn- check-object-and-handler
  [object-type object handler]
  (when-not (ifn? handler)
    (let [msg (str "Invalid handler for " object-type)]
      (log/debug :msg msg object-type object)
      (throw (ex-info msg {:handler handler, object-type object}))))
  (case object-type
    :tool (when-not (specs/valid-tool? object)
            (let [msg "Invalid tool definition"]
              (log/debug :msg msg object-type object)
              (throw (ex-info msg (specs/explain-tool object)))))
    :resource (when-not (specs/valid-resource? object)
                (let [msg "Invalid resource definition"]
                  (log/debug :msg msg object-type object)
                  (throw (ex-info msg (specs/explain-resource object)))))
    :prompt (when-not (specs/valid-prompt? object)
              (let [msg "Invalid prompt definition"]
                (log/debug :msg msg object-type object)
                (throw (ex-info msg (specs/explain-prompt object)))))))


(defn register-tool!
  [context tool handler]
  (log/with-context {:fn :register-tool!}
    (check-object-and-handler :tool tool handler))
  (swap! (:tools context) assoc (:name tool) {:tool tool, :handler handler})
  context)

(defn register-resource!
  [context resource handler]
  (log/with-context {:fn :register-resource!}
    (check-object-and-handler :resource resource handler))
  (swap! (:resources context) assoc
    (:uri resource)
    {:resource resource, :handler handler})
  context)

(defn register-prompt!
  [context prompt handler]
  (log/with-context {:fn :register-prompt!}
    (check-object-and-handler :prompt prompt handler))
  (swap! (:prompts context) assoc
    (:name prompt)
    {:prompt prompt, :handler handler})
  context)

(defn- create-empty-context
  [name version]
  (log/debug :fn :create-empty-context)
  {:server-info {:name name, :version version},
   :tools (atom {}),
   :resources (atom {}),
   :prompts (atom {}),
   :protocol (atom nil),
   :capabilities (atom {:tools {}, :resources {}, :prompts {}})})

(defn create-context!
  "Create and configure an MCP server from a configuration map.
   Config map should have the shape:
   {:name \"server-name\"
    :version \"1.0.0\"
    :tools [{:name \"tool-name\"
             :description \"Tool description\"
             :schema {...}
             :handler (fn [args] ...)}]
    :prompts [{:name \"prompt-name\"
               :description \"Prompt description\"
               :handler (fn [args] ...)}]
    :resources [{:uri \"resource-uri\"
                 :type \"text\"
                 :handler (fn [uri] ...)}]}"
  [{:keys [name version tools prompts resources]}]
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

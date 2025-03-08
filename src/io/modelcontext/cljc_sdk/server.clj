(ns io.modelcontext.cljc-sdk.server
  (:require [io.modelcontext.cljc-sdk.core :as core]
            [io.modelcontext.cljc-sdk.specs :as specs]
            [me.vedang.logger.interface :as log]))

(defprotocol Server
  (register-tool! [this tool handler])
  (register-resource! [this resource handler])
  (register-prompt! [this prompt handler])
  (start! [this transport])
  (stop! [this]))

(defn- handle-initialize
  [client-info _client-capabilities server-info server-capabilities]
  (log/debug :msg "Client connected" :client client-info)
  {:protocolVersion specs/latest-protocol-version,
   :capabilities server-capabilities,
   :serverInfo server-info})

(defn- handle-list-tools [tools] {:tools (mapv :tool (vals tools))})

(defn- handle-call-tool
  [tools tool-name arguments]
  (if-let [{:keys [handler]} (get tools tool-name)]
    (try {:content [(handler arguments)]}
         (catch Exception e
           {:content [{:type "text", :text (str "Error: " (.getMessage e))}],
            :is-error true}))
    (throw (ex-info "Tool not found"
                    {:code specs/method-not-found,
                     :message "The requested tool was not found",
                     :data {:tool-name tool-name}}))))

(defn- handle-list-resources
  [resources]
  {:resources (mapv :resource (vals resources))})

(defn- handle-read-resource
  [resources uri]
  (if-let [{:keys [handler]} (get resources uri)]
    {:contents [(handler uri)]}
    (throw (ex-info "Resource not found"
                    {:code specs/method-not-found,
                     :message "The requested resource was not found",
                     :data {:uri uri}}))))

(defn- handle-list-prompts [prompts] {:prompts (mapv :prompt (vals prompts))})

(defn- handle-get-prompt
  [prompts name arguments]
  (if-let [{:keys [handler]} (get prompts name)]
    (handler arguments)
    (throw (ex-info "Prompt not found"
                    {:code specs/method-not-found,
                     :message "The requested prompt was not found",
                     :data {:prompt-name name}}))))

(defn- init-handlers!
  [server protocol]
  (core/handle-request! protocol
                        "initialize"
                        (fn [req]
                          (handle-initialize (:clientInfo req)
                                             (:capabilities req)
                                             (select-keys server
                                                          [:name :version])
                                             @(:capabilities server))))
  (core/handle-request! protocol
                        "tools/list"
                        (fn [_] (handle-list-tools @(:tools server))))
  (core/handle-request!
    protocol
    "tools/call"
    (fn [req] (handle-call-tool @(:tools server) (:name req) (:arguments req))))
  (core/handle-request! protocol
                        "resources/list"
                        (fn [_] (handle-list-resources @(:resources server))))
  (core/handle-request!
    protocol
    "resources/read"
    (fn [req] (handle-read-resource @(:resources server) (:uri req))))
  (core/handle-request! protocol
                        "prompts/list"
                        (fn [_] (handle-list-prompts @(:prompts server))))
  (core/handle-request!
    protocol
    "prompts/get"
    (fn [req]
      (handle-get-prompt @(:prompts server) (:name req) (:arguments req)))))

(defn- check-object-and-handler
  [object-type object handler]
  (when-not (ifn? handler)
    (throw (ex-info (str "Invalid handler for " object-type)
                    {:handler handler, object-type object})))
  (case object-type
    :tool (when-not (specs/valid-tool? object)
            (throw (ex-info "Invalid tool definition"
                            (specs/explain-tool object))))
    :resource (when-not (specs/valid-resource? object)
                (throw (ex-info "Invalid resource definition"
                                (specs/explain-resource object))))
    :prompt (when-not (specs/valid-prompt? object)
              (throw (ex-info "Invalid prompt definition"
                              (specs/explain-prompt object))))))

(defrecord FastServer [name version tools resources prompts protocol
                       capabilities]
  Server
    (register-tool! [this tool handler]
      (check-object-and-handler :tool tool handler)
      (swap! (:tools this) assoc (:name tool) {:tool tool, :handler handler})
      this)
    (register-resource! [this resource handler]
      (check-object-and-handler :resource resource handler)
      (swap! (:resources this) assoc
        (:uri resource)
        {:resource resource, :handler handler})
      this)
    (register-prompt! [this prompt handler]
      (check-object-and-handler :prompt prompt handler)
      (swap! (:prompts this) assoc
        (:name prompt)
        {:prompt prompt, :handler handler})
      this)
    (start! [this transport]
      (let [protocol (core/create-protocol transport)]
        ;; Initialize handlers and update our protocol
        (init-handlers! this protocol)
        (reset! (:protocol this) protocol)
        (.start! transport))
      this)
    (stop! [this]
      (when-let [protocol @(:protocol this)] (core/stop! (:transport protocol)))
      this))

(defn create-server
  "Create a new MCP server with the given name and version"
  [name version]
  (map->FastServer {:name name,
                    :version version,
                    :tools (atom {}),
                    :resources (atom {}),
                    :prompts (atom {}),
                    :protocol (atom nil),
                    :capabilities (atom
                                    {:tools {}, :resources {}, :prompts {}})}))

(defn make-server
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
  (let [server (create-server name version)]
    (doseq [tool tools]
      (register-tool! server (dissoc tool :handler) (:handler tool)))
    (doseq [resource resources]
      (register-resource! server
                          (dissoc resource :handler)
                          (:handler resource)))
    (doseq [prompt prompts]
      (register-prompt! server (dissoc prompt :handler) (:handler prompt)))
    server))

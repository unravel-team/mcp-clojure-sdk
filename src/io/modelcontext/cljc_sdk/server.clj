(ns io.modelcontext.cljc-sdk.server
  (:require [io.modelcontext.cljc-sdk.core :as core]
            [io.modelcontext.cljc-sdk.specs :as specs]
            [me.vedang.logger.interface :as log]))

(defprotocol MCPServer
  (register-tool! [this tool handler])
  (register-resource! [this resource handler])
  (register-prompt! [this prompt handler])
  (start! [this transport])
  (stop! [this]))

(defn- handle-initialize
  [server-name server-version capabilities client-info]
  (log/info :msg "Client connected" :client client-info)
  {:protocolVersion specs/latest-protocol-version,
   :capabilities capabilities,
   :serverInfo {:name server-name, :version server-version}})

(defn- handle-list-tools [tools] {:tools (mapv :tool (vals @tools))})

(defn- handle-call-tool
  [tools tool-name arguments]
  (if-let [{:keys [handler]} (get @tools tool-name)]
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
  {:resources (mapv :resource (vals @resources))})

(defn- handle-read-resource
  [resources uri]
  (if-let [{:keys [handler]} (get @resources uri)]
    {:contents [(handler uri)]}
    (throw (ex-info "Resource not found"
                    {:code specs/method-not-found,
                     :message "The requested resource was not found",
                     :data {:uri uri}}))))

(defn- handle-list-prompts [prompts] {:prompts (mapv :prompt (vals @prompts))})

(defn- handle-get-prompt
  [prompts name arguments]
  (if-let [{:keys [handler]} (get @prompts name)]
    (handler arguments)
    (throw (ex-info "Prompt not found"
                    {:code specs/method-not-found,
                     :message "The requested prompt was not found",
                     :data {:prompt-name name}}))))

(defn- init-handlers!
  [server-name server-version protocol tools resources prompts]
  (core/handle-request! protocol
                        "initialize"
                        #(handle-initialize server-name
                                            server-version
                                            (:capabilities %)
                                            (:clientInfo %)))
  (core/handle-request! protocol
                        "tools/list"
                        (fn [_] (handle-list-tools tools)))
  (core/handle-request! protocol
                        "tools/call"
                        #(handle-call-tool tools (:name %) (:arguments %)))
  (core/handle-request! protocol
                        "resources/list"
                        (fn [_] (handle-list-resources resources)))
  (core/handle-request! protocol
                        "resources/read"
                        #(handle-read-resource resources (:uri %)))
  (core/handle-request! protocol
                        "prompts/list"
                        (fn [_] (handle-list-prompts prompts)))
  (core/handle-request! protocol
                        "prompts/get"
                        #(handle-get-prompt prompts (:name %) (:arguments %))))

(def server-functions
  {:register-tool!
   (fn [this tool handler]
     (when-not (specs/valid-tool? tool)
       (throw (ex-info "Invalid tool definition" (specs/explain-tool tool))))
     (swap! (:tools this) assoc (:name tool) {:tool tool, :handler handler})
     this),
   :register-resource! (fn [this resource handler]
                         (when-not (specs/valid-resource? resource)
                           (throw (ex-info "Invalid resource"
                                           (specs/explain-resource resource))))
                         (swap! (:resources this) assoc
                           (:uri resource)
                           {:resource resource, :handler handler})
                         this),
   :register-prompt! (fn [this prompt handler]
                       (when-not (specs/valid-prompt? prompt)
                         (throw (ex-info "Invalid prompt"
                                         (specs/explain-prompt prompt))))
                       (swap! (:prompts this) assoc
                         (:name prompt)
                         {:prompt prompt, :handler handler})
                       this),
   :start! (fn [this transport]
             (let [protocol (core/create-protocol transport)]
               ;; Initialize handlers and update our protocol
               (init-handlers! (:name this)
                               (:version this)
                               protocol
                               (:tools this)
                               (:resources this)
                               (:prompts this))
               (reset! (:protocol this) protocol)
               (.start! transport))
             this),
   :stop! (fn [this]
            (when-let [protocol @(:protocol this)]
              (core/stop! (:transport protocol)))
            this)})

(defrecord Server [name version tools resources prompts protocol capabilities])

(extend Server
  MCPServer
    server-functions)

(defn create-server
  "Create a new MCP server with the given name and version"
  [name version]
  (map->Server {:name name,
                :version version,
                :tools (atom {}),
                :resources (atom {}),
                :prompts (atom {}),
                :protocol (atom nil),
                :capabilities (atom {:tools {}})}))

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

(ns io.modelcontext.cljc-sdk.server
  (:require [io.modelcontext.cljc-sdk.core :as core]
            [io.modelcontext.cljc-sdk.specs :as specs]
            [me.vedang.logger.interface :as log]))

(defprotocol MCPServer
  (register-tool! [this tool-name description schema handler]
    "Register a tool")
  (register-resource! [this resource handler]
    "Register a resource")
  (register-prompt! [this prompt handler]
    "Register a prompt")
  (start! [this transport]
    "Start the server with given transport")
  (stop! [this]
    "Stop the server"))

(defn- init-handlers!
  [server-name server-version protocol tools resources prompts]
  (let [handle-initialize
          (fn [{:keys [protocol-version capabilities client-info]}]
            (log/info :msg "Client connected" :client client-info)
            {:protocol-version specs/latest-protocol-version,
             :capabilities @capabilities,
             :server-info {:server-name server-name,
                           :server-version server-version}})
        handle-list-tools (fn [_] {:tools (mapv :tool (vals @tools))})
        handle-call-tool (fn [{:keys [name arguments]}]
                           (if-let [{:keys [handler]} (get @tools name)]
                             (try {:content [(handler arguments)]}
                                  (catch Exception e
                                    {:content [{:type "text",
                                                :text (str "Error: "
                                                           (.getMessage e))}],
                                     :is-error true}))
                             (throw (ex-info "Tool not found"
                                             {:code specs/method-not-found}))))
        handle-list-resources (fn [_]
                                {:resources (mapv :resource (vals @resources))})
        handle-read-resource (fn [{:keys [uri]}]
                               (if-let [{:keys [handler]} (get @resources uri)]
                                 {:contents [(handler uri)]}
                                 (throw (ex-info "Resource not found"
                                                 {:code
                                                  specs/method-not-found}))))
        handle-list-prompts (fn [_] {:prompts (mapv :prompt (vals @prompts))})
        handle-get-prompt (fn [{:keys [name arguments]}]
                            (if-let [{:keys [handler]} (get @prompts name)]
                              (handler arguments)
                              (throw (ex-info "Prompt not found"
                                              {:code
                                               specs/method-not-found}))))]
    (core/handle-request! protocol "initialize" handle-initialize)
    (core/handle-request! protocol "tools/list" handle-list-tools)
    (core/handle-request! protocol "tools/call" handle-call-tool)
    (core/handle-request! protocol "resources/list" handle-list-resources)
    (core/handle-request! protocol "resources/read" handle-read-resource)
    (core/handle-request! protocol "prompts/list" handle-list-prompts)
    (core/handle-request! protocol "prompts/get" handle-get-prompt)))

(defrecord Server [server-name server-version tools resources prompts protocol
                   capabilities]
  MCPServer
    (register-tool! [this tool-name description schema handler]
      (let [tool
              {:name tool-name, :description description, :input-schema schema}]
        (when-not (specs/valid-tool? tool)
          (throw (ex-info "Invalid tool definition"
                          {:explain (specs/explain-tool tool)})))
        (swap! tools assoc tool-name {:tool tool, :handler handler}))
      this)
    (register-resource! [this resource handler]
      (when-not (specs/valid-resource? resource)
        (throw (ex-info "Invalid resource"
                        {:explain (specs/explain-resource resource)})))
      (swap! resources assoc
        (:uri resource)
        {:resource resource, :handler handler})
      this)
    (register-prompt! [this prompt handler]
      (when-not (specs/valid-prompt? prompt)
        (throw (ex-info "Invalid prompt"
                        {:explain (specs/explain-prompt prompt)})))
      (swap! prompts assoc (:name prompt) {:prompt prompt, :handler handler})
      this)
    (start! [this transport]
      (let [protocol (core/create-protocol transport)]
        ;; Initialize handlers
        (init-handlers! server-name
                        server-version
                        protocol
                        tools
                        resources
                        prompts)
        (reset! (:protocol this) protocol)
        (.start! transport))
      this)
    (stop! [this]
      (when-let [protocol @(:protocol this)] (stop! (:transport protocol)))
      this))

(defn create-server
  "Create a new MCP server with the given name and version"
  [name version]
  (map->Server {:server-name name,
                :server-version version,
                :tools (atom {}),
                :resources (atom {}),
                :prompts (atom {}),
                :protocol (atom nil),
                :capabilities (atom {:tools {}})}))

(defmacro defserver
  "Define an MCP server with the given name and version"
  [name version & body]
  `(let [server# (create-server ~name ~version)]
     ~@body
     server#))

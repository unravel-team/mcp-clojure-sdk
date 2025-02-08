(ns io.modelcontext.cljc-sdk.server
  (:require [io.modelcontext.cljc-sdk.core :as core]
            [io.modelcontext.cljc-sdk.specs :as specs]
            [me.vedang.logger.interface :as log]))

(defprotocol MCPServer
  (register-tool! [this tool-name description schema handler])
  (register-resource! [this resource handler])
  (register-prompt! [this prompt handler])
  (start! [this transport])
  (stop! [this]))

(defn- handle-initialize
  [server-name server-version capabilities client-info]
  (log/info :msg "Client connected" :client client-info)
  {:protocol-version specs/latest-protocol-version,
   :capabilities @capabilities,
   :server-info {:server-name server-name, :server-version server-version}})

(defn- handle-list-tools [tools] {:tools (mapv :tool (vals @tools))})

(defn- handle-call-tool
  [tools name arguments]
  (if-let [{:keys [handler]} (get @tools name)]
    (try {:content [(handler arguments)]}
         (catch #?(:clj Exception
                   :cljs js/Object)
           e
           {:content [{:type "text", :text (str "Error: " (.getMessage e))}],
            :is-error true}))
    (throw (ex-info "Tool not found" {:code specs/method-not-found}))))

(defn- handle-list-resources
  [resources]
  {:resources (mapv :resource (vals @resources))})

(defn- handle-read-resource
  [resources uri]
  (if-let [{:keys [handler]} (get @resources uri)]
    {:contents [(handler uri)]}
    (throw (ex-info "Resource not found" {:code specs/method-not-found}))))

(defn- handle-list-prompts [prompts] {:prompts (mapv :prompt (vals @prompts))})

(defn- handle-get-prompt
  [prompts name arguments]
  (if-let [{:keys [handler]} (get @prompts name)]
    (handler arguments)
    (throw (ex-info "Prompt not found" {:code specs/method-not-found}))))

(defn- init-handlers!
  [server-name server-version protocol tools resources prompts]
  (core/handle-request! protocol
                        "initialize"
                        #(handle-initialize server-name
                                            server-version
                                            (:capabilities %)
                                            (:client-info %)))
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

;; Moved record to a def for better reuse [ref: babashka_protocols_gotcha]
(def server-functions
  {:register-tool!
   (fn [this tool-name description schema handler]
     (let [tool
             {:name tool-name, :description description, :input-schema schema}]
       (when-not (specs/valid-tool? tool)
         (throw (ex-info "Invalid tool definition"
                         {:explain (specs/explain-tool tool)})))
       (swap! (:tools this) assoc tool-name {:tool tool, :handler handler}))
     this),
   :register-resource! (fn [this resource handler]
                         (when-not (specs/valid-resource? resource)
                           (throw (ex-info "Invalid resource"
                                           {:explain (specs/explain-resource
                                                       resource)})))
                         (swap! (:resources this) assoc
                           (:uri resource)
                           {:resource resource, :handler handler})
                         this),
   :register-prompt! (fn [this prompt handler]
                       (when-not (specs/valid-prompt? prompt)
                         (throw (ex-info "Invalid prompt"
                                         {:explain (specs/explain-prompt
                                                     prompt)})))
                       (swap! (:prompts this) assoc
                         (:name prompt)
                         {:prompt prompt, :handler handler})
                       this),
   :start! (fn [this transport]
             (let [protocol (core/create-protocol transport)]
               ;; Initialize handlers and update our protocol
               (init-handlers! (:server-name this)
                               (:server-version this)
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

(defrecord Server [server-name server-version tools resources prompts protocol
                   capabilities])

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
    (doseq [{:keys [name description schema handler]} tools]
      (register-tool! server name description schema handler))
    (doseq [resource resources]
      (register-resource! server resource (:handler resource)))
    (doseq [prompt prompts] (register-prompt! server prompt (:handler prompt)))
    server))

;;; [tag: babashka_protocols_gotcha]
;;;
;;; Babashka needs Records to extend Protocols explicitly. It does not
;;; understand the "implement Protocol as part of the Record definition" style.
;;;
#?(:bb (extend-protocol MCPServer
         Server
           (register-tool! [this tool-name description schema handler]
             ((get server-functions :register-tool!)
               this
               tool-name
               description
               schema
               handler))
           (register-resource! [this resource handler]
             ((get server-functions :register-resource!) this resource handler))
           (register-prompt! [this prompt handler]
             ((get server-functions :register-prompt!) this prompt handler))
           (start! [this transport]
             ((get server-functions :start!) this transport))
           (stop! [this] ((get server-functions :stop!) this)))
   :clj (extend Server
          MCPServer
            server-functions))

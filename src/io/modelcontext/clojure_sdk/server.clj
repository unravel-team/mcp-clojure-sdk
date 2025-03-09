(ns io.modelcontext.clojure-sdk.server
  (:require [clojure.core.async :as async]
            [io.modelcontext.clojure-sdk.specs :as specs]
            [lsp4clj.coercer :as coercer]
            [lsp4clj.server :as lsp.server]
            [me.vedang.logger.interface :as log]))

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

(defn- handle-initialize
  [context params]
  (let [client-info (:clientInfo params)
        _client-capabilities (:capabilities params)
        server-info (:server-info context)
        server-capabilities @(:capabilities context)]
    (log/debug :msg "Client connected" :client client-info)
    {:protocolVersion specs/stable-protocol-version,
     :capabilities server-capabilities,
     :serverInfo server-info}))

(defn- handle-list-tools
  [context _params]
  {:tools (mapv :tool (vals @(:tools context)))})

(defn- handle-call-tool
  [context params]
  (let [tools @(:tools context)
        tool-name (:name params)
        arguments (:arguments params)]
    (if-let [{:keys [handler]} (get tools tool-name)]
      (try {:content [(handler arguments)]}
           (catch Exception e
             {:content [{:type "text", :text (str "Error: " (.getMessage e))}],
              :is-error true}))
      (throw (ex-info "Tool not found"
                      {:code specs/method-not-found,
                       :message "The requested tool was not found",
                       :data {:tool-name tool-name}})))))

(defn- handle-list-resources
  [context _params]
  {:resources (mapv :resource (vals @(:resources context)))})

(defn- handle-read-resource
  [context params]
  (let [resources @(:resources context)
        uri (:uri params)]
    (if-let [{:keys [handler]} (get resources uri)]
      {:contents [(handler uri)]}
      (throw (ex-info "Resource not found"
                      {:code specs/method-not-found,
                       :message "The requested resource was not found",
                       :data {:uri uri}})))))

(defn- handle-list-prompts
  [context _params]
  {:prompts (mapv :prompt (vals @(:prompts context)))})

(defn- handle-get-prompt
  [context params]
  (let [prompts @(:prompts context)
        prompt-name (:name params)
        arguments (:arguments params)]
    (if-let [{:keys [handler]} (get prompts prompt-name)]
      (handler arguments)
      (throw (ex-info "Prompt not found"
                      {:code specs/method-not-found,
                       :message "The requested prompt was not found",
                       :data {:prompt-name prompt-name}})))))

(defmethod lsp.server/receive-request "initialize"
  [_ context params]
  (->> params
       (handle-initialize context)
       (conform-or-log :result/initialize)))

(defmethod lsp.server/receive-request "tools/list"
  [_ context params]
  (->> params
       (handle-list-tools context)
       (conform-or-log :result/list-tools)))

(defmethod lsp.server/receive-request "tools/call"
  [_ context params]
  (->> params
       (handle-call-tool context)
       (conform-or-log :result/call-tool)))

(defmethod lsp.server/receive-request "resources/list"
  [_ context params]
  (->> params
       (handle-list-resources context)
       (conform-or-log :result/list-resources)))

(defmethod lsp.server/receive-request "resources/read"
  [_ context params]
  (->> params
       (handle-read-resource context)
       (conform-or-log :result/read-resource)))

(defmethod lsp.server/receive-request "prompts/list"
  [_ context params]
  (->> params
       (handle-list-prompts context)
       (conform-or-log :result/list-prompts)))

(defmethod lsp.server/receive-request "prompts/get"
  [_ context params]
  (->> params
       (handle-get-prompt context)
       (conform-or-log :result/read-prompt)))

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


(defn register-tool!
  [context tool handler]
  (check-object-and-handler :tool tool handler)
  (swap! (:tools context) assoc (:name tool) {:tool tool, :handler handler})
  context)

(defn register-resource!
  [context resource handler]
  (check-object-and-handler :resource resource handler)
  (swap! (:resources context) assoc
    (:uri resource)
    {:resource resource, :handler handler})
  context)

(defn register-prompt!
  [context prompt handler]
  (check-object-and-handler :prompt prompt handler)
  (swap! (:prompts context) assoc
    (:name prompt)
    {:prompt prompt, :handler handler})
  context)

(defn- create-empty-server-context
  [name version]
  {:server-info {:name name, :version version},
   :tools (atom {}),
   :resources (atom {}),
   :prompts (atom {}),
   :protocol (atom nil),
   :capabilities (atom {:tools {}, :resources {}, :prompts {}})})

(defn create-server-context!
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
  (let [context (create-empty-server-context name version)]
    (doseq [tool tools]
      (register-tool! context (dissoc tool :handler) (:handler tool)))
    (doseq [resource resources]
      (register-resource! context
                          (dissoc resource :handler)
                          (:handler resource)))
    (doseq [prompt prompts]
      (register-prompt! context (dissoc prompt :handler) (:handler prompt)))
    context))

(defn start-server!
  [server context]
  (log/info :msg "[SERVER] Starting server...")
  (lsp.server/start server context))

(defn chan-server
  []
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)]
    (lsp.server/chan-server {:output-ch output-ch, :input-ch input-ch})))

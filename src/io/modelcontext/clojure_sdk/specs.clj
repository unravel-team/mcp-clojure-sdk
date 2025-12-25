(ns io.modelcontext.clojure-sdk.specs
  (:require [clojure.spec.alpha :as s]
            #_{:clj-kondo/ignore [:unused-namespace]}
            [jsonrpc4clj.coercer :as coercer]
            [jsonrpc4clj.errors :as jsonrpc.errors]))

;; [tag: reuse_jsonrpc4clj_coercer]
;;
;; This file heavily reuses specs defined in the `jsonrpc4clj.coercer`
;; namespace. If you don't find the definition of a spec in this ns,
;; check the coercer ns.

;; Error Response: copied over from lsp4clj, since jsonrpc4clj seems
;; to have removed this.
(s/def ::error
  (s/keys :req-un [:error/code :error/message] :opt-un [:error/data]))

(s/def ::response-error
  (s/and (s/keys :req-un [::error])
         (s/conformer
           (fn [resp]
             (update resp
                     :error (fn [{:keys [code message data]}]
                              (jsonrpc.errors/body code message data)))))))

;; JSON-RPC types
;; Refer to `::coercer/json-rpc.input` to see all the possible inputs as
;; defined
;; in the JSON-RPC spec.

;; Protocol constants
(def supported-protocol-versions ["2025-03-26" "2024-11-05"])
;; [tag: version_negotiation]
;;
;; (From [[/specification/draft/basic/lifecycle.mdx::Version Negotiation]])
;;
;; In the `initialize` request, the client **MUST** send a protocol
;; version it supports. This **SHOULD** be the _latest_ version supported
;; by the client.
;;
;; If the server supports the requested protocol version, it **MUST**
;; respond with the same version. Otherwise, the server **MUST** respond
;; with another protocol version it supports. This **SHOULD** be the
;; _latest_ version supported by the server.
;;
;; If the client does not support the version in the server's response,
;; it **SHOULD** disconnect.

;;; Base Interface: Request
;; A progress token, used to associate progress notifications with the original
;; request.
(s/def ::progressToken
  (s/or :str string?
        :num number?))
;; An opaque token used to represent a cursor for pagination.
(s/def ::cursor string?)

;; _meta: If specified, the caller is requesting out-of-band progress
;; notifications for this request (as represented by
;; notifications/progress). The value of this parameter is an opaque
;; token that will be attached to any subsequent notifications. The
;; receiver is not obligated to provide these notifications.
(s/def :json-rpc.message/_meta (s/keys :opt-un [::progressToken]))
;; Parameters
(s/def :json-rpc.message/params (s/keys :opt-un [:json-rpc.message/_meta]))

;;; Base interface: Notification
;; [ref: reuse_jsonrpc4clj_coercer]

;;; Base interface: Result
;; [ref: reuse_jsonrpc4clj_coercer]
;; _meta: This result property is reserved by the protocol to allow clients and
;; servers to attach additional metadata to their responses.
(s/def :json-rpc.message/result (s/keys :opt-un [:json-rpc.message/_meta]))

;; Standard error codes
;; [tag: reuse_lsp4clj_errors]
;;
;; These error codes are defined in the namespace `lsp4clj.lsp.errors` and can
;; be reused using the helpers provided there

;; Cancellation
;; [tag: cancelled_notification]
;; This notification can be sent by either side to indicate that it is
;; cancelling a previously-issued request.
;;
;; The request SHOULD still be in-flight, but due to communication latency, it
;; is always possible that this notification MAY arrive after the request has
;; already finished.
;;
;; This notification indicates that the result will be unused, so any
;; associated processing SHOULD cease.
;;
;; A client MUST NOT attempt to cancel its `initialize` request.
;; method: "notifications/cancelled"
(s/def :cancelled-notification/requestId :json-rpc.message/id)
(s/def :cancelled-notification/reason string?)
(s/def ::cancelled-notification
  (s/keys :req-un [:cancelled-notification/requestId]
          :opt-un [:cancelled-notification/reason]))

;;; Initialization
;; [tag: initialize_request]
;; This request is sent from the client to the server when it first connects,
;; asking it to begin initialization
(s/def :initialize/protocolVersion string?)
;; The latest version of the Model Context Protocol that the client
;; supports. The client MAY decide to support older versions as
;; well.
;; [ref: client_capabilities] for :initialize-request/capabilities and
;; [ref: implementation_server_client_info] for :initialize-request/clientInfo
(s/def ::initialize-request
  (s/keys :req-un [:initialize/protocolVersion :initialize-request/capabilities
                   :initialize-request/clientInfo]))

;; After receiving an initialize request from the client, the server sends this
;; response.
(s/def :initialize-response/instructions string?)
(s/def :initialize-response/result
  ;; The version of the Model Context Protocol that the server wants to
  ;; use. This may not match the version that the client requested. If the
  ;; client cannot support this version, it MUST disconnect.
  ;; [ref: server_capabilities] for :initialize-response/capabilities and
  ;; [ref: implementation_server_client_info] for
  ;; :initialize-response/serverInfo
  (s/keys :req-un [:initialize/protocolVersion :initialize-response/capabilities
                   :initialize-response/serverInfo]
          ;; Instructions describing how to use the
          ;; server and its features. This can be used by
          ;; clients to improve the LLM's understanding
          ;; of available tools, resources, etc. It can
          ;; be thought of like a "hint" to the model.
          ;; For example, this information MAY be added
          ;; to the system prompt.
          :opt-un [:initialize-response/instructions]))
(s/def ::initialize-response
  (s/and (s/or :error ::response-error
               :initialize :initialize-response/result)
         (s/conformer second)))

;; [tag: initialized_notification]
;; This notification is sent from the client to the server after initialization
;; has finished. The server should start processing requests after this
;; notification.
(s/def ::initialized-notification (s/keys :opt-un [:json-rpc.message/_meta]))

;;; Capabilities
;; Experimental, non-standard capabilities that the server/client supports.
(s/def :capabilities/experimental (s/map-of string? any?))
;; Whether the server/client supports notifications for changes to the
;; prompts/roots list.
(s/def :capabilities/listChanged boolean?)
;; Present if the client supports listing roots.
(s/def :capabilities/roots (s/keys :opt-un [:capabilities/listChanged]))
;; Present if the client supports sampling from an LLM.
(s/def :capabilities/sampling any?)

;; [tag: client_capabilities]
;;
;; Capabilities a client may support. Known capabilities are defined here, in
;; this schema, but this is not a closed set: any client can define its own,
;; additional capabilities.
(s/def ::client-capabilities
  (s/keys :opt-un [:capabilities/experimental :capabilities/roots
                   :capabilities/sampling]))
(s/def :initialize-request/capabilities ::client-capabilities)

;; Present if the server supports sending log messages to the client.
(s/def :capabilities/logging any?)
(s/def :capabilities/subscribe boolean?)
;; Present if the server offers any prompt templates.
(s/def :capabilities/prompts (s/keys :opt-un [:capabilities/listChanged]))
;; Present if the server offers any resources to read.
(s/def :capabilities/resources
  (s/keys :opt-un [:capabilities/subscribe :capabilities/listChanged]))
;; Present if the server offers any tools to call.
(s/def :capabilities/tools (s/keys :opt-un [:capabilities/listChanged]))

;; [tag: server_capabilities]
;;
;; Capabilities that a server may support. Known capabilities are defined here,
;; in this schema, but this is not a closed set: any server can define its own,
;; additional capabilities.
(s/def ::server-capabilities
  (s/keys :opt-un [:capabilities/experimental :capabilities/logging
                   :capabilities/prompts :capabilities/resources
                   :capabilities/tools]))
(s/def :initialize-response/capabilities ::server-capabilities)

;;; [tag: implementation_server_client_info]
;; Describes the name and version of an MCP implementation.
(s/def :implementation/name string?)
(s/def :implementation/version string?)
(s/def ::implementation
  (s/keys :req-un [:implementation/name :implementation/version]))
(s/def :initialize-request/clientInfo ::implementation)
(s/def :initialize-response/serverInfo ::implementation)

;;; [tag: ping_request]
;; A ping, issued by either the server or the client, to check that the other
;; party is still alive. The receiver must promptly respond, or else may be
;; disconnected.
(s/def ::ping-request any?)

;;; [tag: progress_notification]
;; An out-of-band notification used to inform the receiver of a progress update
;; for a long-running request.
;; The progress thus far. This should increase every time progress is made,
;; even if the total is unknown.
(s/def :progress-notification/progress number?)
;; Total number of items to process (or total progress required), if known.
(s/def :progress-notification/total number?)
(s/def ::progress-notification
  (s/keys :req-un [::progressToken :progress-notification/progress]
          :opt-un [:progress-notification/total]))

;;; Pagination
(s/def ::paginated-request (s/keys :opt-un [::cursor]))
(s/def :paginated/nextCursor ::cursor)
(s/def ::paginated-response (s/keys :opt-un [:paginated/nextCursor]))

;;; Resources
;; [tag: list_resources_request]
;; Sent from the client to request a list of resources the server has.
(s/def ::list-resources-request ::paginated-request)

;; The server's response to a resources/list request from the client.
(s/def :list-resources-response/resources (s/coll-of ::resource))
(s/def :list-resources-response/result
  (s/merge ::paginated-response
             (s/keys :req-un [:list-resources-response/resources])))
(s/def ::list-resources-response
  (s/and (s/or :error ::response-error
               :list-resources :list-resources-response/result)
         (s/conformer second)))

;; [tag: list_resource_templates_request]
;; Sent from the client to request a list of resource templates the server has.
(s/def ::list-resource-templates-request ::paginated-request)

;; The server's response to a resources/templates/list request from the client.
(s/def :list-resource-templates-response/resourceTemplates
  (s/coll-of ::resource-template))
(s/def :list-resource-templates-response/result
  (s/merge ::paginated-response
             (s/keys :req-un
                       [:list-resource-templates-response/resourceTemplates])))
(s/def ::list-resource-templates-response
  (s/and (s/or :error ::response-error
               :list-resource-templates
                 :list-resource-templates-response/result)
         (s/conformer second)))

;; [tag: read_resource_request]
;; Sent from the client to the server, to read a specific resource URI.
(s/def ::read-resource-request (s/keys :req-un [:resource/uri]))

;; The server's response to a resources/read request from the client.
(s/def :read-resource-response/content
  (s/and (s/or :text-resource :contents/text-resource
               :blob-resource :contents/blob-resource)
         (s/conformer second)))
(s/def :read-resource-response/contents
  (s/coll-of :read-resource-response/content))
(s/def :read-resource-response/result
  (s/keys :req-un [:read-resource-response/contents]))
(s/def ::read-resource-response
  (s/and (s/or :error ::response-error
               :read-resource :read-resource-response/result)
         (s/conformer second)))

;;; Resource List Changed Notification
;; [tag: resource_list_changed_notification]
;; An optional notification from the server to the client, informing
;; it that the list of resources it can read from has changed. This
;; may be issued by servers without any previous subscription from the
;; client.
;; method: "notifications/resources/list_changed"
(s/def ::resource-list-changed-notification
  (s/keys :opt-un [:json-rpc.message/_meta]))

;;; Resource Subscribe/Unsubscribe
;; [tag: resource_subscribe_unsubscribe_request]
;; Subscribe: Sent from the client to request resources/updated notifications
;; from the
;; server whenever a particular resource changes.
;; Unsubscribe: Sent from the client to request cancellation of
;; resources/updated
;; notifications from the server. This should follow a previous
;; resources/subscribe request.
;; Both requests expect an empty response from the server
(s/def ::resource-subscribe-unsubscribe-request
  (s/keys :req-un [:resource/uri]))

;;; Resource Updated Notification
;; [tag: resource_updated_notification]
;; A notification from the server to the client, informing it that a
;; resource has changed and may need to be read again. This should
;; only be sent if the client previously sent a resources/subscribe
;; request.
;; method: "notifications/resources/updated"
(s/def ::resource-updated-notification (s/keys :req-un [:resource/uri]))

;;; Resource
;; A known resource that the server is capable of reading.
;; The URI of this resource.
(s/def :resource/uri string?)
;; A human-readable name for this resource. This can be used by clients to
;; populate UI elements.
(s/def :resource/name string?)
;; A description of what this resource represents. This can be used by clients
;; to improve the LLM's understanding of available resources. It can be thought
;; of like a "hint" to the model.
(s/def :resource/description string?)
;; The MIME type of this resource, if known.
(s/def :resource/mimeType string?)
;; The size of the raw resource content, in bytes (i.e., before base64 encoding
;; or any tokenization), if known. This can be used by Hosts to display file
;; sizes and estimate context window usage.
(s/def :resource/size number?)
(s/def ::resource
  (s/merge ::annotated (s/keys :req-un [:resource/uri :resource/name]
                               :opt-un [:resource/description :resource/mimeType
                                        :resource/size])))

;;; Resource Template
;; A template description for resources available on the server.
;; A URI template (according to RFC 6570) that can be used to construct
;; resource
;; URIs.
(s/def :resource-template/uriTemplate string?)
(s/def ::resource-template
  (s/merge ::annotated (s/keys
                         :req-un [:resource-template/uriTemplate :resource/name]
                         :opt-un [:resource/description :resource/mimeType])))

;;; Resource Contents
;; The contents of a specific resource or sub-resource.
(s/def :contents/resource
  (s/keys :req-un [:resource/uri] :opt-un [:resource/mimeType]))
;; The text of the item. This must only be set if the item can actually be
;; represented as text (not binary data).
(s/def :contents/text string?)
;; A base64-encoded string representing the binary data of the item.
(s/def :contents/blob string?)

(s/def :contents/text-resource
  (s/merge :contents/resource (s/keys :req-un [:contents/text])))
(s/def :contents/blob-resource
  (s/merge :contents/resource (s/keys :req-un [:contents/blob])))

;;; Prompts
;; [tag: list_prompts_request]
;; Sent from the client to request a list of prompts and prompt templates the
;; server has.
(s/def ::list-prompts-request ::paginated-request)

;; The server's response to a prompts/list request from the client.
(s/def :list-prompts-response/prompts (s/coll-of ::prompt))
(s/def :list-prompts-response/result
  (s/merge ::paginated-response (s/keys :req-un
                                          [:list-prompts-response/prompts])))
(s/def ::list-prompts-response
  (s/and (s/or :error ::response-error
               :list-prompts :list-prompts-response/result)
         (s/conformer second)))

;; [tag: get_prompt_request]
;; Used by the client to get a prompt provided by the server.
(s/def :get-prompt-request/arguments (s/map-of keyword? string?))
(s/def ::get-prompt-request
  (s/keys :req-un [:prompt/name] :opt-un [:get-prompt-request/arguments]))

;; The server's response to a prompts/get request from the client.
(s/def :get-prompt-response/messages (s/coll-of ::prompt-message))
(s/def :get-prompt-response/result
  (s/keys :req-un [:get-prompt-response/messages]
          :opt-un [:prompt/description]))
(s/def ::get-prompt-response
  (s/and (s/or :error ::response-error
               :get-prompt :get-prompt-response/result)
         (s/conformer second)))

;;; Prompt
(s/def :prompt/name string?)
(s/def :prompt/description string?)
(s/def :prompt/arguments (s/coll-of ::prompt-argument))
(s/def ::prompt
  (s/keys :req-un [:prompt/name]
          :opt-un [:prompt/description :prompt/arguments]))

;;; Prompt Argument
;; Describes an argument that a prompt can accept.
(s/def :prompt-argument/name string?)
(s/def :prompt-argument/description string?)
;; Whether this argument must be provided.
(s/def :prompt-argument/required boolean?)
(s/def ::prompt-argument
  (s/keys :req-un [:prompt-argument/name]
          :opt-un [:prompt-argument/description :prompt-argument/required]))

;;; Role
;; The sender or recipient of messages and data in a conversation.
(s/def ::role #{"user" "assistant"})

;;; Prompt Message
;; Describes a message returned as part of a prompt. This is similar
;; to `SamplingMessage`, but also supports the embedding of resources
;; from the MCP server.
(s/def :prompt-message/content
  (s/and (s/or :text-content :content/text
               :image-content :content/image
               :audio-content :content/audio
               :embedded-resource :resource/embedded)
         (s/conformer second)))
(s/def ::prompt-message (s/keys :req-un [::role :prompt-message/content]))

;;; Embedded Resource
;; The contents of a resource, embedded into a prompt or tool call result. It
;; is
;; up to the client how best to render embedded resources for the benefit of
;; the
;; LLM and/or the user.
(s/def :embedded-resource/type #{"resource"})
(s/def :embedded-resource/resource
  (s/and (s/or :text-resource :contents/text-resource
               :blob-resource :contents/blob-resource)
         (s/conformer second)))
(s/def :resource/embedded
  (s/merge ::annotated (s/keys :req-un [:embedded-resource/type
                                        :embedded-resource/resource])))

;;; [tag: prompt_list_changed_notification]
;; An optional notification from the server to the client, informing it that
;; the
;; list of prompts it offers has changed. This may be issued by servers without
;; any previous subscription from the client.
;; method: "notifications/prompts/list_changed"
(s/def ::prompt-list-changed-notification
  (s/keys :opt-un [:json-rpc.message/_meta]))

;;; Tools
;; [tag: list_tools_request]
;; Sent from the client to request a list of tools the server has.

(s/def ::list-tools-request ::paginated-request)

;; The server's response to a tools/list request from the client.
(s/def :list-tools-response/tools (s/coll-of ::tool))
(s/def :list-tools-response/result
  (s/merge ::paginated-response (s/keys :req-un [:list-tools-response/tools])))
(s/def ::list-tools-response
  (s/and (s/or :error ::response-error
               :list-tools :list-tools-response/result)
         (s/conformer second)))

;; Tool Call
;; [tag: call_tool_request]
;; The server's response to a tool call.
;;
;; Any errors that originate from the tool SHOULD be reported inside the result
;; object, with `isError` set to true, _not_ as an MCP protocol-level error
;; response. Otherwise, the LLM would not be able to see that an error occurred
;; and self-correct.
;;
;; However, any errors in _finding_ the tool, an error indicating that the
;; server does not support tool calls, or any other exceptional conditions,
;; should be reported as an MCP error response.

;; Used by the client to invoke a tool provided by the server.
(s/def :call-tool-request/arguments (s/map-of keyword? any?))
(s/def ::call-tool-request
  (s/keys :req-un [:tool/name] :opt-un [:call-tool-request/arguments]))

;; The server's response to a tool call.
(s/def ::content-list
  (s/coll-of (s/and (s/or :text :content/text
                          :image :content/image
                          :audio :content/audio
                          :resource :resource/embedded)
                    (s/conformer second))))

(s/def :call-tool-response/content ;; yes, this is a collection, and the
                                   ;; name is not
  ;; `contents`. This looks like a mistake they
  ;; made and kept for backwards compatibility.
  ::content-list)

;; If not set, this is assumed to be false (the call was successful).
(s/def :call-tool-response/isError boolean?)

;; If the Tool does not define an outputSchema, `content` field MUST be present
;; in the result.
(s/def :call-tool-response/unstructured-result
  (s/keys :req-un [:call-tool-response/content]
          :opt-un [:call-tool-response/isError]))

;; Tool result for tools that do declare an outputSchema.
;; [tag: structured-content-should-match-output-schema-exactly]
(s/def :call-tool-response/structuredContent (s/map-of string? any?))

;; If the Tool defines an outputSchema, `structuredContent` field MUST be
;; present in the result, and contain a JSON object that matches the schema.
;; `content` is for backward compatibility with older clients.
(s/def :call-tool-response/structured-result
  (s/keys :req-un [:call-tool-response/structuredContent]
          :opt-un [:call-tool-response/content :call-tool-response/isError]))

(s/def :call-tool-response/result
  (s/and (s/or :structured :call-tool-response/structured-result
               :unstructured :call-tool-response/unstructured-result)
         (s/conformer second)))

(s/def ::call-tool-response
  (s/and (s/or :error ::response-error
               :call-tool :call-tool-response/result)
         (s/conformer second)))

;; [tag: tool_list_changed_notification]
;; An optional notification from the server to the client, informing it that
;; the list of tools it offers has changed. This may be issued by servers
;; without
;; any previous subscription from the client.
;; method: "notifications/tools/list_changed"
(s/def ::tool-list-changed-notification
  (s/keys :opt-un [:json-rpc.message/_meta]))

;;; Tool
;; Definition for a tool the client can call.
(s/def :tool/name string?)
(s/def :tool/description string?)
(s/def :tool/properties (s/map-of string? any?))
(s/def :tool/required (s/coll-of string?))
(s/def :schema/type #{"object"})
;; A JSON Schema object defining the expected parameters for the tool.
(s/def :tool/inputSchema
  (s/keys :req-un [:schema/type] :opt-un [:tool/properties :tool/required]))
(s/def :tool/outputSchema ;; This is any? right now, but I'm copying
                          ;; over from inputSchema to keep it
                          ;; consistent
  (s/keys :req-un [:schema/type] :opt-un [:tool/properties]))
(s/def ::tool
  (s/keys :req-un [:tool/name :tool/inputSchema]
          :opt-un [:tool/description :tool/outputSchema]))

;;; Logging
;; [tag: set_logging_level_request]
;; A request from the client to the server, to enable or adjust logging.
;; The level of logging that the client wants to receive from the server. The
;; server should send all logs at this level and higher (i.e., more severe) to
;; the client as notifications/logging/message.
(s/def ::set-logging-level-request (s/keys :req-un [:logging/level]))

;; [tag: logging_message_notification]
;; Notification of a log message passed from server to client. If no
;; logging/setLevel request has been sent from the client, the server MAY
;; decide which messages to send automatically.
;; method: "notifications/message"
(s/def :logging-message/logger string?)
(s/def :logging-message/data any?)
(s/def ::logging-message-notification
  (s/keys :req-un [:logging/level :logging-message/data]
          :opt-un [:logging-message/logger]))

;; The severity of a log message. These map to syslog message severities, as
;; specified in RFC-5424:
;; https://datatracker.ietf.org/doc/html/rfc5424#section-6.2.1
(s/def :logging/level
  #{"debug" "info" "notice" "warning" "error" "critical" "alert" "emergency"})

;;; Sampling
;; @TODO: Specs need to be cleaned up
;; A request from the server to sample an LLM via the client. The client has
;; full discretion over which model to select. The client should also inform
;; the
;; user before beginning sampling, to allow them to inspect the request (human
;; in the loop) and decide whether to approve it.
(s/def :sampling-create-message-request/method #{"sampling/createMessage"})
(s/def :sampling-create-message-request/messages (s/coll-of ::sampling-message))
;; An optional system prompt the server wants to use for sampling. The client
;; MAY modify or omit this prompt.
(s/def :sampling-create-message-request/systemPrompt string?)
;; A request to include context from one or more MCP servers (including the
;; caller), to be attached to the prompt. The client MAY ignore this request.
(s/def :sampling-create-message-request/includeContext
  #{"none" "thisServer" "allServers"})
(s/def :sampling-create-message-request/temperature number?)
;; The maximum number of tokens to sample, as requested by the server. The
;; client MAY choose to sample fewer tokens than requested.
(s/def :sampling-create-message-request/maxTokens number?)
(s/def :sampling-create-message-request/stopSequences (s/coll-of string?))
;; Optional metadata to pass through to the LLM provider. The format of this
;; metadata is provider-specific.
(s/def :sampling-create-message-request/metadata any?)
(s/def ::sampling-create-message-request
  (s/keys :req-un [:sampling-create-message-request/messages
                   :sampling-create-message-request/maxTokens]
          :opt-un [:sampling-create-message-request/modelPreferences
                   :sampling-create-message-request/systemPrompt
                   :sampling-create-message-request/includeContext
                   :sampling-create-message-request/temperature
                   :sampling-create-message-request/stopSequences
                   :sampling-create-message-request/metadata]))

;; The client's response to a sampling/create_message request from the server.
;; The client should inform the user before returning the sampled message, to
;; allow them to inspect the response (human in the loop) and decide whether to
;; allow the server to see it.

;; The name of the model that generated the message.
;; @TODO: Specs need to be cleaned up
(s/def :sampling-create-message-response/model string?)
;; The reason why sampling stopped, if known.
(s/def :sampling-create-message-response/stopReason
  (s/and (s/or :known-reasons #{"endTurn" "stopSequence" "maxTokens"}
               :unknown-reasons string?)
         (s/conformer second)))
(s/def :sampling-create-message-response/result
  (s/keys :req-un [:sampling-create-message-response/model]
          :opt-un [:sampling-create-message-response/stopReason]))

;; Describes a message issued to or received from an LLM API.
(s/def :sampling-message/content
  (s/and (s/or :text :content/text
               :image :content/image
               :audio :content/audio)
         (s/conformer second)))
(s/def ::sampling-message (s/keys :req-un [::role :sampling-message/content]))

;;; Annotated
;; Base for objects that include optional annotations for the client. The
;; client
;; can use annotations to inform how objects are used or displayed
(s/def ::annotated (s/keys :opt-un [:annotated/annotations]))
(s/def :annotated/annotations
  (s/keys :opt-un [:annotated/audience :annotated/priority]))
;; Describes who the intended customer of this object or data is.
(s/def :annotated/audience (s/coll-of ::role))
;; Describes how important this data is for operating the server. A value of 1
;; means "most important," and indicates that the data is effectively required,
;; while 0 means "least important," and indicates thatthe data is entirely
;; optional.
(defn between-zero-and-one? [x] (<= 0 x 1))
(s/def :annotated/priority (s/and number? between-zero-and-one?))

;; Text provided to or from an LLM.
(s/def :content/text
  (s/merge ::annotated (s/keys :req-un [:text-content/type
                                        :text-content/text])))
(s/def :text-content/type #{"text"})
(s/def :text-content/text string?)

;; An image provided to or from an LLM.
(s/def :content/image
  (s/merge ::annotated (s/keys :req-un [:image-content/type :image-content/data
                                        :image-content/mimeType])))
(s/def :image-content/type #{"image"})
;; The base64-encoded image data.
(s/def :image-content/data string?)
(s/def :image-content/mimeType string?)

;; Audio provided to or from an LLM.
(s/def :content/audio
  (s/merge ::annotated (s/keys :req-un [:audio-content/type :audio-content/data
                                        :audio-content/mimeType])))

(s/def :audio-content/type #{"audio"})
;; The base64-encoded audio data.
(s/def :audio-content/data string?)
(s/def :audio-content/mimeType string?)

;;; Model Preferences
;; The server's preferences for model selection, requested of the client during
;; sampling.
;;
;; Because LLMs can vary along multiple dimensions, choosing the "best" model
;; is
;; rarely straightforward.  Different models excel in different areas—some are
;; faster but less capable, others are more capable but more expensive, and so
;; on. This interface allows servers to express their priorities across
;; multiple dimensions to help clients make an appropriate selection for their
;; use case.
;;
;; These preferences are always advisory. The client MAY ignore them. It is
;; also up to the client to decide how to interpret these preferences and how
;; to balance them against other considerations.
(s/def ::model-preferences
  (s/keys :opt-un [:model-preferences/hints :model-preferences/costPriority
                   :model-preferences/speedPriority
                   :model-preferences/intelligencePriority]))
;; Optional hints to use for model selection. If multiple hints are specified,
;; the client MUST evaluate them in order. The client SHOULD prioritize these
;; hints over the numeric priorities, but MAY still use the priorities to
;; select
;; from ambiguous matches.
(s/def :model-preferences/hints (s/coll-of ::model-hint))
(s/def :model-preferences/costPriority number?)
(s/def :model-preferences/speedPriority number?)
(s/def :model-preferences/intelligencePriority number?)
;; A hint for a model name. The client SHOULD treat this as a substring of a
;; model name; for example:
;;  - `claude-3-5-sonnet` should match `claude-3-5-sonnet-20241022`
;;  - `sonnet` should match `claude-3-5-sonnet-20241022`,
;;  `claude-3-sonnet-20240229`, etc.
;;  - `claude` should match any Claude model
;;
;; The client MAY also map the string to a different provider's model name or a
;; different model family, as long as it fills a similar niche; for example:
;;  - `gemini-1.5-flash` could match `claude-3-haiku-20240307`
(s/def :model-hint/name string?)
(s/def ::model-hint (s/keys :opt-un [:model-hint/name]))
;;; FIXME: For some reason, the compiler cannot find ::model-preferences if I
;;; use the spec before the definition. I don't know why, I'll look into it
;;; later.
;; The server's preferences for which model to select. The client MAY ignore
;; these preferences.
(s/def :sampling-create-message-request/modelPreferences ::model-preferences)

;;; Completion
;; [tag: complete_request]
;; A request from the client to the server, to ask for completion options.
(s/def :complete-request/ref
  (s/and (s/or :prompt-ref :ref/prompt
               :resource-ref :ref/resource)
         (s/conformer second)))
(s/def :complete-request/argument
  (s/keys :req-un [:complete-request-argument/name
                   :complete-request-argument/value]))
(s/def :complete-request-argument/name string?)
(s/def :complete-request-argument/value string?)
(s/def ::complete-request
  (s/keys :req-un [:complete-request/ref :complete-request/argument]))

;; The server's response to a completion/complete request
(s/def :complete-response/values (s/coll-of string?))
(s/def :complete-response/total number?)
(s/def :complete-response/hasMore boolean?)
(s/def :complete-response/completion
  (s/keys :req-un [:complete-response/values]
          :opt-un [:complete-response/total :complete-response/hasMore]))
(s/def :complete-response/result
  (s/keys :req-un [:complete-response/completion]))
(s/def ::complete-response
  (s/and (s/or :error ::response-error
               :complete :complete-response/result)
         (s/conformer second)))

;;; Reference Types
(s/def :ref/type #{"ref/prompt" "ref/resource"})
(s/def :ref/prompt (s/keys :req-un [:ref/type :prompt/name]))
(s/def :ref/resource (s/keys :req-un [:ref/type :resource/uri]))

;;; Roots
;; @TODO: Specs need to be cleaned up
;; Sent from the server to request a list of root URIs from the client. Roots
;; allow servers to ask for specific directories or files to operate on. A
;; common example for roots is providing a set of repositories or directories a
;; server should operate on.
;;
;; This request is typically used when the server needs to understand the file
;; system structure or access specific locations that the client has permission
;; to read from.
;; method: "roots/list"
(s/def ::list-roots-request (s/keys :opt-un [:json-rpc.message/_meta]))
;; The client's response to a roots/list request from the server. This result
;; contains an array of Root objects, each representing a root directory or
;; file
;; that the server can operate on.
(s/def :list-roots-response/roots (s/coll-of ::root))
(s/def :list-roots-response/result
  (s/keys :req-un [:list-roots-response/roots]))

(s/def ::list-roots-response
  (s/and (s/or :error ::response-error
               :list-roots :list-roots-response/result)
         (s/conformer second)))

;; Represents a root directory or file that the server can operate on.
(s/def :root/uri string?)
(s/def :root/name string?)
(s/def ::root (s/keys :req-un [:root/uri] :opt-un [:root/name]))

;; A notification from the client to the server, informing it that the list of
;; roots has changed.
;;
;; This notification should be sent whenever the client adds, removes, or
;; modifies any root. The server should then request an updated list of roots
;; using the ListRootsRequest.
;; method: "notifications/roots/list_changed"
(s/def ::root-list-changed-notification
  (s/keys :opt-un [:json-rpc.message/_meta]))

;;; Client messages
(s/def ::client-request
  (s/or :ping ::ping-request
        :initialize ::initialize-request
        :complete ::complete-request
        :set-logging-level ::set-logging-level-request
        :get-prompt ::get-prompt-request
        :list-prompts ::list-prompts-request
        :list-resources ::list-resources-request
        :list-resource-templates ::list-resource-templates-request
        :read-resource ::read-resource-request
        :subscribe ::resource-subscribe-unsubscribe-request
        :unsubscribe ::resource-subscribe-unsubscribe-request
        :call-tool ::call-tool-request
        :list-tools ::list-tools-request))

(s/def ::client-notification
  (s/or :cancelled ::cancelled-notification
        :progress ::progress-notification
        :initialized ::initialized-notification
        :root-list-changed ::root-list-changed-notification))

;;; Server messages
(s/def ::server-request
  (s/or :ping ::ping-request
        :sampling-create-message ::sampling-create-message-request
        :list-roots ::list-roots-request))

(s/def ::server-notification
  (s/or :cancelled ::cancelled-notification
        :progress ::progress-notification
        :logging-message ::logging-message-notification
        :resource-updated ::resource-updated-notification
        :resource-list-changed ::resource-list-changed-notification
        :tool-list-changed ::tool-list-changed-notification
        :prompt-list-changed ::prompt-list-changed-notification))

(s/def ::empty-response #(= {} %))

(s/def ::server-response
  (s/or :empty ::empty-response
        :initialize ::initialize-response
        :complete ::complete-response
        :get-prompt ::get-prompt-response
        :list-prompts ::list-prompts-response
        :list-resources ::list-resources-response
        :list-resource-templates ::list-resource-templates-response
        :read-resource ::read-resource-response
        :call-tool ::call-tool-response
        :list-tools ::list-tools-response))

(s/def :server-spec/handler ifn?)
(s/def :server-spec/tool
  (s/merge ::tool (s/keys :req-un [:server-spec/handler])))
(s/def :server-spec/tools (s/coll-of :server-spec/tool))
(s/def :server-spec/prompt
  (s/merge ::prompt (s/keys :req-un [:server-spec/handler])))
(s/def :server-spec/prompts (s/coll-of :server-spec/prompt))
(s/def :server-spec/resource
  (s/merge ::resource (s/keys :req-un [:server-spec/handler])))
(s/def :server-spec/resources (s/coll-of :server-spec/resource))
(s/def ::server-spec
  (s/keys :req-un [:implementation/name :implementation/version]
          :opt-un [:server-spec/tools :server-spec/prompts
                   :server-spec/resources]))

;; Helper functions for resource validation
(defn valid-resource? [resource] (s/valid? ::resource resource))

(defn explain-resource [resource] (s/explain-data ::resource resource))

;; Helper functions for prompt validation
(defn valid-prompt? [prompt] (s/valid? ::prompt prompt))

(defn explain-prompt [prompt] (s/explain-data ::prompt prompt))

;; Helper functions for tool validation
(defn valid-tool? [tool] (s/valid? ::tool tool))

(defn explain-tool [tool] (s/explain-data ::tool tool))

;; Helper functions for root validation
(defn valid-root? [root] (s/valid? ::root root))

(defn explain-root [root] (s/explain-data ::root root))

;; Helper functions for message content validation
(defn valid-text-content? [content] (s/valid? :content/text content))

(defn valid-image-content? [content] (s/valid? :content/image content))

(defn valid-audio-content? [content] (s/valid? :content/audio content))

(defn valid-embedded-resource? [content] (s/valid? :resource/embedded content))

(defn explain-text-content [content] (s/explain-data :content/text content))

(defn explain-image-content [content] (s/explain-data :content/image content))

(defn explain-audio-content [content] (s/explain-data :content/audio content))

(defn explain-embedded-resource
  [content]
  (s/explain-data :resource/embedded content))

;; Helper functions for sampling validation
(defn valid-sampling-message? [msg] (s/valid? ::sampling-message msg))

(defn valid-model-preferences? [prefs] (s/valid? ::model-preferences prefs))

(defn explain-sampling-message [msg] (s/explain-data ::sampling-message msg))

(defn explain-model-preferences
  [prefs]
  (s/explain-data ::model-preferences prefs))

;; Helper functions for implementation validation
(defn valid-implementation? [impl] (s/valid? ::implementation impl))

(defn explain-implementation [impl] (s/explain-data ::implementation impl))

;; Helper functions for server-spec validation
(defn valid-server-spec? [spec] (s/valid? ::server-spec spec))

(defn explain-server-spec [spec] (s/explain-data ::server-spec spec))

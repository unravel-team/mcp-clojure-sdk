(ns io.modelcontext.cljc-sdk.specs
  (:require [clojure.spec.alpha :as s]))

;; JSON-RPC types
(s/def :jsonrpc/message
  (s/or :jsonrpc-request :jsonrpc/request
        :jsonrpc-notification :jsonrpc/notification
        :jsonrpc-response :jsonprc/response
        :jsonrpc-error :jsonrpc/error))

;; Protocol constants
(def latest-protocol-version "DRAFT-2025-v1")
(def jsonrpc-version "2.0")

;;; Base Interface: Request
;; A progress token, used to associate progress notifications with the original
;; request.
(s/def ::progressToken
  (s/or :str string?
        :num number?))
;; An opaque token used to represent a cursor for pagination.
(s/def ::cursor string?)
(s/def ::method string?)
;; _meta: If specified, the caller is requesting out-of-band progress
;; notifications for this request (as represented by
;; notifications/progress). The value of this parameter is an opaque
;; token that will be attached to any subsequent notifications. The
;; receiver is not obligated to provide these notifications.
(s/def :request/_meta (s/keys :opt-un [::progressToken]))
;; Parameters
(s/def ::unknown-params (s/nilable (s/map-of string? any?)))
(s/def :request/params
  (s/merge (s/keys :opt-un [:request/_meta]) ::unknown-params))
(s/def ::request (s/keys :req-un [::method] :opt-un [:request/params]))

;;; Base interface: Notification
(s/def ::additional-metadata (s/map-of string? any?))
;; _meta: This parameter name is reserved by MCP to allow clients and servers
;; to
;; attach additional metadata to their notifications.
(s/def :notification/_meta ::additional-metadata)
(s/def :notification/params
  (s/merge (s/keys :opt-un [:notification/_meta]) ::unknown-params))
(s/def ::notification
  (s/keys :req-un [::method] :opt-un [:notification/params]))

;;; Base interface: Result
;; _meta: This result property is reserved by the protocol to allow clients and
;; servers to attach additional metadata to their responses.
(s/def :result/_meta ::additional-metadata)
(s/def ::result (s/merge (s/keys :opt-un [:result/_meta]) ::unknown-params))


;;; JSON-RPC
(s/def ::jsonrpc #(= jsonrpc-version %))
(s/def :request/id
  (s/or :str string?
        :num number?))
;; Standard error codes
(def parse-error -32700)
(def invalid-request -32600)
(def method-not-found -32601)
(def invalid-params -32602)
(def internal-error -32603)
;; The error type that occurred.
(s/def :error/code
  #{parse-error invalid-request method-not-found invalid-params internal-error})
;; A short description of the error. The message SHOULD be limited to a concise
;; single sentence.
(s/def :error/message string?)
;; Additional information about the error. The value of this member is defined
;; by the sender (e.g. detailed error information, nested errors etc.).
(s/def :error/data any?)
(s/def ::error
  (s/keys :req-un [:error/code :error/message] :opt-un [:error/data]))

;; JSONRPCRequest: A request that expects a response.
(s/def :jsonrpc/request
  (s/merge ::request (s/keys :req-un [::jsonrpc :request/id])))
;; JSONRPCNotification: A notification which does not expect a response.
(s/def :jsonrpc/notification
  (s/merge ::notification (s/keys :req-un [::jsonrpc])))
;; JSONRPCResponse: A successful (non-error) response to a request.
(s/def :jsonrpc/response (s/keys :req-un [::jsonrpc :request/id ::result]))
;; JSONRPCError: A response to a request that indicates an error occurred.
(s/def :jsonrpc/error (s/keys :req-un [::jsonrpc :request/id ::error]))

;; Cancellation
;;
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
(s/def ::requestId :request/id)
(s/def ::reason string?)
(s/def :cancelled-notification/method #{"notifications/cancelled"})
(s/def :cancelled-notification/params
  (s/keys :req-un [::requestId] :opt-un [::reason]))

(s/def :notification/cancelled
  (s/merge ::notification (s/keys :req-un [:cancelled-notification/method
                                           :cancelled-notification/params])))
;;; Initialization
;;
;; This request is sent from the client to the server when it first connects,
;; asking it to begin initialization
(s/def ::protocolVersion string?)
(s/def :initialize/method #{"initialize"})
(s/def :initialize/params
  ;; The latest version of the Model Context Protocol that the client
  ;; supports. The client MAY decide to support older versions as well.
  (s/keys :req-un [::protocolVersion :client/capabilities ::clientInfo]))
(s/def :request/initialize
  (s/merge ::request (s/keys :req-un [:initialize/method :initialize/params])))

;; After receiving an initialize request from the client, the server sends this
;; response.
(s/def :initialize/instructions string?)
(s/def :result/initialize
  ;; The version of the Model Context Protocol that the server wants to
  ;; use. This may not match the version that the client requested. If the
  ;; client cannot support this version, it MUST disconnect.
  (s/merge ::result (s/keys :req-un [::protocolVersion :server/capabilities
                                     ::serverInfo]
                            ;; Instructions describing how to use the
                            ;; server and its features. This can be used by
                            ;; clients to improve the LLM's understanding
                            ;; of available tools, resources, etc. It can
                            ;; be thought of like a "hint" to the model.
                            ;; For example, this information MAY be added
                            ;; to the system prompt.
                            :opt-un [:initialize/instructions])))

;; This notification is sent from the client to the server after initialization
;; has finished.
(s/def :initialized/method #{"notifications/initialized"})
(s/def :notification/initialized
  (s/merge ::notification (s/keys :req-un [:initialized/method])))

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

;; Client Capabilities
;;
;; Capabilities a client may support. Known capabilities are defined here, in
;; this schema, but this is not a closed set: any client can define its own,
;; additional capabilities.
(s/def :capabilities/client
  (s/keys :opt-un [:capabilities/experimental :capabilities/roots
                   :capabilities/sampling]))

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
;; Server Capabilities
;;
;; Capabilities that a server may support. Known capabilities are defined here,
;; in this schema, but this is not a closed set: any server can define its own,
;; additional capabilities.
(s/def :capabilities/server
  (s/keys :opt-un [:capabilities/experimental :capabilities/logging
                   :capabilities/prompts :capabilities/resources
                   :capabilities/tools]))

;;; Implementation
;; Describes the name and version of an MCP implementation.
(s/def :implementation/name string?)
(s/def :implementation/version string?)
(s/def ::implementation
  (s/keys :req-un [:implementation/name :implementation/version]))

;;; Ping Request
;; A ping, issued by either the server or the client, to check that the other
;; party is still alive. The receiver must promptly respond, or else may be
;; disconnected.
(s/def :ping/method #{"ping"})
(s/def :request/ping (s/merge ::request (s/keys :req-un [:ping/method])))

;;; Progress Notification
;; An out-of-band notification used to inform the receiver of a progress update
;; for a long-running request.
(s/def :progress/method #{"notifications/progress"})
;; The progress thus far. This should increase every time progress is made,
;; even if the total is unknown.
(s/def ::progress number?)
;; Total number of items to process (or total progress required), if known.
(s/def ::total number?)
(s/def :progress/params
  (s/keys :req-un [::progressToken ::progress] :opt-un [::total]))
(s/def :notification/progress
  (s/merge ::notification (s/keys :req-un [:progress/method :progress/params])))

;;; Pagination
(s/def :paginated/params (s/keys :opt-un [::cursor]))
(s/def :request/paginated
  (s/merge ::request (s/keys :opt-un [:paginated/params])))

(s/def :paginated/nextCursor ::cursor)
(s/def :result/paginated
  (s/merge ::result (s/keys :opt-un [:paginated/nextCursor])))

;;; Resources
;; Sent from the client to request a list of resources the server has.
(s/def :list-resources/method #{"resources/list"})
(s/def :request/list-resources
  (s/merge :request/paginated (s/keys :req-un [:list-resources/method])))

;; The server's response to a resources/list request from the client.
(s/def :list-resources/resources (s/coll-of ::resource))
(s/def :result/list-resources
  (s/merge :result/paginated (s/keys :req-un [:list-resources/resources])))

;; Sent from the client to request a list of resource templates the server has.
(s/def :list-resource-templates/method #{"resources/templates/list"})
(s/def :request/list-resource-templates
  (s/merge :request/paginated (s/keys :req-un
                                        [:list-resource-templates/method])))

;; The server's response to a resources/templates/list request from the client.
(s/def :list-resource-templates/resourceTemplates
  (s/coll-of ::resource-template))
(s/def :result/list-resource-templates
  (s/merge :result/paginated
             (s/keys :req-un [:list-resource-templates/resourceTemplates])))

;; Sent from the client to the server, to read a specific resource URI.
(s/def :read-resource/method #{"resources/read"})
(s/def :read-resource/params (s/keys :req-un [:resource/uri]))
(s/def :request/read-resource
  (s/merge ::request (s/keys :req-un [:read-resource/method
                                      :read-resource/params])))

;; The server's response to a resources/read request from the client.
(s/def :read-resource/content
  (s/or :text-resource :contents/text-resource
        :blob-resource :contents/blob-resource))
(s/def :read-resource/contents (s/coll-of :read-resource/content))
(s/def :result/read-resource
  (s/merge ::result (s/keys :req-un [:read-resource/contents])))

;;; Resource List Changed Notification
;; An optional notification from the server to the client, informing
;; it that the list of resources it can read from has changed. This
;; may be issued by servers without any previous subscription from the
;; client.
(s/def :resource-list-changed/method #{"notifications/resources/list_changed"})
(s/def :notification/resource-list-changed
  (s/merge ::notification (s/keys :req-un [:resource-list-changed/method])))

;;; Resource Subscribe/Unsubscribe
;; Sent from the client to request resources/updated notifications from the
;; server whenever a particular resource changes.
(s/def :subscribe/method #{"resources/subscribe"})
(s/def :subscribe/params (s/keys :req-un [:resource/uri]))
(s/def :request/subscribe
  (s/merge ::request (s/keys :req-un [:subscribe/method :subscribe/params])))

;; Sent from the client to request cancellation of resources/updated
;; notifications from the server. This should follow a previous
;; resources/subscribe request.
(s/def :unsubscribe/method #{"resources/unsubscribe"})
(s/def :unsubscribe/params (s/keys :req-un [:resource/uri]))
(s/def :request/unsubscribe
  (s/merge ::request (s/keys :req-un [:unsubscribe/method
                                      :unsubscribe/params])))

;;; Resource Updated Notification
;; A notification from the server to the client, informing it that a
;; resource has changed and may need to be read again. This should
;; only be sent if the client previously sent a resources/subscribe
;; request.
(s/def :resource-updated/method #{"notifications/resources/updated"})
(s/def :resource-updated/params (s/keys :req-un [:resource/uri]))
(s/def :notification/resource-updated
  (s/merge ::notification (s/keys :req-un [:resource-updated/method
                                           :resource-updated/params])))

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
(s/def ::resource
  (s/merge ::annotated (s/keys :req-un [:resource/uri :resource/name]
                               :opt-un [:resource/description
                                        :resource/mimeType])))

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
;; Sent from the client to request a list of prompts and prompt templates the
;; server has.
(s/def :list-prompts/method #{"prompts/list"})
(s/def :request/list-prompts
  (s/merge :request/paginated (s/keys :req-un [:list-prompts/method])))

;; The server's response to a prompts/list request from the client.
(s/def :list-prompts/prompts (s/coll-of ::prompt))
(s/def :result/list-prompts
  (s/merge :result/paginated (s/keys :req-un [:list-prompts/prompts])))

;;; Get Prompt
;; Used by the client to get a prompt provided by the server.
(s/def :get-prompt/method #{"prompts/get"})
(s/def :get-prompt/arguments (s/map-of string? string?))
(s/def :get-prompt/params
  (s/keys :req-un [:prompt/name] :opt-un [:get-prompt/arguments]))
(s/def :request/get-prompt
  (s/merge ::request (s/keys :req-un [:get-prompt/method :get-prompt/params])))

;; The server's response to a prompts/get request from the client.
(s/def :get-prompt/messages (s/coll-of ::prompt-message))
(s/def :result/get-prompt
  (s/merge ::result (s/keys :req-un [:get-prompt/messages]
                            :opt-un [:prompt/description])))

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
  (s/or :text-content :content/text
        :image-content :content/image
        :audio-content :content/audio
        :embedded-resource :resource/embedded))
(s/def ::prompt-message (s/keys :req-un [::role :prompt-message/content]))

;;; Embedded Resource
;; The contents of a resource, embedded into a prompt or tool call result. It
;; is
;; up to the client how best to render embedded resources for the benefit of
;; the
;; LLM and/or the user.
(s/def :embedded-resource/type #{"resource"})
(s/def :embedded-resource/resource
  (s/or :text-resource :contents/text-resource
        :blob-resource :contents/blob-resource))
(s/def :resource/embedded
  (s/merge ::annotated (s/keys :req-un [:embedded-resource/type
                                        :embedded-resource/resource])))

;;; Prompt List Changed Notification
;; An optional notification from the server to the client, informing it that
;; the
;; list of prompts it offers has changed. This may be issued by servers without
;; any previous subscription from the client.
(s/def :prompt-list-changed/method #{"notifications/prompts/list_changed"})
(s/def :notification/prompt-list-changed
  (s/merge ::notification (s/keys :req-un [:prompt-list-changed/method])))

;;; Tools
;; Sent from the client to request a list of tools the server has.
(s/def :list-tools/method #{"tools/list"})
(s/def :request/list-tools
  (s/merge :request/paginated (s/keys :req-un [:list-tools/method])))

;; The server's response to a tools/list request from the client.
(s/def :list-tools/tools (s/coll-of ::tool))
(s/def :result/list-tools
  (s/merge :result/paginated (s/keys :req-un [:list-tools/tools])))

;; Tool Call
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
(s/def :call-tool/content ;; yes, this is a collection, and the name is not
                          ;; `contents`. This looks like a mistake they
                          ;; made and kept for backwards compatibility.
  (s/coll-of (s/or :text :content/text
                   :image :content/image
                   :audio :content/audio
                   :resource :resource/embedded)))
;; If not set, this is assumed to be false (the call was successful).
(s/def :call-tool/isError boolean?)
(s/def :result/call-tool
  (s/merge ::result (s/keys :req-un [:call-tool/content]
                            :opt-un [:call-tool/isError])))

;; Used by the client to invoke a tool provided by the server.
(s/def :call-tool/method #{"tools/call"})
(s/def :call-tool/arguments (s/map-of string? any?))
(s/def :call-tool/params
  (s/keys :req-un [:tool/name] :opt-un [:call-tool/arguments]))
(s/def :request/call-tool
  (s/merge ::request (s/keys :req-un [:call-tool/method :call-tool/params])))

;; An optional notification from the server to the client, informing it that
;; the
;; list of tools it offers has changed. This may be issued by servers without
;; any previous subscription from the client.
(s/def :tool-list-changed/method #{"notifications/tools/list_changed"})
(s/def :notification/tool-list-changed
  (s/merge ::notification (s/keys :req-un [:tool-list-changed/method])))

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
(s/def ::tool
  (s/keys :req-un [:tool/name :tool/inputSchema] :opt-un [:tool/description]))

;;; Logging
;; A request from the client to the server, to enable or adjust logging.
(s/def :set-level/method #{"logging/setLevel"})
;; The level of logging that the client wants to receive from the server. The
;; server should send all logs at this level and higher (i.e., more severe) to
;; the client as notifications/logging/message.
(s/def :set-level/params (s/keys :req-un [:logging/level]))
(s/def :request/set-level
  (s/merge ::request (s/keys :req-un [:set-level/method :set-level/params])))

;; Notification of a log message passed from server to client. If no
;; logging/setLevel request has been sent from the client, the server MAY
;; decide
;; which messages to send automatically.
(s/def :logging-message/method #{"notifications/message"})
(s/def :logging-message/logger string?)
(s/def :logging-message/data any?)
(s/def :logging-message/params
  (s/keys :req-un [:logging/level :logging-message/data]
          :opt-un [:logging-message/logger]))
(s/def :notification/logging-message
  (s/merge ::notification (s/keys :req-un [:logging-message/method
                                           :logging-message/params])))
;; The severity of a log message. These map to syslog message severities, as
;; specified in RFC-5424:
;; https://datatracker.ietf.org/doc/html/rfc5424#section-6.2.1
(s/def :logging/level
  #{"debug" "info" "notice" "warning" "error" "critical" "alert" "emergency"})

;;; Message Content Types
(s/def :content/type #{"text" "image" "audio" "resource"})
(s/def :content/text string?)
(s/def :content/data string?)
(s/def :content/mimeType string?)
(s/def :content/resource ::resource-contents)

(s/def ::text-content
  (s/merge ::annotated (s/keys :req-un [:content/type :content/text])))

(s/def ::image-content
  (s/merge ::annotated (s/keys :req-un [:content/type :content/data
                                        :content/mimeType])))

(s/def ::audio-content
  (s/merge ::annotated (s/keys :req-un [:content/type :content/data
                                        :content/mimeType])))


;;; Sampling
(s/def :create-message/method #{"sampling/createMessage"})
(s/def :create-message/messages (s/coll-of ::sampling-message))
(s/def :create-message/modelPreferences ::model-preferences)
(s/def :create-message/systemPrompt string?)
(s/def :create-message/includeContext #{"none" "thisServer" "allServers"})
(s/def :create-message/temperature number?)
(s/def :create-message/maxTokens number?)
(s/def :create-message/stopSequences (s/coll-of string?))
(s/def :create-message/metadata any?)
(s/def :create-message/params
  (s/keys :req-un [:create-message/messages :create-message/maxTokens]
          :opt-un [:create-message/modelPreferences :create-message/systemPrompt
                   :create-message/includeContext :create-message/temperature
                   :create-message/stopSequences :create-message/metadata]))
(s/def :request/create-message
  (s/merge ::request (s/keys :req-un [:create-message/method
                                      :create-message/params])))

(s/def :sampling-message/model string?)
(s/def :sampling-message/stopReason string?)
(s/def ::sampling-message
  (s/keys :req-un [::role]
          :opt-un [:sampling-message/model :sampling-message/stopReason]))

(s/def :model-hint/name string?)
(s/def ::model-hint (s/keys :opt-un [:model-hint/name]))

(s/def :model-preferences/hints (s/coll-of ::model-hint))
(s/def :model-preferences/costPriority number?)
(s/def :model-preferences/speedPriority number?)
(s/def :model-preferences/intelligencePriority number?)
(s/def ::model-preferences
  (s/keys :opt-un [:model-preferences/hints :model-preferences/costPriority
                   :model-preferences/speedPriority
                   :model-preferences/intelligencePriority]))

;;; Roots
(s/def :list-roots/method #{"roots/list"})
(s/def :request/list-roots
  (s/merge ::request (s/keys :req-un [:list-roots/method])))

(s/def :root/uri string?)
(s/def :root/name string?)
(s/def ::root (s/keys :req-un [:root/uri] :opt-un [:root/name]))

(s/def :list-roots/roots (s/coll-of ::root))
(s/def :result/list-roots
  (s/merge ::result (s/keys :req-un [:list-roots/roots])))

(s/def :roots-list-changed/method #{"notifications/roots/list_changed"})
(s/def :notification/roots-list-changed
  (s/merge ::notification (s/keys :req-un [:roots-list-changed/method])))


;; Helper functions for resource validation
(defn valid-resource? [resource] (s/valid? ::resource resource))

(defn explain-resource [resource] (s/explain-str ::resource resource))

;; Helper functions for prompt validation
(defn valid-prompt? [prompt] (s/valid? ::prompt prompt))

(defn explain-prompt [prompt] (s/explain-str ::prompt prompt))

;; Helper functions for tool validation
(defn valid-tool? [tool] (s/valid? ::tool tool))

(defn explain-tool [tool] (s/explain-str ::tool tool))

;; Helper functions
(defn valid-request? [req] (s/valid? ::request req))

(defn valid-response? [res] (s/valid? ::response res))

(defn valid-error? [err] (s/valid? ::error-response err))

(defn valid-notification? [notif] (s/valid? ::notification notif))

(defn explain-request [req] (s/explain-str ::request req))

(defn explain-response [res] (s/explain-str ::response res))

(defn explain-error [err] (s/explain-str ::error-response err))

(defn explain-notification [notif] (s/explain-str ::notification notif))

;; Helper functions for root validation
(defn valid-root? [root] (s/valid? ::root root))

(defn explain-root [root] (s/explain-str ::root root))

;; Helper functions for message content validation
(defn valid-text-content? [content] (s/valid? ::text-content content))

(defn valid-image-content? [content] (s/valid? ::image-content content))

(defn valid-audio-content? [content] (s/valid? ::audio-content content))

(defn valid-embedded-resource? [content] (s/valid? ::embedded-resource content))

(defn explain-text-content [content] (s/explain-str ::text-content content))

(defn explain-image-content [content] (s/explain-str ::image-content content))

(defn explain-audio-content [content] (s/explain-str ::audio-content content))

(defn explain-embedded-resource
  [content]
  (s/explain-str ::embedded-resource content))

;; Helper functions for sampling validation
(defn valid-sampling-message? [msg] (s/valid? ::sampling-message msg))

(defn valid-model-preferences? [prefs] (s/valid? ::model-preferences prefs))

(defn explain-sampling-message [msg] (s/explain-str ::sampling-message msg))

(defn explain-model-preferences
  [prefs]
  (s/explain-str ::model-preferences prefs))

;; Helper functions for implementation validation
(defn valid-implementation? [impl] (s/valid? ::implementation impl))

(defn explain-implementation [impl] (s/explain-str ::implementation impl))

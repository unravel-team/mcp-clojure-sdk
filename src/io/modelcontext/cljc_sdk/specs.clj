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
;; The URI of the resource to read. The URI can use any protocol; it is up to
;; the server how to interpret it.
(s/def :resource/uri string?)
(s/def :read-resource/params (s/keys :req-un [:resource/uri]))
(s/def :request/read-resource
  (s/merge ::request (s/keys :req-un [:read-resource/method
                                      :read-resource/params])))

;; The server's response to a resources/read request from the client.
(s/def :read-resouces/text-resource-contents
  (s/coll-of :resource/text-resource))
(s/def :read-resouces/blob-resource-contents
  (s/coll-of :resource/blob-resource))
(s/def :read-resource/contents
  (s/or :text-resources :read-resouces/text-resource-contents
        :blob-resources :read-resource/blob-resource-contents))
(s/def :result/read-resource
  (s/merge ::result (s/keys :req-un [:read-resource/contents])))

;;; Resource List Changed Notification
(s/def :resource-list-changed/method #{"notifications/resources/list_changed"})
(s/def :notification/resource-list-changed
  (s/merge ::notification (s/keys :req-un [:resource-list-changed/method])))

;;; Resource Subscribe/Unsubscribe
(s/def :subscribe/method #{"resources/subscribe"})
(s/def :subscribe/params (s/keys :req-un [:resource/uri]))
(s/def :request/subscribe
  (s/merge ::request (s/keys :req-un [:subscribe/method :subscribe/params])))

(s/def :unsubscribe/method #{"resources/unsubscribe"})
(s/def :unsubscribe/params (s/keys :req-un [:resource/uri]))
(s/def :request/unsubscribe
  (s/merge ::request (s/keys :req-un [:unsubscribe/method :unsubscribe/params])))

;;; Resource Updated Notification
(s/def :resource-updated/method #{"notifications/resources/updated"})
(s/def :resource-updated/params (s/keys :req-un [:resource/uri]))
(s/def :notification/resource-updated
  (s/merge ::notification (s/keys :req-un [:resource-updated/method
                                          :resource-updated/params])))

;;; Resource
(s/def :resource/name string?)
(s/def :resource/description string?)
(s/def :resource/mimeType string?)
(s/def ::resource
  (s/merge ::annotated (s/keys :req-un [:resource/uri :resource/name]
                              :opt-un [:resource/description 
                                     :resource/mimeType])))

;;; Resource Template
(s/def :resource-template/uriTemplate string?)
(s/def ::resource-template
  (s/merge ::annotated 
           (s/keys :req-un [:resource-template/uriTemplate :resource/name]
                   :opt-un [:resource/description :resource/mimeType])))

;;; Resource Contents
(s/def :resource/text string?)
(s/def :resource/blob string?)

(s/def :resource/text-resource
  (s/merge ::resource-contents (s/keys :req-un [:resource/text])))

(s/def :resource/blob-resource
  (s/merge ::resource-contents (s/keys :req-un [:resource/blob])))

(s/def ::resource-contents
  (s/keys :req-un [:resource/uri]
          :opt-un [:resource/mimeType]))

;;; Prompts
(s/def :list-prompts/method #{"prompts/list"})
(s/def :request/list-prompts
  (s/merge :request/paginated (s/keys :req-un [:list-prompts/method])))

(s/def :list-prompts/prompts (s/coll-of ::prompt))
(s/def :result/list-prompts
  (s/merge :result/paginated (s/keys :req-un [:list-prompts/prompts])))

(s/def :get-prompt/method #{"prompts/get"})
(s/def :prompt/arguments (s/map-of string? string?))
(s/def :get-prompt/params 
  (s/keys :req-un [:prompt/name]
          :opt-un [:prompt/arguments]))
(s/def :request/get-prompt
  (s/merge ::request (s/keys :req-un [:get-prompt/method
                                     :get-prompt/params])))

(s/def :get-prompt/messages (s/coll-of ::prompt-message))
(s/def :result/get-prompt
  (s/merge ::result (s/keys :req-un [:get-prompt/messages]
                           :opt-un [:prompt/description])))

;;; Prompt
(s/def :prompt/name string?)
(s/def :prompt/arguments (s/coll-of ::prompt-argument))
(s/def ::prompt
  (s/keys :req-un [:prompt/name]
          :opt-un [:prompt/description :prompt/arguments]))

;;; Prompt Argument
(s/def :prompt-argument/name string?)
(s/def :prompt-argument/description string?)
(s/def :prompt-argument/required boolean?)
(s/def ::prompt-argument
  (s/keys :req-un [:prompt-argument/name]
          :opt-un [:prompt-argument/description
                  :prompt-argument/required]))

;;; Prompt List Changed Notification
(s/def :prompt-list-changed/method #{"notifications/prompts/list_changed"})
(s/def :notification/prompt-list-changed
  (s/merge ::notification (s/keys :req-un [:prompt-list-changed/method])))

;;; Role
(s/def ::role #{"user" "assistant"})

;;; Message Content Types
(s/def :content/type #{"text" "image" "audio" "resource"})
(s/def :content/text string?)
(s/def :content/data string?)
(s/def :content/mimeType string?)
(s/def :content/resource ::resource-contents)

(s/def ::text-content
  (s/merge ::annotated 
           (s/keys :req-un [:content/type :content/text])))

(s/def ::image-content
  (s/merge ::annotated 
           (s/keys :req-un [:content/type :content/data :content/mimeType])))

(s/def ::audio-content
  (s/merge ::annotated 
           (s/keys :req-un [:content/type :content/data :content/mimeType])))

(s/def ::embedded-resource
  (s/merge ::annotated 
           (s/keys :req-un [:content/type :content/resource])))

(s/def ::prompt-message
  (s/keys :req-un [::role]
          :req [(or :content/text :content/data :content/resource)]))


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

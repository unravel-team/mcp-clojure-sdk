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
(s/def ::unknown-params (s/nilable (s/map-of keyword? any?)))
(s/def :request/params
  (s/merge (s/keys :opt-un [:request/_meta]) ::unknown-params))
(s/def ::request (s/keys :req-un [::method] :opt-un [:request/params]))

;;; Base interface: Notification
(s/def ::additional-metadata (s/map-of keyword? any?))
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
(s/def :initialize-request/method #{"initialize"})
(s/def :initialize-request/params
  ;; The latest version of the Model Context Protocol that the client
  ;; supports. The client MAY decide to support older versions as well.
  (s/keys :req-un [::protocolVersion :client/capabilities ::clientInfo]))
(s/def :request/initialize
  (s/merge ::request (s/keys :req-un [:initialize-request/method
                                      :initialize-request/params])))

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
(s/def :initialized-notification/method #{"notifications/initialized"})
(s/def :notification/initialized
  (s/merge ::notification (s/keys :req-un [:initialized-notification/method])))



;; Implementation info
(s/def ::clientInfo (s/keys :req-un [::name ::version]))
(s/def ::serverInfo (s/keys :req-un [::name ::version]))

;; Capabilities
(s/def ::roots (s/keys :opt-un [::listChanged]))

(s/def ::prompts (s/keys :opt-un [::listChanged]))

(s/def ::resources (s/keys :opt-un [::subscribe ::listChanged]))

(s/def ::tools (s/keys :opt-un [::listChanged]))

(s/def ::capabilities (s/keys :opt-un [::experimental ::roots ::sampling]))

(s/def ::capabilities
  (s/keys :opt-un [::experimental ::logging ::prompts ::resources ::tools]))

;; Resource content
(s/def ::uri string?)
(s/def ::name string?)
(s/def ::description string?)
(s/def ::mimeType string?)

;; Resources
(def valid-uri-scheme? #{"file" "http" "https" "data" "resource"})
(s/def ::uri
  (s/and string?
         #(try (let [uri (java.net.URI. %)]
                 (valid-uri-scheme? (.getScheme uri)))
               (catch Exception _ false))))
(s/def ::name string?)
(s/def ::description string?)
(s/def ::mime-type string?)

(s/def ::resource
  (s/keys :req-un [::uri ::name] :opt-un [::description ::mime-type]))

;; Helper functions for resource validation
(defn valid-resource? [resource] (s/valid? ::resource resource))

(defn explain-resource [resource] (s/explain-str ::resource resource))
;; Prompts
(s/def ::role #{"user" "assistant"})
(s/def :argument/required boolean?)
(s/def ::argument
  (s/keys :req-un [::name] :opt-un [::description :argument/required]))
(s/def ::arguments (s/coll-of ::argument))
(s/def ::content-type #{"text" "image" "resource"})
(s/def ::text string?)
(s/def ::content (s/keys :req-un [::content-type] :opt-un [::text]))
(s/def ::message (s/keys :req-un [::role ::content]))
(s/def ::messages (s/coll-of ::message))

(s/def ::prompt (s/keys :req-un [::name] :opt-un [::description ::arguments]))

;; Helper functions for prompt validation
(defn valid-prompt? [prompt] (s/valid? ::prompt prompt))

(defn explain-prompt [prompt] (s/explain-str ::prompt prompt))

(s/def ::resource-contents (s/keys :req-un [::uri] :opt-un [::mime-type]))

(s/def ::text-contents (s/merge ::resource-contents (s/keys :req-un [::text])))

(s/def ::blob-contents (s/merge ::resource-contents (s/keys :req-un [::blob])))

;; Tools
(s/def ::type #{"object"})
(s/def :property/type #{"string" "number" "boolean" "array" "object"})
(s/def ::property (s/keys :req-un [:property/type] :opt-un [::description]))
(s/def ::properties (s/map-of string? ::property))
(s/def :properties/required (s/coll-of string?))

(s/def ::inputSchema
  (s/keys :req-un [::type] :opt-un [::properties :properties/required]))

(s/def ::tool (s/keys :req-un [::name ::inputSchema] :opt-un [::description]))

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

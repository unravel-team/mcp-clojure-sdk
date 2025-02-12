(ns io.modelcontext.cljc-sdk.specs
  (:require [clojure.spec.alpha :as s]))

;; Protocol constants
(def latest-protocol-version "DRAFT-2025-v1")
(def jsonrpc-version "2.0")

;; Basic types
(s/def ::id
  (s/or :str string?
        :num number?))
(s/def ::method string?)
(s/def ::progressToken
  (s/or :str string?
        :num number?))
(s/def ::cursor string?)

;; Meta parameters
(s/def ::_meta (s/keys :opt-un [::progressToken]))

;; Parameters
(s/def ::params (s/nilable (s/map-of keyword? any?)))

;; Basic message components
(s/def ::jsonrpc #(= jsonrpc-version %))
(s/def ::result map?)
(s/def ::code int?)
(s/def ::message string?)
(s/def ::data any?)

;; Error object
(s/def ::error
  (s/keys :req-un [::code ::message] :opt-un [::data]))

;; JSON-RPC Messages
(s/def ::request (s/keys :req-un [::jsonrpc ::id ::method] 
                        :opt-un [::params]))

(s/def ::notification (s/keys :req-un [::jsonrpc ::method] :opt-un [::params]))

(s/def ::response (s/keys :req-un [::jsonrpc ::id] :opt-un [::result]))

(s/def ::error-response (s/keys :req-un [::jsonrpc ::id ::error]))

;; Standard error codes
(def parse-error -32700)
(def invalid-request -32600)
(def method-not-found -32601)
(def invalid-params -32602)
(def internal-error -32603)

;; Implementation info
(s/def ::clientInfo (s/keys :req-un [::name ::version]))
(s/def ::serverInfo (s/keys :req-un [::name ::version]))

;; Capabilities
(s/def ::roots (s/keys :opt-un [::listChanged]))

(s/def ::prompts (s/keys :opt-un [::listChanged]))

(s/def ::resources (s/keys :opt-un [::subscribe ::listChanged]))

(s/def ::tools (s/keys :opt-un [::listChanged]))

(s/def ::capabilities
  (s/keys :opt-un [::experimental ::roots ::sampling]))

(s/def ::capabilities
  (s/keys :opt-un [::experimental ::logging ::prompts ::resources ::tools]))

;; Initialization
(s/def ::initialize
  (s/merge ::request (s/keys :req-un [::protocolVersion ::capabilities
                                     ::clientInfo])))

(s/def ::initialize
  (s/keys :req-un [::protocolVersion ::capabilities ::serverInfo]
          :opt-un [::instructions]))

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

(s/def ::input-schema
  (s/keys :req-un [::type] :opt-un [::properties :properties/required]))

(s/def ::tool (s/keys :req-un [::name ::input-schema] :opt-un [::description]))

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

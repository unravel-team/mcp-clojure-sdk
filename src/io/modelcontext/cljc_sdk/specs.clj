(ns io.modelcontext.cljc-sdk.specs
  (:require [clojure.spec.alpha :as s]))

;; Protocol constants
(def latest-protocol-version "DRAFT-2025-v1")
(def jsonrpc-version "2.0")

;; Basic types
(s/def ::request-id
  (s/or :str string?
        :num number?))
(s/def ::method string?)
(s/def ::progress-token
  (s/or :str string?
        :num number?))
(s/def ::cursor string?)

;; Meta parameters
(s/def ::meta-params (s/keys :opt-un [::progress-token]))

;; Parameters
(s/def ::params (s/nilable (s/map-of keyword? any?)))

;; Basic message components
(s/def ::jsonrpc #(= jsonrpc-version %))
(s/def ::result map?)
(s/def ::error-code int?)
(s/def ::error-message string?)
(s/def ::error-data any?)

;; Error object
(s/def ::error
  (s/keys :req-un [::error-code ::error-message] :opt-un [::error-data]))

;; JSON-RPC Messages
(s/def ::request (s/keys :req-un [::jsonrpc ::id ::method] :opt-un [::params]))

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
(s/def ::implementation (s/keys :req-un [::name ::version]))

;; Capabilities
(s/def ::roots-capability (s/keys :opt-un [::list-changed]))

(s/def ::prompts-capability (s/keys :opt-un [::list-changed]))

(s/def ::resources-capability (s/keys :opt-un [::subscribe ::list-changed]))

(s/def ::tools-capability (s/keys :opt-un [::list-changed]))

(s/def ::client-capabilities
  (s/keys :opt-un [::experimental ::roots ::sampling]))

(s/def ::server-capabilities
  (s/keys :opt-un [::experimental ::logging ::prompts ::resources ::tools]))

;; Initialization
(s/def ::initialize-request
  (s/merge ::request (s/keys :req-un [::protocol-version ::capabilities
                                      ::client-info])))

(s/def ::initialize-result
  (s/keys :req-un [::protocol-version ::capabilities ::server-info]
          :opt-un [::instructions]))

;; Resource content
(s/def ::uri string?)
(s/def ::name string?)
(s/def ::description string?)
(s/def ::mime-type string?)

(s/def ::resource
  (s/keys :req-un [::uri ::name] :opt-un [::description ::mime-type]))

(s/def ::resource-contents (s/keys :req-un [::uri] :opt-un [::mime-type]))

(s/def ::text-contents (s/merge ::resource-contents (s/keys :req-un [::text])))

(s/def ::blob-contents (s/merge ::resource-contents (s/keys :req-un [::blob])))

;; Tools
(s/def ::tool-schema
  (s/keys :req-un [::type] :opt-un [::properties ::required]))

(s/def ::tool (s/keys :req-un [::name ::input-schema] :opt-un [::description]))

;; Helper functions
(defn valid-request? [req] (s/valid? ::request req))

(defn valid-response? [res] (s/valid? ::response res))

(defn valid-error? [err] (s/valid? ::error-response err))

(defn valid-notification? [notif] (s/valid? ::notification notif))

(defn explain-request [req] (s/explain-str ::request req))

(defn explain-response [res] (s/explain-str ::response res))

(defn explain-error [err] (s/explain-str ::error-response err))

(defn explain-notification [notif] (s/explain-str ::notification notif))

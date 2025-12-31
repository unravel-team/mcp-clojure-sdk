(ns integration.fixture
  (:require [io.modelcontext.clojure-sdk.specs :as specs]))

(def default-client-info
  {:name "mcp-clojure-sdk-integration-test", :version "1.0.0"})

(defn initialize-request
  ([] (initialize-request {}))
  ([params]
   ["initialize"
    (merge {:protocolVersion (first specs/supported-protocol-versions),
            :capabilities {},
            :clientInfo default-client-info}
           params)]))

(defn initialized-notification [] ["notifications/initialized" {}])

(defn ping-request [] ["ping" {}])

(defn list-tools-request
  ([] (list-tools-request nil))
  ([cursor] ["tools/list" (cond-> {} cursor (assoc :cursor cursor))]))

(defn call-tool-request
  ([name] (call-tool-request name nil))
  ([name arguments]
   ["tools/call" (cond-> {:name name} arguments (assoc :arguments arguments))]))

(defn list-resources-request
  ([] (list-resources-request nil))
  ([cursor] ["resources/list" (cond-> {} cursor (assoc :cursor cursor))]))

(defn list-resource-templates-request
  ([] (list-resource-templates-request nil))
  ([cursor]
   ["resources/templates/list" (cond-> {} cursor (assoc :cursor cursor))]))

(defn read-resource-request [uri] ["resources/read" {:uri uri}])

(defn subscribe-request [uri] ["resources/subscribe" {:uri uri}])

(defn unsubscribe-request [uri] ["resources/unsubscribe" {:uri uri}])

(defn list-prompts-request
  ([] (list-prompts-request nil))
  ([cursor] ["prompts/list" (cond-> {} cursor (assoc :cursor cursor))]))

(defn get-prompt-request
  ([name] (get-prompt-request name nil))
  ([name arguments]
   ["prompts/get"
    (cond-> {:name name} arguments (assoc :arguments arguments))]))

(defn complete-request
  [ref argument-name argument-value]
  ["completion/complete"
   {:ref ref, :argument {:name argument-name, :value argument-value}}])

(defn set-logging-level-request [level] ["logging/setLevel" {:level level}])

(defn cancelled-notification
  ([request-id] (cancelled-notification request-id nil))
  ([request-id reason]
   ["notifications/cancelled"
    (cond-> {:requestId request-id} reason (assoc :reason reason))]))

(defn roots-list-changed-notification
  []
  ["notifications/roots/list_changed" {}])

(ns io.modelcontext.clojure-sdk.mcp.errors
  (:require [jsonrpc4clj.errors :as jsonrpc.errors]))

(set! *warn-on-reflection* true)

(def by-key
  (assoc jsonrpc.errors/by-key
    :tool-not-found {:code -32601, :message "Tool not found"}
    :resource-not-found {:code -32601, :message "Resource not found"}
    :prompt-not-found {:code -32601, :message "Prompt not found"}))

(defn body
  "Returns a JSON-RPC error object with the code and, if provided, the message
  and data.

  `error-code` should be a keyword as per `by-key`."
  ([error-code data]
   (-> (by-key error-code)
       (assoc :data data)))
  ([error-code message data]
   (-> (by-key error-code)
       (assoc :message message)
       (assoc :data data))))

(ns io.modelcontext.clojure-sdk.mcp.errors
  (:require [lsp4clj.lsp.errors :as lsp.errors]))

(set! *warn-on-reflection* true)

(def by-key
  (assoc lsp.errors/by-key
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

(ns bench-cljc-server
  "Benchmark MCP stdio server built on this repo's mcp-clojure-sdk.
  Exposes the same three tools as `bench-java-server`."
  (:require [io.modelcontext.clojure-sdk.stdio-server :as io-server]))

(def message-schema
  {:type "object",
   :properties {"message" {:type "string"}},
   :required ["message"]})

(defn big-echo
  "Repeats `message` until the result is ~64KB of text."
  ^String [^String message]
  (let [sb (StringBuilder.)]
    (while (< (.length sb) 65536) (.append sb message))
    (.toString sb)))

(def tool-echo
  {:name "echo",
   :description "Echo the message back as text",
   :inputSchema message-schema,
   :handler (fn [{:keys [message]}] {:type "text", :text message})})

(def tool-bigecho
  {:name "bigecho",
   :description "Echo the message repeated until ~64KB of text",
   :inputSchema message-schema,
   :handler (fn [{:keys [message]}] {:type "text", :text (big-echo message)})})

(def tool-sleepy
  {:name "sleepy",
   :description "Sleep for the given number of milliseconds, then return done",
   :inputSchema
   {:type "object", :properties {"ms" {:type "number"}}, :required ["ms"]},
   :handler
   (fn [{:keys [ms]}] (Thread/sleep (long ms)) {:type "text", :text "done"})})

(def server-spec
  {:name "bench-cljc-server",
   :version "0.1.0",
   :server-id "bench-cljc-server",
   :tools [tool-echo tool-bigecho tool-sleepy]})

(defn -main [& _args] @(io-server/run! server-spec))

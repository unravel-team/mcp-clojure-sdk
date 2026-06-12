(ns bench-java-server
  "Benchmark MCP stdio server built on the Java MCP SDK. The interop
  mirrors clojure-mcp's `clojure-mcp.core` exactly, since that is the
  baseline we compare against."
  (:require [clojure.data.json :as json])
  (:import [io.modelcontextprotocol.json McpJsonMapper]
           [io.modelcontextprotocol.json.jackson3 JacksonMcpJsonMapper]
           [io.modelcontextprotocol.server McpServer
            McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.server.transport
            StdioServerTransportProvider]
           [io.modelcontextprotocol.spec McpSchema$CallToolRequest
            McpSchema$CallToolResult McpSchema$ServerCapabilities
            McpSchema$TextContent McpSchema$Tool]
           [reactor.core.publisher Mono]
           [tools.jackson.databind.json JsonMapper]))

(defonce ^McpJsonMapper json-mapper (JacksonMcpJsonMapper. (JsonMapper.)))

(defn create-mono-from-callback
  "Creates a function that takes the exchange and the arguments map and
  returns a Mono promise. The callback function should take three
  arguments:
   - exchange: The MCP exchange object
   - arguments: The arguments map sent in the request
   - continuation: A function that will be called with the result and
     will fulfill the promise"
  [callback-fn]
  (fn [exchange arguments]
    (Mono/create (reify
                   java.util.function.Consumer
                     (accept [_this sink]
                       (callback-fn exchange
                                    arguments
                                    (fn [result] (.success sink result))))))))

(defn adapt-results
  ^McpSchema$CallToolResult [list-str error?]
  (-> (McpSchema$CallToolResult/builder)
      (.content (mapv #(McpSchema$TextContent. ^String %) list-str))
      (.isError ^Boolean error?)
      (.build)))

(defn create-async-tool
  "Creates an AsyncToolSpecification with the given parameters.

  Takes a map with :name, :description, :schema and :tool-fn keys.
  :tool-fn signature: (fn [exchange arg-map clj-result-k]) where arg-map
  has STRING keys and clj-result-k takes a vector of strings and an
  error flag."
  [{:keys [name description schema tool-fn]}]
  (let [schema-json (json/write-str schema)
        mcp-tool (-> (McpSchema$Tool/builder)
                     (.name ^String name)
                     (.description ^String description)
                     (.inputSchema json-mapper schema-json)
                     (.build))
        mono-fn (create-mono-from-callback
                  (fn [exchange arg-map mono-fill-k]
                    (let [clj-result-k (fn [res-list error?]
                                         (mono-fill-k (adapt-results res-list
                                                                     error?)))]
                      (tool-fn exchange arg-map clj-result-k))))]
    (McpServerFeatures$AsyncToolSpecification.
      mcp-tool
      (reify
        java.util.function.BiFunction
          (apply [_this exchange request]
            (let [arguments (.arguments ^McpSchema$CallToolRequest request)]
              (mono-fn exchange arguments)))))))

(defn add-tool
  [mcp-server tool-map]
  (-> (.addTool mcp-server (create-async-tool tool-map))
      (.subscribe)))

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
   :schema message-schema,
   :tool-fn (fn [_exchange arg-map clj-result-k]
              (clj-result-k [(get arg-map "message")] false))})

(def tool-bigecho
  {:name "bigecho",
   :description "Echo the message repeated until ~64KB of text",
   :schema message-schema,
   :tool-fn (fn [_exchange arg-map clj-result-k]
              (clj-result-k [(big-echo (get arg-map "message"))] false))})

(def tool-sleepy
  {:name "sleepy",
   :description "Sleep for the given number of milliseconds, then return done",
   :schema
   {:type "object", :properties {"ms" {:type "number"}}, :required ["ms"]},
   :tool-fn (fn [_exchange arg-map clj-result-k]
              (future (Thread/sleep (long (get arg-map "ms")))
                      (clj-result-k ["done"] false)))})

(defn mcp-server
  "Creates a basic stdio mcp server with the three benchmark tools."
  []
  (let [transport-provider (StdioServerTransportProvider. json-mapper)
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "bench-java-server" "0.1.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                      (.tools true)
                                      (.build)))
                   (.build))]
    (doseq [tool [tool-echo tool-bigecho tool-sleepy]] (add-tool server tool))
    server))

(defn -main [& _args] (mcp-server) @(promise))

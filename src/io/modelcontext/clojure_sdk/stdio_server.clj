(ns io.modelcontext.clojure-sdk.stdio-server
  (:require [clojure.core.async :as async]
            [io.modelcontext.clojure-sdk.server :as core]
            [io.modelcontext.clojure-sdk.io-chan :as mcp.io-chan]
            [jsonrpc4clj.server :as jsonrpc.server]
            [me.vedang.logger.interface :as log])
  (:refer-clojure :exclude [run!]))

(defn- monitor-server-logs
  [log-ch]
  ;; NOTE: We don't do this in `initialize`, because if anything bad
  ;; happened before `initialize`, we wouldn't get any logs.
  (async/go-loop []
    (when-let [log-args (async/<! log-ch)]
      (log/trace :level (first log-args) :args (rest log-args))
      (recur))))

(defn start!
  [server spec]
  (let [context (assoc (core/create-context! spec) :server server)]
    (log/info :msg "[STDIO SERVER] Starting server...")
    (monitor-server-logs (:log-ch server))
    (jsonrpc.server/start server context)))

;;;; Create server

(defn stdio-server
  "Starts a server reading from stdin and writing to stdout."
  [{:keys [in out], :as opts}]
  (let [in (or in System/in)
        out (or out System/out)
        input-ch (mcp.io-chan/input-stream->input-chan in)
        output-ch (mcp.io-chan/output-stream->output-chan out)]
    (jsonrpc.server/chan-server (assoc opts
                                  :in in
                                  :out out
                                  :input-ch input-ch
                                  :output-ch output-ch))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn run!
  [spec]
  (log/with-context {:server-id (:server-id spec)}
    (log/trace :fn run! :msg "Starting server!")
    (core/validate-spec! spec)
    (let [log-ch (async/chan (async/sliding-buffer 20))
          server (stdio-server {:log-ch log-ch})]
      (start! server spec))))

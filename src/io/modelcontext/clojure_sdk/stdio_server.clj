(ns io.modelcontext.clojure-sdk.stdio-server
  (:require [clojure.core.async :as async]
            [io.modelcontext.clojure-sdk.server :as core]
            [io.modelcontext.clojure-sdk.io-chan :as mcp.io-chan]
            [lsp4clj.server :as lsp.server]
            [me.vedang.logger.interface :as log])
  (:refer-clojure :exclude [run!]))

(defn- monitor-server-logs
  [log-ch]
  ;; NOTE: if this were moved to `initialize`, after timbre has been
  ;; configured, the server's startup logs and traces would appear in the
  ;; regular log file instead of the temp log file. We don't do this though
  ;; because if anything bad happened before `initialize`, we wouldn't get
  ;; any logs.
  (async/go-loop []
    (when-let [log-args (async/<! log-ch)]
      (log/trace :level (first log-args) :args (rest log-args))
      (recur))))

(defn start!
  [server spec]
  (let [context (assoc (core/create-context! spec) :server server)]
    (log/info :msg "[STDIO SERVER] Starting server...")
    (monitor-server-logs (:log-ch server))
    (lsp.server/start server context)))

;;;; Create server

(defn stdio-server
  "Starts a server reading from stdin and writing to stdout."
  [{:keys [in out], :as opts}]
  (let [in (or in System/in)
        out (or out System/out)
        input-ch (mcp.io-chan/input-stream->input-chan in)
        output-ch (mcp.io-chan/output-stream->output-chan out)]
    (lsp.server/chan-server (assoc opts
                              :in in
                              :out out
                              :input-ch input-ch
                              :output-ch output-ch))))

(defn run!
  [spec]
  (log/with-context {:server-id (:server-id spec)}
    (log/trace :fn run! :msg "Starting calculator server")
    (let [log-ch (async/chan (async/sliding-buffer 20))
          server (stdio-server
                   {:log-ch log-ch, :trace-ch log-ch, :trace-level :trace})]
      (start! server spec))))

(ns io.modelcontext.clojure-sdk.stdio-server
  (:require [clojure.core.async :as async]
            [io.modelcontext.clojure-sdk.server :as server]
            [lsp4clj.io-server :as lsp.io-server]
            [lsp4clj.server :as lsp.server]
            [me.vedang.logger.interface :as log]))

(defn- monitor-server-logs
  [log-ch]
  ;; NOTE: if this were moved to `initialize`, after timbre has been
  ;; configured, the server's startup logs and traces would appear in the
  ;; regular log file instead of the temp log file. We don't do this though
  ;; because if anything bad happened before `initialize`, we wouldn't get
  ;; any logs.
  (async/go-loop []
    (when-let [log-args (async/<! log-ch)]
      (log/info :level (first log-args) :args (rest log-args))
      (recur))))

(defn start-server!
  [server spec]
  (let [context (assoc (server/create-server-context! spec) :server server)]
    (log/info :msg "[SERVER] Starting server...")
    (monitor-server-logs (:log-ch server))
    (lsp.server/start server context)))

(defn run!
  [spec]
  (lsp.server/discarding-stdout
    (let [log-ch (async/chan (async/sliding-buffer 20))
          server (lsp.io-server/stdio-server
                   {:log-ch log-ch, :trace-ch log-ch, :trace-level :trace})]
      (start-server! server spec))))

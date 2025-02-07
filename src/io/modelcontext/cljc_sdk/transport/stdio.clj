(ns io.modelcontext.cljc-sdk.transport.stdio
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [io.modelcontext.cljc-sdk.core :as core]
            [me.vedang.logger.interface :as log])
  (:import [java.io BufferedReader BufferedWriter]))

(defrecord StdioTransport [^BufferedReader in ^BufferedWriter out msg-chan
                           running?]
  core/Transport
    (start! [this]
      (reset! running? true)
      ;; Start read loop
      (a/go-loop []
        (when @running?
          (when-let [line (.readLine in)] (a/>! msg-chan line))
          (recur)))
      this)
    (stop! [_this]
      (reset! running? false)
      (a/close! msg-chan)
      (.close in)
      (.close out))
    (send! [_this message]
      (locking out (.write out message) (.newLine out) (.flush out)))
    (receive! [_this] (a/<!! msg-chan)))

(defn create-stdio-transport
  "Creates a transport that communicates over stdin/stdout.
   For use with MCP servers that communicate via stdio."
  []
  (let [in (io/reader System/in)
        out (io/writer System/out)]
    (map->StdioTransport {:in (BufferedReader. in),
                          :out (BufferedWriter. out),
                          :msg-chan (a/chan 1024),
                          :running? (atom false)})))

(defn create-process-transport
  "Creates a transport that communicates with a subprocess via its stdin/stdout.
   For use with MCP clients that need to spawn server processes."
  [{:keys [command args env]}]
  (let [process-builder (ProcessBuilder. (cons command args))
        _ (when env (.putAll (.environment process-builder) env))
        process (.start process-builder)
        in (io/reader (.getInputStream process))
        out (io/writer (.getOutputStream process))
        err (io/reader (.getErrorStream process))]
    ;; Handle stderr in separate thread
    (a/thread (with-open [err-reader (BufferedReader. err)]
                (loop []
                  (when-let [line (.readLine err-reader)]
                    (log/warn :msg "Server stderr" :line line)
                    (recur)))))
    (map->StdioTransport {:in (BufferedReader. in),
                          :out (BufferedWriter. out),
                          :msg-chan (a/chan 1024),
                          :running? (atom false),
                          :process process})))

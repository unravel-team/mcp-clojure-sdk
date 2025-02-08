(ns io.modelcontext.cljc-sdk.transport.stdio
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [io.modelcontext.cljc-sdk.core :as core]
            [me.vedang.logger.interface :as log])
  (:import [java.io BufferedReader BufferedWriter InputStream OutputStream]))

(defn unclosable-input-stream
  [^InputStream wrapped]
  (proxy [InputStream] []
    (read
      ([] (.read wrapped))
      ([buf] (.read wrapped buf))
      ([buf off len] (.read wrapped buf off len)))
    (close [] nil))) ; Override close to do nothing

(defn unclosable-output-stream
  [^OutputStream wrapped]
  (proxy [OutputStream] []
    (write
      ([buf] (.write wrapped buf))
      ([buf off len] (.write wrapped buf off len)))
    (flush [] (.flush wrapped))
    (close [] nil))) ; Override close to do nothing

(defrecord StdioTransport [^BufferedReader in ^BufferedWriter out msg-chan
                           running? process]
  core/Transport
    (start! [this]
      (reset! running? true)
      (a/go-loop []
        (when @running?
          (when-let [line (.readLine in)] (a/>! msg-chan line))
          (recur)))
      this)
    (stop! [_this]
      (reset! running? false)
      (a/close! msg-chan)
      (when process (.destroy process))
      ;; Only flush the buffers, don't close system streams
      (.flush out))
    (send! [_this message]
      (locking out (.write out message) (.newLine out) (.flush out)))
    (receive! [_this] (a/<!! msg-chan)))

(defn create-stdio-transport
  "Creates a transport that communicates over stdin/stdout.
   For use with MCP servers that communicate via stdio."
  []
  (let [in (-> System/in
               unclosable-input-stream
               io/reader
               BufferedReader.)
        out (-> System/out
                unclosable-output-stream
                io/writer
                BufferedWriter.)]
    (map->StdioTransport
      {:in in, :out out, :msg-chan (a/chan 1024), :running? (atom false)})))

(defn create-process-transport
  "Creates a transport that communicates with a subprocess via its stdin/stdout.
   For use with MCP clients that need to spawn server processes."
  [{:keys [command args env]}]
  (let [process-builder (ProcessBuilder. (cons command args))
        _ (when env (.putAll (.environment process-builder) env))
        process (.start process-builder)
        in (-> process
               .getInputStream
               io/reader
               BufferedReader.)
        out (-> process
                .getOutputStream
                io/writer
                BufferedWriter.)
        err (-> process
                .getErrorStream
                io/reader)]
    (a/thread (with-open [err-reader (BufferedReader. err)]
                (loop []
                  (when-let [line (.readLine err-reader)]
                    (log/warn :msg "Server stderr" :line line)
                    (recur)))))
    (map->StdioTransport {:in in,
                          :out out,
                          :msg-chan (a/chan 1024),
                          :running? (atom false),
                          :process process})))

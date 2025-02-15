(ns io.modelcontext.cljc-sdk.transport.stdio
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [io.modelcontext.cljc-sdk.core :as core]
            [me.vedang.logger.interface :as log])
  (:import [java.io BufferedReader BufferedWriter InputStream OutputStream]
           [java.nio.charset Charset StandardCharsets]))

(def default-buffer-size 8192)

(defn- make-buffered-reader
  "Creates a buffered reader with specified encoding and buffer size."
  [^InputStream input-stream &
   {:keys [encoding buffer-size],
    :or {encoding "UTF-8", buffer-size default-buffer-size}}]
  (-> input-stream
      (io/reader :encoding encoding)
      (BufferedReader. buffer-size)))

(defn- make-buffered-writer
  "Creates a buffered writer with specified encoding and buffer size."
  [^OutputStream output-stream &
   {:keys [encoding buffer-size],
    :or {encoding "UTF-8", buffer-size default-buffer-size}}]
  (-> output-stream
      (io/writer :encoding encoding)
      (BufferedWriter. buffer-size)))

(defn unclosable-input-stream
  "Creates an input stream that ignores close requests."
  [^InputStream wrapped]
  (proxy [InputStream] []
    (read
      ([] (.read wrapped))
      ([buf] (.read wrapped buf))
      ([buf off len] (.read wrapped buf off len)))
    (close [] nil))) ; Override close to do nothing

(defn unclosable-output-stream
  "Creates an output stream that ignores close requests."
  [^OutputStream wrapped]
  (proxy [OutputStream] []
    (write
      ([buf] (.write wrapped buf))
      ([buf off len] (.write wrapped buf off len)))
    (flush [] (.flush wrapped))
    (close [] nil))) ; Override close to do nothing

(defrecord StdioTransport [^BufferedReader in ^BufferedWriter out msg-chan
                           running? process encoding]
  core/Transport
    (start! [this]
      (reset! running? true)
      (a/go-loop []
        (when @running?
          (try (when-let [line (.readLine in)] (a/>! msg-chan line))
               (catch Exception e
                 (log/error :msg "Error reading from stdin" :error e)))
          (recur)))
      this)
    (stop! [_this]
      (reset! running? false)
      (a/close! msg-chan)
      (when process
        (try (.destroy process)
             (catch Exception e
               (log/error :msg "Error destroying process" :error e))))
      (try (.flush out)
           (catch Exception e
             (log/error :msg "Error flushing output" :error e))))
    (send! [_this message]
      (try (locking out (.write out message) (.newLine out) (.flush out))
           (catch Exception e
             (log/error :msg "Error writing to stdout"
                        :error e
                        :message message))))
    (receive! [_this] (a/<!! msg-chan)))

(defn validate-encoding!
  "Validates that the specified encoding is supported."
  [encoding]
  (try (Charset/forName encoding)
       true
       (catch Exception e
         (throw (IllegalArgumentException. (str "Unsupported encoding: "
                                                encoding))))))

(defn create-stdio-transport
  "Creates a transport that communicates over stdin/stdout.
   For use with MCP servers that communicate via stdio.

   Options:
   :encoding - The character encoding to use (default: UTF-8)
   :buffer-size - Size of the IO buffers in bytes (default: 8192)"
  [&
   {:keys [encoding buffer-size],
    :or {encoding "UTF-8", buffer-size default-buffer-size}}]
  (validate-encoding! encoding)
  (let [in (-> System/in
               unclosable-input-stream
               (make-buffered-reader :encoding encoding
                                     :buffer-size buffer-size))
        out (-> System/out
                unclosable-output-stream
                (make-buffered-writer :encoding encoding
                                      :buffer-size buffer-size))]
    (map->StdioTransport {:in in,
                          :out out,
                          :msg-chan (a/chan 1024),
                          :running? (atom false),
                          :encoding encoding})))

(defn create-process-transport
  "Creates a transport that communicates with a subprocess via its stdin/stdout.
   For use with MCP clients that need to spawn server processes.

   Options:
   :encoding - The character encoding to use (default: UTF-8)
   :buffer-size - Size of the IO buffers in bytes (default: 8192)
   :command - The command to run
   :args - Command line arguments
   :env - Environment variables map"
  [{:keys [command args env encoding buffer-size],
    :or {encoding "UTF-8", buffer-size default-buffer-size}}]
  (validate-encoding! encoding)
  (let [process-builder (ProcessBuilder. (cons command args))
        _ (when env (.putAll (.environment process-builder) env))
        process (.start process-builder)
        in (-> process
               .getInputStream
               (make-buffered-reader :encoding encoding
                                     :buffer-size buffer-size))
        out (-> process
                .getOutputStream
                (make-buffered-writer :encoding encoding
                                      :buffer-size buffer-size))
        err (-> process
                .getErrorStream
                (make-buffered-reader :encoding encoding
                                      :buffer-size buffer-size))]
    ; Start error stream reader thread
    (a/thread (try (loop []
                     (when-let [line (.readLine err)]
                       (log/warn :msg "Server stderr" :line line)
                       (recur)))
                   (catch Exception e
                     (log/error :msg "Error reading stderr" :error e))))
    (map->StdioTransport {:in in,
                          :out out,
                          :msg-chan (a/chan 1024),
                          :running? (atom false),
                          :process process,
                          :encoding encoding})))

(ns io.modelcontext.clojure-sdk.io-chan
  (:require [babashka.json :as json]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [me.vedang.logger.interface :as log]))

(set! *warn-on-reflection* true)

;;;; IO <-> chan

;; Follow the MCP spec for reading and writing JSON-RPC messages. Convert the
;; messages to and from Clojure hashmaps and shuttle them to core.async
;; channels.

;; https://modelcontextprotocol.io/specification

(defn ^:private read-message
  [^java.io.BufferedReader input]
  (try (let [content (.readLine input)]
         (log/trace :fn :read-message :line content)
         (json/read-str content))
       (catch Exception ex (log/error :fn :read-message :ex ex) :parse-error)))

(defn ^:private kw->camelCaseString
  "Convert keywords to camelCase strings, but preserve capitalization of things
  that are already strings."
  [k]
  (cond-> k (keyword? k) csk/->camelCaseString))

(def ^:private write-lock (Object.))

(defn ^:private write-message
  [^java.io.BufferedWriter output msg]
  (let [content (json/write-str (cske/transform-keys kw->camelCaseString msg))]
    (locking write-lock
      (doto output (.write ^String content) (.newLine) (.flush)))))

(defn input-stream->input-chan
  "Returns a channel which will yield parsed messages that have been read off
  the `input`. When the input is closed, closes the channel. By default when the
  channel closes, will close the input, but can be determined by `close?`.

  Reads in a thread to avoid blocking a go block thread."
  [input]
  (log/trace :fn :input-stream->input-chan :msg "Creating new input-chan")
  (let [messages (async/chan 1)]
    ;; close output when channel closes
    (async/thread
      (with-open [reader (io/reader (io/input-stream input))]
        (loop []
          (let [msg (read-message reader)]
            (cond
              ;; input closed; also close channel
              (= msg :parse-error) (do (log/debug :fn :input-stream->input-chan
                                                  :error true
                                                  :msg "Parse error or EOF")
                                       (async/close! messages))
              :else (do (log/trace :fn :input-stream->input-chan :msg msg)
                        (when (async/>!! messages msg)
                          ;; wait for next message
                          (recur))))))))
    messages))

(defn output-stream->output-chan
  "Returns a channel which expects to have messages put on it. nil values are
  not allowed. Serializes and writes the messages to the output. When the
  channel is closed, closes the output.

  Writes in a thread to avoid blocking a go block thread."
  [output]
  (let [messages (async/chan 1)]
    ;; close output when channel closes
    (async/thread (with-open [writer (io/writer (io/output-stream output))]
                    (loop []
                      (when-let [msg (async/<!! messages)]
                        (log/trace :fn :output-stream->output-chan :msg msg)
                        (try
                          (write-message writer msg)
                          (catch Throwable e (async/close! messages) (throw e)))
                        (recur)))))
    messages))

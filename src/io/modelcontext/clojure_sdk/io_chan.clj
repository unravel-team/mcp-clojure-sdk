(ns io.modelcontext.clojure-sdk.io-chan
  (:require [babashka.json :as json]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [me.vedang.logger.interface :as log])
  (:import (java.io InputStream OutputStream)))

(set! *warn-on-reflection* true)

;;;; IO <-> chan

;; Follow the MCP spec for reading and writing JSON-RPC messages. Convert the
;; messages to and from Clojure hashmaps and shuttle them to core.async
;; channels.

;; https://modelcontextprotocol.io/specification

(defn ^:private read-message
  [^InputStream input]
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
  [^OutputStream output msg]
  (let [content (json/write-str (cske/transform-keys kw->camelCaseString msg))]
    (locking write-lock
      (doto output (.write content) (.newLine content) (.flush)))))

(defn input-stream->input-chan
  "Returns a channel which will yield parsed messages that have been read off
  the `input`. When the input is closed, closes the channel. By default when the
  channel closes, will close the input, but can be determined by `close?`.

  Reads in a thread to avoid blocking a go block thread."
  ([input] (input-stream->input-chan input {}))
  ([input {:keys [close?], :or {close? true}}]
   (log/trace :fn :input-stream->input-chan :msg "Creating new input-chan")
   (let [input (io/input-stream input)
         messages (async/chan 1)]
     (async/thread
       (loop []
         (let [msg (read-message input)]
           (cond
             ;; input closed; also close channel
             (= msg :parse-error) (do (log/debug :fn :input-stream->input-chan
                                                 :error true
                                                 :msg "Parse error or EOF")
                                      (async/close! messages))
             :else (do (log/trace :fn :input-stream->input-chan :msg msg)
                       (if (async/>!! messages msg)
                         ;; wait for next message
                         (recur)
                         ;; messages closed
                         (when close? (.close input))))))))
     messages)))

(defn output-stream->output-chan
  "Returns a channel which expects to have messages put on it. nil values are
  not allowed. Serializes and writes the messages to the output. When the
  channel is closed, closes the output.

  Writes in a thread to avoid blocking a go block thread."
  [output]
  (let [output (io/output-stream output)
        messages (async/chan 1)]
    (async/thread (with-open [writer output] ;; close output when channel
                                             ;; closes
                    (loop []
                      (when-let [msg (async/<!! messages)]
                        (log/trace :fn :output-stream->output-chan :msg msg)
                        (try
                          (write-message writer msg)
                          (catch Throwable e (async/close! messages) (throw e)))
                        (recur)))))
    messages))

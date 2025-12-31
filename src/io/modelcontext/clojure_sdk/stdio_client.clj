(ns io.modelcontext.clojure-sdk.stdio-client
  "STDIO transport implementation for MCP client."
  (:require [clojure.java.io :as io]
            [io.modelcontext.clojure-sdk.client :as client]
            [io.modelcontext.clojure-sdk.io-chan :as mcp.io-chan]
            [me.vedang.logger.interface :as log])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.util.concurrent TimeUnit])
  (:refer-clojure :exclude [run!]))

(set! *warn-on-reflection* true)

;;; ============================================================================
;;; Process Management
;;; ============================================================================

(defn start-server-process!
  "Start an MCP server as a subprocess."
  [command args & {:keys [env cwd inherit-stderr?], :or {inherit-stderr? true}}]
  (log/trace :fn :start-server-process! :command command :args args :cwd cwd)
  (let [cmd-array (into-array String (cons command args))
        pb (ProcessBuilder. ^"[Ljava.lang.String;" cmd-array)]
    (when cwd (.directory pb (io/file cwd)))
    (when env
      (let [pb-env (.environment pb)]
        (doseq [[k v] env] (.put pb-env (name k) (str v)))))
    (if inherit-stderr?
      (.redirectError pb ProcessBuilder$Redirect/INHERIT)
      (.redirectError pb ProcessBuilder$Redirect/DISCARD))
    (.start pb)))

(defn stop-process!
  "Stop a server process gracefully."
  [^Process process & {:keys [timeout-ms], :or {timeout-ms 5000}}]
  (log/trace :fn :stop-process! :timeout-ms timeout-ms)
  (when process
    (.destroy process)
    (when-not (.waitFor process timeout-ms TimeUnit/MILLISECONDS)
      (log/debug :fn :stop-process! :msg "Force killing process")
      (.destroyForcibly process))))

;;; ============================================================================
;;; Transport Connection
;;; ============================================================================

(defn connect-to-process!
  "Connect a client to a server process via STDIO."
  [client ^Process process]
  (log/trace :fn :connect-to-process!)
  (let [input-ch (mcp.io-chan/input-stream->input-chan (.getInputStream
                                                         process))
        output-ch (mcp.io-chan/output-stream->output-chan (.getOutputStream
                                                            process))
        connected-client (-> client
                             (client/connect! input-ch output-ch)
                             (assoc :process process))]
    connected-client))

;;; ============================================================================
;;; High-Level API
;;; ============================================================================

(defn stdio-client
  "Create an MCP client connected to a server via STDIO."
  [client-info command args &
   {:keys [env cwd inherit-stderr? roots on-progress on-log on-resource-updated
           on-resource-list-changed on-tool-list-changed on-prompt-list-changed
           sampling-handler],
    :or {inherit-stderr? true}}]
  (log/trace :fn :stdio-client :client-info client-info :command command)
  (let [client (client/create-client client-info
                                     :roots roots
                                     :on-progress on-progress
                                     :on-log on-log
                                     :on-resource-updated on-resource-updated
                                     :on-resource-list-changed
                                       on-resource-list-changed
                                     :on-tool-list-changed on-tool-list-changed
                                     :on-prompt-list-changed
                                       on-prompt-list-changed
                                     :sampling-handler sampling-handler)
        process (start-server-process! command
                                       args
                                       :env env
                                       :cwd cwd
                                       :inherit-stderr? inherit-stderr?)]
    (connect-to-process! client process)))

(defn run!
  "Create, connect, initialize, and start an MCP client."
  [client-info command args &
   {:keys [timeout-ms], :or {timeout-ms 30000}, :as opts}]
  (log/trace :fn :run! :client-info client-info :command command)
  (try (let [client (apply stdio-client
                      client-info
                      command
                      args
                      (mapcat identity (dissoc opts :timeout-ms)))
             _join (client/start! client)
             init-pending (client/initialize! client)
             init-result
               (client/deref-or-cancel init-pending timeout-ms ::timeout)]
         (cond (= ::timeout init-result)
                 (do (log/error :fn :run! :error :timeout)
                     (client/shutdown! client)
                     {:error {:code -32603,
                              :message "Initialization timed out"}})
               (:error init-result) (do (log/error :fn :run! :error init-result)
                                        (client/shutdown! client)
                                        {:error (:error init-result)})
               :else (do (client/process-initialize-result! client init-result)
                         (client/initialized! client)
                         (log/info :fn :run!
                                   :msg "Client initialized successfully"
                                   :server-info (:serverInfo init-result))
                         {:client client})))
       (catch Exception e
         (log/error :fn :run! :exception e)
         {:error {:code -32603,
                  :message (.getMessage e),
                  :data {:exception-class (.getName (class e))}}})))

;;; ============================================================================
;;; Cleanup
;;; ============================================================================

(defn shutdown!
  "Shutdown the STDIO client."
  [client]
  (log/trace :fn :shutdown!)
  (let [result (client/shutdown! client)]
    (stop-process! (:process client))
    result))

;;; ============================================================================
;;; Utility Macros
;;; ============================================================================

(defmacro with-client
  "Execute body with an initialized MCP client, ensuring cleanup."
  [[binding run-expr] & body]
  `(let [result# ~run-expr
         ~binding result#]
     (try ~@body
          (finally (when-let [client# (:client result#)]
                     (shutdown! client#))))))

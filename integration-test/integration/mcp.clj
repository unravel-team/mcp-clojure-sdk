(ns integration.mcp
  (:require [babashka.process :as p]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.test :refer [use-fixtures]]
            [integration.client :as client]))

(def ^:dynamic *mock-mcp-process* nil)
(def ^:dynamic *mock-client* nil)

(defn- resolve-command
  [command]
  (let [file (io/file command)]
    (if (.exists file) (.getCanonicalPath file) command)))

(defn- command-from-cli
  []
  (when (empty? *command-line-args*)
    (throw (ex-info "Missing server command" {})))
  {:command (first *command-line-args*)
   :args (vec (rest *command-line-args*))})

(defn start-server
  ([command] (start-server command []))
  ([command args]
   (p/process (into [(resolve-command command)] args)
              {:dir "integration-test/examples/"})))

(defn start-process!
  ([] (let [{:keys [command args]} (command-from-cli)]
        (start-process! command args)))
  ([command args]
   (let [server (start-server command args)
        client (client/client (:in server) (:out server))]
    (client/start client nil)
    (async/go-loop []
      (when-let [log (async/<! (:log-ch client))]
        (println log)
        (recur)))
    (alter-var-root #'*mock-mcp-process* (constantly server))
    (alter-var-root #'*mock-client* (constantly client)))))

(defn cli!
  ([] (let [{:keys [command args]} (command-from-cli)]
        (cli! command args)))
  ([command args]
   (let [server (start-server command args)]
     (alter-var-root #'*mock-mcp-process* (constantly server))
     (io/reader (:out server)))))

(defn clean!
  []
  (flush)
  (some-> *mock-client*
          client/shutdown)
  (some-> *mock-mcp-process*
          deref) ;; wait for shutdown of client to shutdown server
  (alter-var-root #'*mock-mcp-process* (constantly nil))
  (alter-var-root #'*mock-client* (constantly nil)))

(defn clean-after-test
  []
  (use-fixtures :each (fn [f] (clean!) (f)))
  (use-fixtures :once (fn [f] (f) (clean!))))

(defn notify!
  [[method body]]
  (client/send-notification *mock-client* method body))

(defn request!
  [[method body]]
  (client/request-and-await-server-response! *mock-client* method body))

(defn client-awaits-server-notification
  [method]
  (client/await-server-notification *mock-client* method))

(defn client-awaits-server-request
  [method]
  (client/await-server-request *mock-client* method))

(defn mock-response
  [method resp]
  (client/mock-response *mock-client* method resp))

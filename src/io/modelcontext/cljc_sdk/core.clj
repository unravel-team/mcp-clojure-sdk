(ns io.modelcontext.cljc-sdk.core
  (:require [clojure.core.async :as a]
            [io.modelcontext.cljc-sdk.specs :as specs]
            [babashka.json :as json]
            [me.vedang.logger.interface :as log]))

(defprotocol Transport
  (start! [this]
    "Start the transport")
  (stop! [this]
    "Stop the transport")
  (send! [this message]
    "Send a message")
  (receive! [this]
    "Receive a message"))

(defprotocol Protocol
  (request! [this method params]
    "Send a request and await response")
  (notify! [this method params]
    "Send a notification")
  (handle-request! [this method handler]
    "Register a request handler")
  (handle-notification! [this method handler]
    "Register a notification handler"))

(defn- generate-request-id [] (str (random-uuid)))

(defn create-request
  [method params]
  {:jsonrpc specs/jsonrpc-version,
   :id (generate-request-id),
   :method method,
   :params params})

(defn create-notification
  [method params]
  {:jsonrpc specs/jsonrpc-version, :method method, :params params})

(defn create-response
  [id result]
  {:jsonrpc specs/jsonrpc-version, :id id, :result result})

(defn create-error
  [id code message & [data]]
  {:jsonrpc specs/jsonrpc-version,
   :id id,
   :error {:code code, :message message, :data data}})

(defn- encode-message [msg] (json/write-str msg))

(defn- decode-message [msg] (json/read-str msg))

(defrecord JsonRpcProtocol [transport request-handlers notification-handlers
                            pending-requests]
  Protocol
    (request! [_this method params]
      (let [req (create-request method params)
            response-ch (a/promise-chan)]
        (when-not (specs/valid-request? req)
          (throw (ex-info "Invalid request"
                          {:explain (specs/explain-request req)})))
        (swap! pending-requests assoc (:id req) response-ch)
        (send! transport (encode-message req))
        (a/<!! response-ch)))
    (notify! [_this method params]
      (let [notif (create-notification method params)]
        (when-not (specs/valid-notification? notif)
          (throw (ex-info "Invalid notification"
                          {:explain (specs/explain-notification notif)})))
        (send! transport (encode-message notif))))
    (handle-request! [_this method handler]
      (swap! request-handlers assoc method handler))
    (handle-notification! [_this method handler]
      (swap! notification-handlers assoc method handler)))

(defn- handle-request
  [protocol decoded handler]
  (try (let [result (handler (:params decoded))
             response (create-response (:id decoded) result)]
         (send! (:transport protocol) (encode-message response)))
       (catch clojure.lang.ExceptionInfo e
         (let [data (ex-data e)
               error-resp (create-error (:id decoded)
                                        (:code data specs/internal-error)
                                        (.getMessage e)
                                        (:data data))]
           (send! (:transport protocol) (encode-message error-resp))))
       (catch Exception e
         (log/error :msg "Error handling request" :error e)
         (let [error-resp (create-error (:id decoded)
                                        specs/internal-error
                                        (str "Internal error: " (.getMessage e))
                                        {:type "internal.error"})]
           (send! (:transport protocol) (encode-message error-resp))))))

(defn- handle-notification
  [protocol decoded handler]
  (try (handler (:params decoded))
       (catch Exception e
         (log/error :msg "Error handling notification" :error e))))

(defn- handle-response
  [protocol decoded]
  (when-let [response-ch (get @(:pending-requests protocol) (:id decoded))]
    (if (:error decoded)
      (a/>!! response-ch (ex-info "RPC Error" (:error decoded)))
      (a/>!! response-ch (:result decoded)))
    (swap! (:pending-requests protocol) dissoc (:id decoded))))

(defn- handle-method-not-found
  [protocol decoded]
  (let [error-resp (create-error (:id decoded)
                                 specs/method-not-found
                                 "Method not found")]
    (send! (:transport protocol) (encode-message error-resp))))

(defn- handle-incoming-message
  [protocol msg]
  (let [decoded (decode-message msg)]
    (cond
      ;; Handle requests
      (and (:method decoded) (:id decoded))
        (if-let [handler (get @(:request-handlers protocol) (:method decoded))]
          (handle-request protocol decoded handler)
          (handle-method-not-found protocol decoded))
      ;; Handle notifications
      (:method decoded) (when-let [handler (get @(:notification-handlers
                                                   protocol)
                                                (:method decoded))]
                          (handle-notification protocol decoded handler))
      ;; Handle responses
      (:id decoded) (handle-response protocol decoded)
      :else (log/warn :msg "Received invalid message" :message decoded))))

(defn create-protocol
  [transport]
  (let [protocol (->JsonRpcProtocol transport (atom {}) (atom {}) (atom {}))]
    ;; Start message handling loop
    (a/go-loop []
      (when-let [msg (receive! transport)]
        (handle-incoming-message protocol msg)
        (recur)))
    protocol))

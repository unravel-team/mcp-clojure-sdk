(ns io.modelcontext.clojure-sdk.client-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [io.modelcontext.clojure-sdk.client :as client]
            [io.modelcontext.clojure-sdk.server :as server]
            [io.modelcontext.clojure-sdk.test-helper :as h]
            [jsonrpc4clj.requests :as jsonrpc.requests]
            [jsonrpc4clj.server :as jsonrpc.server]))

(def tool-echo
  {:name "echo",
   :description "Echo input",
   :inputSchema {:type "object",
                 :properties {"message" {:type "string"}},
                 :required ["message"]},
   :handler (fn [{:keys [message]}] {:type "text", :text message})})

(defn- await-result [pending] (client/deref-or-cancel pending 2000 ::timeout))

(defn- connect-client-server
  [server-spec client-opts]
  (let [server (server/chan-server)
        context (server/create-context! server-spec)
        _server-join (server/start! server context)
        client (-> (apply client/create-client
                          {:name "test-client", :version "1.0.0"}
                          (mapcat identity client-opts))
                   (client/connect! (:output-ch server) (:input-ch server)))
        _client-join (client/start! client)]
    {:server server, :client client}))

(defn- initialize-client!
  [client]
  (let [result (await-result (client/initialize! client))]
    (when-not (= ::timeout result)
      (client/process-initialize-result! client result)
      (client/initialized! client))
    result))

(defn- shutdown!
  [{:keys [client server]}]
  (when client (client/shutdown! client))
  (when server (jsonrpc.server/shutdown server)))

(deftest client-initialize-and-list-tools
  (testing "Client initialization and tool listing"
    (let [server-spec {:name "test-server",
                       :version "1.0.0",
                       :tools [tool-echo],
                       :prompts [],
                       :resources []}
          connection (connect-client-server server-spec {})]
      (try (let [init-result (initialize-client! (:client connection))]
             (is (not= ::timeout init-result))
             (is (= "test-server" (get-in init-result [:serverInfo :name])))
             (is (= "test-server"
                    (get-in (client/server-info (:client connection)) [:name])))
             (is (true? (client/initialized? (:client connection)))))
           (let [tools-result (await-result (client/list-tools! (:client
                                                                  connection)))]
             (is (not= ::timeout tools-result))
             (is (= ["echo"] (mapv :name (:tools tools-result)))))
           (finally (shutdown! connection))))))

(deftest client-responds-to-roots-list
  (testing "Server-initiated roots/list request"
    (let [server-spec {:name "test-server",
                       :version "1.0.0",
                       :tools [],
                       :prompts [],
                       :resources []}
          roots [{:uri "file:///tmp", :name "Temp"}]
          connection (connect-client-server server-spec {:roots roots})]
      (try (let [init-result (initialize-client! (:client connection))]
             (is (not= ::timeout init-result)))
           (let [pending (jsonrpc.server/send-request (:server connection)
                                                      "roots/list"
                                                      {})
                 result (await-result pending)]
             (is (not= ::timeout result))
             (is (= roots (:roots result))))
           (finally (shutdown! connection))))))

(deftest client-responds-to-sampling-request
  (testing "Server-initiated sampling request"
    (let [server-spec {:name "test-server",
                       :version "1.0.0",
                       :tools [],
                       :prompts [],
                       :resources []}
          sampling-handler (fn [_params] {:model "test-model"})
          connection (connect-client-server server-spec
                                            {:sampling-handler
                                             sampling-handler})]
      (try (let [init-result (initialize-client! (:client connection))]
             (is (not= ::timeout init-result)))
           (let [params {:messages [{:role "user",
                                     :content {:type "text", :text "Hi"}}],
                         :maxTokens 10}
                 pending (jsonrpc.server/send-request (:server connection)
                                                      "sampling/createMessage"
                                                      params)
                 result (await-result pending)]
             (is (not= ::timeout result))
             (is (= "test-model" (:model result))))
           (finally (shutdown! connection))))))

;;; Client -> Server notifications: progress and roots management.

(defn- raw-connected-client
  "Connect a client to raw channels so tests can observe its outbound
  messages directly. Returns {:client ... :out ...}."
  [client-opts]
  (let [in (async/chan 3)
        out (async/chan 3)
        client (-> (apply client/create-client
                          {:name "test-client", :version "1.0.0"}
                          (mapcat identity client-opts))
                   (client/connect! in out))]
    (client/start! client)
    {:client client, :out out, :in in}))

(deftest client-sends-progress-notifications
  (testing "notify-progress! sends a progress notification"
    (let [{:keys [client out]} (raw-connected-client {})]
      (try (client/notify-progress! client "tok-1" 25 {:total 50, :message "Q"})
           (is (= (jsonrpc.requests/notification "notifications/progress"
                                                 {:progressToken "tok-1",
                                                  :progress 25,
                                                  :total 50,
                                                  :message "Q"})
                  (h/assert-take out)))
           (finally (client/shutdown! client))))))

(deftest client-roots-management
  (testing "add-root! updates state and notifies the server"
    (let [{:keys [client out]} (raw-connected-client {:roots [{:uri "file:///a",
                                                               :name "A"}]})
          new-root {:uri "file:///b", :name "B"}]
      (try (client/add-root! client new-root)
           (is (= [{:uri "file:///a", :name "A"} new-root]
                  (:roots @(:state client))))
           (is (= (jsonrpc.requests/notification
                    "notifications/roots/list_changed"
                    {})
                  (h/assert-take out)))
           (testing "remove-root! removes by uri and notifies"
             (client/remove-root! client "file:///a")
             (is (= [new-root] (:roots @(:state client))))
             (is (= (jsonrpc.requests/notification
                      "notifications/roots/list_changed"
                      {})
                    (h/assert-take out))))
           (finally (client/shutdown! client))))))

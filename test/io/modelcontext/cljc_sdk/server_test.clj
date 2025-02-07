(ns io.modelcontext.cljc-sdk.server-test
  (:require [clojure.test :refer [deftest testing is]]
            [io.modelcontext.cljc-sdk.server :as server]
            [io.modelcontext.cljc-sdk.transport.stdio :as stdio]))

;;; // Rewrite all the tests in this file to work with the new
;;; server/make-server
;;; // function ai!
(deftest server-basic-functionality
  (testing "Server creation and tool registration"
    (let [server (server/defserver
                   "test-server"
                   "1.0.0"
                   (server/register-tool!
                     "greet"
                     "Greet someone"
                     {:type "object", :properties {"name" {:type "string"}}}
                     (fn [{:keys [name]}]
                       {:type "text", :text (str "Hello, " name "!")})))]
      (is (some? server))
      (testing "Tool listing"
        (let [tools (-> @(:tools server)
                        vals
                        first
                        :tool)]
          (is (= "greet" (:name tools)))
          (is (= "Greet someone" (:description tools)))))
      (testing "Tool execution"
        (let [handler (-> @(:tools server)
                          (get "greet")
                          :handler)
              result (handler {:name "World"})]
          (is (= {:type "text", :text "Hello, World!"} result)))))))

(deftest server-full-protocol
  (testing "Complete server protocol interaction"
    (let [transport (stdio/create-stdio-transport)
          server (server/defserver
                   "test-server"
                   "1.0.0"
                   (server/register-tool!
                     "greet"
                     "Greet someone"
                     {:type "object", :properties {"name" {:type "string"}}}
                     (fn [{:keys [name]}]
                       {:type "text", :text (str "Hello, " name "!")})))]
      (try (server/start! server transport)
           ;; Here we'd typically simulate client requests through the
           ;; transport but we'll keep this simple for now
           (finally (server/stop! server))))))

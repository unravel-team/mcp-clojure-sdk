(ns io.modelcontext.clojure-sdk.integration-support-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [io.modelcontext.clojure-sdk.specs :as specs]))

(load-file "integration-test/integration/helper.clj")
(load-file "integration-test/integration/fixture.clj")

(require '[integration.fixture :as fixture])
(require '[integration.helper :as helper])

(deftest helper-file-uri-roundtrip
  (let [file (io/file "deps.edn")
        uri (helper/file->uri file)
        roundtrip (helper/uri->file uri)]
    (is (.exists roundtrip))
    (is (= (.getCanonicalPath file) (.getCanonicalPath roundtrip)))))

(deftest fixture-initialize-request
  (let [[method params] (fixture/initialize-request)]
    (is (= "initialize" method))
    (is (= (first specs/supported-protocol-versions) (:protocolVersion params)))
    (is (= "mcp-clojure-sdk-integration-test"
           (get-in params [:clientInfo :name])))))

(deftest fixture-tool-request
  (let [[method params] (fixture/call-tool-request "echo" {:message "hi"})]
    (is (= "tools/call" method))
    (is (= "echo" (:name params)))
    (is (= {:message "hi"} (:arguments params)))))

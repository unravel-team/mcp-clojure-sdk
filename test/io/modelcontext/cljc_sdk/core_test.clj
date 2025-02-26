(ns io.modelcontext.cljc-sdk.core-test
  (:require [io.modelcontext.cljc-sdk.core :as core]
            [io.modelcontext.cljc-sdk.specs :as specs]
            [clojure.test :refer [deftest testing is]]))

(deftest test-create-request
  (testing "create-request generates valid requests"
    (let [method "test/method"
          params {:foo "bar"}
          request (core/create-request method params)]
      (is (= specs/jsonrpc-version (:jsonrpc request)))
      (is (string? (:id request)))
      (is (= method (:method request)))
      (is (= params (:params request)))
      (is (specs/valid-request? request)))))

(ns io.modelcontext.cljc-sdk.core-test
  (:require [io.modelcontext.cljc-sdk.core :as core]
            [io.modelcontext.cljc-sdk.specs :as specs]
            [clojure.test :refer [deftest testing is]]))

(deftest test-create-request
  (testing "create-request generates valid requests"
    (let [method "test/method"
          params1 {"foo" "bar"}
          params2 {:foo "bar"}
          request1 (core/create-request method params1)
          request2 (core/create-request method params2)]
      (is (= specs/jsonrpc-version (:jsonrpc request1)))
      (is (string? (:id request1)))
      (is (= method (:method request1)))
      (is (= params1 (:params request1)))
      (is (specs/valid-request? request1))
      (is (= params1 (:params request2)))
      (is (specs/valid-request? request2)))))

(ns io.modelcontext.cljc-sdk.core-test
  (:require [io.modelcontext.cljc-sdk.core :as core]
            [io.modelcontext.cljc-sdk.specs :as specs]
            [clojure.test :refer [deftest testing is]]))

(deftest test-stringify-keys
  (testing "stringify-keys converts keyword keys to strings"
    (let [input {:a 1, :b {:c 2}}
          expected {"a" 1, "b" {"c" 2}}
          result (#'io.modelcontext.cljc-sdk.core/stringify-keys input)]
      (is (= expected result))))
  (testing "stringify-keys handles non-map input"
    (let [input "not-a-map"
          result (#'io.modelcontext.cljc-sdk.core/stringify-keys input)]
      (is (= input result)))))

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

;; // look at test-create-request and create similar functions for
;; // create-notification and create-result ai!

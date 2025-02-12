(ns io.modelcontext.cljc-sdk.specs-test
  (:require [clojure.test :refer [deftest testing is]]
            [io.modelcontext.cljc-sdk.specs :as specs]))

(deftest test-tool-validation
  (testing "Valid tool definitions"
    (let [simple-tool {:name "greet", :inputSchema {:type "object"}}]
      (is (specs/valid-tool? simple-tool)))
    (let [full-tool {:name "add-numbers",
                     :description "Add two numbers together",
                     :inputSchema
                     {:type "object",
                      :properties
                      {"a" {:type "number", :description "First number"},
                       "b" {:type "number", :description "Second number"}},
                      :required ["a" "b"]}}]
      (is (specs/valid-tool? full-tool))))
  (testing "Invalid tool definitions"
    (testing "Missing required fields"
      (let [no-name {:input-schema {:type "object"}}]
        (is (not (specs/valid-tool? no-name))))
      (let [no-schema {:name "test"}] (is (not (specs/valid-tool? no-schema)))))
    (testing "Invalid schema type"
      (let [invalid-type {:name "test", :inputSchema {:type "invalid"}}]
        (is (not (specs/valid-tool? invalid-type)))))
    (testing "Invalid property types"
      (let [invalid-prop {:name "test",
                          :inputSchema {:type "object",
                                         :properties {"test" {:type
                                                              "invalid"}}}}]
        (is (not (specs/valid-tool? invalid-prop)))))))

(deftest test-resource-validation
  (testing "Valid resource definitions"
    (let [simple-resource {:uri "file:///test.txt", :name "Test File"}]
      (is (specs/valid-resource? simple-resource)))
    (let [full-resource {:uri "https://example.com/data.json",
                         :name "Data",
                         :description "Example data file",
                         :mimeType "application/json"}]
      (is (specs/valid-resource? full-resource))))
  (testing "Invalid resource definitions"
    (testing "Missing required fields"
      (let [no-uri {:name "Test"}] (is (not (specs/valid-resource? no-uri))))
      (let [no-name {:uri "file:///test.txt"}]
        (is (not (specs/valid-resource? no-name)))))
    (testing "Invalid URI schemes"
      (let [invalid-scheme {:uri "invalid:///test.txt", :name "Test"}]
        (is (not (specs/valid-resource? invalid-scheme))))
      (let [not-uri {:uri "not a uri", :name "Test"}]
        (is (not (specs/valid-resource? not-uri)))))))

(deftest test-prompt-validation
  (testing "Valid prompt definitions"
    (let [simple-prompt {:name "greet"}]
      (is (specs/valid-prompt? simple-prompt)))
    (let [full-prompt {:name "analyze-code",
                       :description "Analyze code for improvements",
                       :arguments [{:name "language",
                                    :description "Programming language",
                                    :required true}
                                   {:name "code",
                                    :description "Code to analyze",
                                    :required true}]}]
      (is (specs/valid-prompt? full-prompt))))
  (testing "Invalid prompt definitions"
    (testing "Missing required fields"
      (let [no-name {}] (is (not (specs/valid-prompt? no-name)))))
    (testing "Invalid argument structure"
      (let [invalid-args {:name "test",
                          :arguments [{:description "Missing name"}]}]
        (is (not (specs/valid-prompt? invalid-args))))
      (let [invalid-arg-type {:name "test", :arguments "not-a-list"}]
        (is (not (specs/valid-prompt? invalid-arg-type)))))))

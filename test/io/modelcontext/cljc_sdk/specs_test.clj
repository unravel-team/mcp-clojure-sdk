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
                          :inputSchema {:type "not-object",
                                        :properties {"test" "anything"}}}]
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
        (is (not (specs/valid-resource? no-name)))))))

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

(deftest test-message-content-validation
  (testing "Valid message contents"
    (let [text-content {:type "text", :text "Hello world"}]
      (is (specs/valid-text-content? text-content)))
    (let [image-content
            {:type "image", :data "base64data...", :mimeType "image/png"}]
      (is (specs/valid-image-content? image-content)))
    (let [audio-content
            {:type "audio", :data "base64data...", :mimeType "audio/mp3"}]
      (is (specs/valid-audio-content? audio-content))))
  (testing "Invalid message contents"
    (testing "Missing required fields"
      (is (not (specs/valid-text-content? {:type "text"})))
      (is (not (specs/valid-image-content? {:type "image", :data "test"})))
      (is (not (specs/valid-audio-content? {:type "audio",
                                            :mimeType "audio/mp3"}))))))

(deftest test-sampling-validation
  (testing "Valid sampling messages"
    (let [message {:role "assistant",
                   :content {:type "text", :text "any text is fine"}}]
      (is (specs/valid-sampling-message? message))))
  (testing "Valid model preferences"
    (let [preferences {:hints [{:name "claude-3"}],
                       :costPriority 0.8,
                       :speedPriority 0.5,
                       :intelligencePriority 0.9}]
      (is (specs/valid-model-preferences? preferences)))))

(deftest test-root-validation
  (testing "Valid root definitions"
    (let [simple-root {:uri "file:///workspace"}]
      (is (specs/valid-root? simple-root)))
    (let [named-root {:uri "file:///projects", :name "Projects Directory"}]
      (is (specs/valid-root? named-root))))
  (testing "Invalid root definitions"
    (testing "Missing required fields"
      (let [no-uri {:name "Invalid Root"}]
        (is (not (specs/valid-root? no-uri)))))))

(deftest test-implementation-validation
  (testing "Valid implementation info"
    (let [impl {:name "test-client", :version "1.0.0"}]
      (is (specs/valid-implementation? impl))))
  (testing "Invalid implementation info"
    (testing "Missing required fields"
      (is (not (specs/valid-implementation? {:name "test-only"})))
      (is (not (specs/valid-implementation? {:version "1.0.0"}))))))

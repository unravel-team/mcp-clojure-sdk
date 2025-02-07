(ns io.modelcontext.cljc-sdk.specs-gen-test
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.spec.alpha :as s]
            [io.modelcontext.cljc-sdk.specs :as specs]))

;; Custom generators
(def gen-uri
  (gen/fmap #(str (gen/generate (gen/elements ["file" "http" "https"
                                               "resource"]))
                  "://"
                  %)
            (gen/not-empty gen/string-alphanumeric)))

(def gen-property-type
  (gen/elements ["string" "number" "boolean" "array" "object"]))

(def gen-property
  (gen/hash-map :type gen-property-type
                :description (gen/frequency [[9 gen/string-alphanumeric]
                                             [1 (gen/return nil)]])))

(def gen-properties (gen/map gen/string-alphanumeric gen-property))

(def gen-input-schema
  (gen/hash-map :type (gen/return "object")
                :properties (gen/frequency [[9 gen-properties]
                                            [1 (gen/return nil)]])
                :required (gen/vector gen/string-alphanumeric)))

;; Tool property tests
(defspec tool-validity
         100
         (prop/for-all [name gen/string-alphanumeric description
                        (gen/frequency [[9 gen/string-alphanumeric]
                                        [1 (gen/return nil)]]) input-schema
                        gen-input-schema]
                       (let [tool {:name name,
                                   :description description,
                                   :input-schema input-schema}]
                         (s/valid? ::specs/tool tool))))

;; Resource property tests
(defspec resource-validity
         100
         (prop/for-all [uri gen-uri name gen/string-alphanumeric description
                        (gen/frequency [[9 gen/string-alphanumeric]
                                        [1 (gen/return nil)]]) mime-type
                        (gen/frequency [[9 gen/string-alphanumeric]
                                        [1 (gen/return nil)]])]
                       (let [resource {:uri uri,
                                       :name name,
                                       :description description,
                                       :mime-type mime-type}]
                         (s/valid? ::specs/resource resource))))

;; Prompt property tests
(def gen-argument
  (gen/hash-map :name gen/string-alphanumeric
                :description (gen/frequency [[9 gen/string-alphanumeric]
                                             [1 (gen/return nil)]])
                :required (gen/frequency [[9 gen/boolean]
                                          [1 (gen/return nil)]])))

(def gen-content
  (gen/hash-map :content-type (gen/elements ["text" "image" "resource"])
                :text (gen/frequency [[9 gen/string-alphanumeric]
                                      [1 (gen/return nil)]])))

(def gen-message
  (gen/hash-map :role (gen/elements ["user" "assistant"]) :content gen-content))

(defspec prompt-validity
         100
         (prop/for-all
           [prompt-name gen/string-alphanumeric description
            (gen/frequency [[9 gen/string-alphanumeric] [1 (gen/return nil)]])
            arguments (gen/vector gen-argument)]
           (let [prompt (merge {:name prompt-name, :arguments arguments}
                               (when description {:description description}))]
             (s/valid? ::specs/prompt prompt))))

;; Mutation tests - verify that invalid data is rejected
(defspec invalid-tool-rejection
         100
         (prop/for-all [tool
                        (gen/hash-map :name (gen/one-of [gen/small-integer
                                                         gen/boolean gen/ratio])
                                      :input-schema
                                        (gen/hash-map :type gen/small-integer))]
                       (not (s/valid? ::specs/tool tool))))

(defspec invalid-resource-rejection
         100
         (prop/for-all
           [resource
            (gen/hash-map
              :uri (gen/one-of [gen/small-integer gen/boolean gen/ratio])
              :name (gen/one-of [gen/small-integer gen/boolean gen/ratio]))]
           (not (s/valid? ::specs/resource resource))))

(defspec invalid-prompt-rejection
         100
         (prop/for-all
           [prompt
            (gen/hash-map
              :name (gen/one-of [gen/small-integer gen/boolean gen/ratio])
              :arguments (gen/one-of [gen/small-integer gen/string gen/ratio]))]
           (not (s/valid? ::specs/prompt prompt))))

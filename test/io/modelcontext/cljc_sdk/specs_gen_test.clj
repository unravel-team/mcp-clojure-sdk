(ns io.modelcontext.cljc-sdk.specs-gen-test
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
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

(def gen-property-with-description
  (gen/hash-map :type gen-property-type :description gen/string-alphanumeric))

(def gen-property-without-description (gen/hash-map :type gen-property-type))

(def gen-property
  (gen/frequency [[9 gen-property-with-description]
                  [1 gen-property-without-description]]))

(def gen-properties (gen/map gen/string-alphanumeric gen-property))

(def gen-input-schema-with-properties
  (gen/hash-map :type (gen/return "object")
                :properties gen-properties
                :required (gen/vector gen/string-alphanumeric)))

(def gen-input-schema-without-properties
  (gen/hash-map :type (gen/return "object")
                :required (gen/vector gen/string-alphanumeric)))

(def gen-input-schema
  (gen/frequency [[9 gen-input-schema-with-properties]
                  [1 gen-input-schema-without-properties]]))


;; Tool property tests
(def gen-tool-with-description
  (gen/hash-map :name gen/string-alphanumeric
                :description gen/string-alphanumeric
                :input-schema gen-input-schema))

(def gen-tool-without-description
  (gen/hash-map :name gen/string-alphanumeric :input-schema gen-input-schema))

(def gen-tool
  (gen/frequency [[9 gen-tool-with-description]
                  [1 gen-tool-without-description]]))

(declare tool-validity ;; [ref: clj_kondo_needs_forward_declaration]
         resource-validity
         prompt-validity
         invalid-tool-rejection
         invalid-resource-rejection
         invalid-prompt-rejection)

(defspec tool-validity
         100
         (prop/for-all [tool gen-tool] (specs/valid-tool? tool)))

;; Resource property tests
(def gen-resource-with-description-and-mime
  (gen/hash-map :uri gen-uri
                :name gen/string-alphanumeric
                :description gen/string-alphanumeric
                :mime-type gen/string-alphanumeric))

(def gen-resource-with-only-description
  (gen/hash-map :uri gen-uri
                :name gen/string-alphanumeric
                :description gen/string-alphanumeric))

(def gen-resource-with-only-mime
  (gen/hash-map :uri gen-uri
                :name gen/string-alphanumeric
                :mime-type gen/string-alphanumeric))

(def gen-resource-basic
  (gen/hash-map :uri gen-uri :name gen/string-alphanumeric))

(def gen-resource
  (gen/frequency [[5 gen-resource-with-description-and-mime]
                  [2 gen-resource-with-only-description]
                  [2 gen-resource-with-only-mime] [1 gen-resource-basic]]))

(defspec resource-validity
         100
         (prop/for-all [resource gen-resource]
                       (specs/valid-resource? resource)))

;; Prompt property tests
(def gen-argument
  (gen/hash-map :name gen/string-alphanumeric
                :description gen/string-alphanumeric
                :required gen/boolean))

(def gen-argument-without-description
  (gen/hash-map :name gen/string-alphanumeric :required gen/boolean))

(def gen-argument-without-required
  (gen/hash-map :name gen/string-alphanumeric
                :description gen/string-alphanumeric))

(def gen-argument-basic (gen/hash-map :name gen/string-alphanumeric))

(def gen-arguments
  (gen/vector (gen/frequency
                [[5 gen-argument] [2 gen-argument-without-description]
                 [2 gen-argument-without-required] [1 gen-argument-basic]])))

(def gen-prompt-with-description
  (gen/hash-map :name gen/string-alphanumeric
                :description gen/string-alphanumeric
                :arguments gen-arguments))

(def gen-prompt-basic
  (gen/hash-map :name gen/string-alphanumeric :arguments gen-arguments))

(def gen-prompt
  (gen/frequency [[4 gen-prompt-with-description] [1 gen-prompt-basic]]))

(defspec prompt-validity
         100
         (prop/for-all [prompt gen-prompt] (specs/valid-prompt? prompt)))

;; Mutation tests - verify that invalid data is rejected
(defspec invalid-tool-rejection
         100
         (prop/for-all [tool
                        (gen/hash-map :name (gen/one-of [gen/small-integer
                                                         gen/boolean gen/ratio])
                                      :input-schema
                                        (gen/hash-map :type gen/small-integer))]
                       (not (specs/valid-tool? tool))))

(defspec invalid-resource-rejection
         100
         (prop/for-all
           [resource
            (gen/hash-map
              :uri (gen/one-of [gen/small-integer gen/boolean gen/ratio])
              :name (gen/one-of [gen/small-integer gen/boolean gen/ratio]))]
           (not (specs/valid-resource? resource))))

(defspec invalid-prompt-rejection
         100
         (prop/for-all
           [prompt
            (gen/hash-map
              :name (gen/one-of [gen/small-integer gen/boolean gen/ratio])
              :arguments (gen/one-of [gen/small-integer gen/string gen/ratio]))]
           (not (specs/valid-prompt? prompt))))

;;; [tag: clj_kondo_needs_forward_declaration]
;;;
;;; Macros like `defspec` create functions with the name we pass as the first
;;; argument. Since these variables are created in the namespace, we need to
;;; tell clj-kondo about them, otherwise it gets confused and throws Unresolved
;;; Symbol
;;;
;;; From:
;;; https://stackoverflow.com/questions/61727582/how-to-avoid-unresolved-symbol-with-clj-kond-when-using-hugsql-def-db-fns-macro

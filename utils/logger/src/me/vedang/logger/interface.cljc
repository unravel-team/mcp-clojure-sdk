(ns me.vedang.logger.interface
  (:require [babashka.json :as json]
            #?(:bb [taoensso.timbre :as log]
               :clj [io.pedestal.log :as log])))
;; [ref: babashka_reader_conditionals]
;; [ref: reader_conditionals]
(defmacro trace
  [& keyvals]
  `(log/trace ::log/formatter json/write-str ~@keyvals))

(defmacro debug
  [& keyvals]
  `(log/debug ::log/formatter json/write-str ~@keyvals))

(defmacro info [& keyvals] `(log/info ::log/formatter json/write-str ~@keyvals))

(defmacro warn [& keyvals] `(log/warn ::log/formatter json/write-str ~@keyvals))

(defmacro error
  [& keyvals]
  `(log/error ::log/formatter json/write-str ~@keyvals))

(defmacro spy
  "Logs expr and its value at DEBUG level, returns value."
  [expr]
  (let [value' (gensym "value")
        expr' (gensym "expr")]
    `(let [~value' ~expr
           ~expr' ~(list 'quote expr)]
       (log/debug :spy ~expr' :returns ~value' ::log/formatter json/write-str)
       ~value')))

(defmacro with-context
  [ctx-map & body]
  ;; [ref: babashka_reader_conditionals]
  #?(:bb `(do ~@body)
     :clj `(log/with-context (assoc ~ctx-map ::log/formatter json/write-str)
             ~@body)))

;;; [tag: babashka_reader_conditionals]
;;;
;;; From: https://book.babashka.org/#_reader_conditionals
;;;
;;; Babashka supports reader conditionals by taking either the :bb or :clj
;;; branch, **whichever comes first**. NOTE: the :clj branch behavior was added
;;; in version 0.0.71, before that version the :clj branch was ignored.
;;;
;;; Remember this when defining reader conditional branches.

;;; [tag: reader_conditionals]
;;;
;;; An important point to keep in mind: Reader Conditionals only work in .cljc
;;; files
;;;
;;; From: https://clojure.org/guides/reader_conditionals
;;;
;;; Reader conditionals are integrated into the Clojure reader, and don’t
;;; require any extra tooling. To use reader conditionals, all you need is for
;;; your file to have a .cljc extension.

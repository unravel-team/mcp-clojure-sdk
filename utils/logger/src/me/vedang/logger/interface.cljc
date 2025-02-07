(ns me.vedang.logger.interface
  (:require [babashka.json :as json]
            #?(:clj [io.pedestal.log :as log]
               :bb [taoensso.timbre :as log])))

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
  #?(:clj `(log/with-context (assoc ~ctx-map ::log/formatter json/write-str)
             ~@body)
     :bb `(do ~@body)))

(ns me.vedang.logger.interface
  (:require [io.pedestal.log :as log]
            [jsonista.core :as json]))

(defmacro trace
  [& keyvals]
  `(log/trace ::log/formatter json/write-value-as-string ~@keyvals))

(defmacro debug
  [& keyvals]
  `(log/debug ::log/formatter json/write-value-as-string ~@keyvals))

(defmacro info
  [& keyvals]
  `(log/info ::log/formatter json/write-value-as-string ~@keyvals))

(defmacro warn
  [& keyvals]
  `(log/warn ::log/formatter json/write-value-as-string ~@keyvals))

(defmacro error
  [& keyvals]
  `(log/error ::log/formatter json/write-value-as-string ~@keyvals))

(defmacro spy
  "Logs expr and its value at DEBUG level, returns value."
  [expr]
  (let [value' (gensym "value")
        expr' (gensym "expr")]
    `(let [~value' ~expr
           ~expr' ~(list 'quote expr)]
       (log/debug :spy ~expr'
                  :returns ~value'
                  ::log/formatter json/write-value-as-string)
       ~value')))

(defmacro with-context
  [ctx-map & body]
  `(log/with-context (assoc ~ctx-map ::log/formatter json/write-value-as-string)
     ~@body))

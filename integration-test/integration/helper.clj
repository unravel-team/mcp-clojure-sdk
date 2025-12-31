(ns integration.helper
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as string]
   [clojure.test :refer [is]])
  (:import
   [java.net URI URLDecoder]
   [java.nio.charset StandardCharsets]))

(defn file->uri
  [file]
  (-> file io/file .getCanonicalFile .toURI .toString))

(defn uri->file
  [uri]
  (io/file (URI. uri)))

(defn unescape-uri
  [^String uri]
  (try
    (URLDecoder/decode uri (.name StandardCharsets/UTF_8))
    (catch IllegalArgumentException _ uri)))

(defn string=
  "Like `clojure.core/=` applied on STRING1 and STRING2, but treats
  any line endings as equal."
  [string1 string2]
  (= (string/split-lines string1) (string/split-lines string2)))

(defn str-includes?
  "Like `clojure.string/includes?` applied to S and SUBSTR, but treats
  any line endings as equal."
  [s substr]
  (let [s (->> (string/split-lines s) (string/join "\n"))
        substr (->> (string/split-lines substr) (string/join "\n"))]
    (string/includes? s substr)))

(defn newlines->system
  "Converts TEXT new lines to those of the underlying system, and
  returns it."
  [text]
  (-> (string/split-lines text) (string/join (System/lineSeparator))))

(defn assert-submap
  [expected actual]
  (is (= expected (some-> actual (select-keys (keys expected))))
      (str "Actual:\n\n" (pr-str actual) "\nExpected:\n\n" (pr-str expected))))

(defmacro assert-submaps
  "Asserts that maps are submaps of result in corresponding order and
  that the number of maps corresponds to the number of results."
  [maps result]
  `(let [maps# ~maps
         res# ~result]
     (and
       (is (= (count maps#) (count res#))
           (format "Expected %s results, but got: %s \n--\n%s--"
                   (count maps#)
                   (count res#)
                   (with-out-str (pprint/pprint res#))))
       (doseq [[r# m#] (map vector res# maps#)]
         (assert-submap m# r#)))))

(defmacro assert-contains-submaps
  "Asserts that maps are contained submaps of result in results."
  [maps result]
  `(let [maps# ~maps
         res# ~result]
     (doseq [m# maps#]
       (let [m-keys (keys m#)]
         (is (some (fn [element#]
                     (= m# (some-> element# (select-keys m-keys))))
                   res#))))))

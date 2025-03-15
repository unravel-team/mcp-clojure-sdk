(ns vegalite-server
  (:gen-class)
  (:require [babashka.json :as json]
            [babashka.process :as process]
            [io.modelcontext.clojure-sdk.stdio-server :as io-server]
            [me.vedang.logger.interface :as log]))

(def saved-data
  (atom {"sample-data" [{:name "Alice", :age 25, :city "New York"}
                        {:name "Bob", :age 30, :city "San Francisco"}
                        {:name "Charlie", :age 35, :city "Los Angeles"}]}))

(def tool-save-data
  {:name "save-data",
   :description
   "A tool to save data tables for later visualization.
   - Use when you have data to visualize later
   - Provide table name and data array",
   :inputSchema {:type "object",
                 :properties {"name" {:type "string",
                                      :description
                                      "Table name to save data under"},
                              "data" {:type "array",
                                      :items {:type "object"},
                                      :description "Data rows as objects"}},
                 :required ["name" "data"]},
   :handler (fn [{:keys [name data]}]
              (swap! saved-data assoc name data)
              {:type "text", :text (format "Data saved to table '%s'" name)})})

(defn- vl2png
  [spec]
  (try (let [spec-json (json/write-str spec)
             ;; vl-convert tool has the following help:
             ;;
             ;; vl-convert vl2png --help
             ;;
             ;; Convert a Vega-Lite specification to an PNG image
             ;;
             ;; Usage: vl-convert vl2png [OPTIONS] --input <INPUT> --output
             ;; <OUTPUT>
             ;;
             ;; Options:
             ;;   -i, --input <INPUT>
             ;;           Path to input Vega-Lite file
             ;;   -o, --output <OUTPUT>
             ;;           Path to output PNG file to be created
             ;; // Update this code to use vl-convert correctly ai!
             result (process/sh "vl-convert" "vl2png" :in spec-json)]
         (if (zero? (:exit result))
           {:type "image", :data (:out result), :mimeType "image/png"}
           {:type "text",
            :text (str "PNG conversion error: " (:err result)),
            :is-error true}))
       (catch Exception e
         {:type "text",
          :text (str "Conversion failed: " (.getMessage e)),
          :is-error true})))

(defn- visualize-data
  [{:keys [table-name vegalite-spec output-type], :or {output-type "png"}}]
  (try (if-let [data (get @saved-data table-name)]
         (let [spec (try (json/read-str vegalite-spec)
                         (catch Exception e
                           {:type "text",
                            :text (str "Spec parse error: " e),
                            :is-error true}))]
           (if (:is-error spec)
             spec
             (let [full-spec (assoc spec :data {:values data})]
               (case output-type
                 "png" (vl2png full-spec)
                 "txt" {:type "text", :text full-spec}))))
         {:type "text",
          :text (format "Data table '%s' not found" table-name),
          :is-error true})
       (catch Exception e
         {:type "text", :text (str "Unexpected error: " e), :is-error true})))

(def tool-visualize-data
  {:name "visualize-data",
   :description
   "Tool to visualize data using Vega-Lite specs.
   - Use for complex data visualization
   - Requires pre-saved data table name
   - Provide Vega-Lite spec (without data)",
   :inputSchema
   {:type "object",
    :properties
    {"table-name" {:type "string", :description "Name of saved data table"},
     "vegalite-spec" {:type "string",
                      :description "Vega-Lite JSON spec (string)"},
     "output-type" {:type "string",
                    :description "One of `png` or `txt`, defines return type"}},
    :required ["table-name" "vegalite-spec"]},
   :handler visualize-data})

(def vegalite-server-spec
  {:name "vegalite",
   :version "1.0.0",
   :tools [;; Save Data
           tool-save-data
           ;; Visualize Data
           tool-visualize-data]})

(defn -main
  [& _args]
  (let [server-id (random-uuid)]
    (log/debug "[MAIN] Starting vegalite server: " server-id)
    @(io-server/run! (assoc vegalite-server-spec :server-id server-id))))

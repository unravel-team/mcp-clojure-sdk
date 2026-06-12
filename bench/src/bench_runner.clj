(ns bench-runner
  "SDK-neutral benchmark driver. Spawns each benchmark server as a
  subprocess, speaks raw newline-delimited JSON-RPC over its
  stdin/stdout, and reports comparative latency/throughput numbers."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json])
  (:import [java.io BufferedReader BufferedWriter File]
           [java.lang ProcessBuilder$Redirect]
           [java.util.concurrent TimeUnit]))

(def bench-dir
  "Working directory for spawned servers (the directory holding this
  project's deps.edn)."
  (.getAbsoluteFile (File. "")))

(def server-commands
  {:java-sdk ["clojure" "-M:java-server"],
   :cljc-sdk ["clojure" "-M:cljc-server"],
   :cljc-sdk-aot ["clojure" "-M:cljc-server-aot"]})

;;; Raw stdio JSON-RPC client

(defn- reader-loop
  "Reads stdout lines from the server process, parses each as JSON and
  delivers it to the promise registered under its id in `pending*`.
  Unparseable lines are reported on stderr and skipped, so one corrupt
  line cannot silently kill the benchmark."
  [^BufferedReader rdr pending*]
  (loop []
    (when-let [line (.readLine rdr)]
      (when-not (str/blank? line)
        (try (let [msg (json/read-value line)
                   id (get msg "id")]
               (when-some [p (get @pending* id)]
                 (swap! pending* dissoc id)
                 (deliver p msg)))
             (catch Exception _
               (binding [*out* *err*]
                 (println "[bench] unparseable line from server:"
                          (subs line 0 (min 200 (count line))))))))
      (recur))))

(defn spawn-client
  "Spawns the server subprocess for `server-key` and returns a client
  map. Stderr is discarded so logging can never block the benchmark."
  [server-key]
  (let [pb (doto (ProcessBuilder. ^java.util.List (server-commands server-key))
             (.directory bench-dir)
             (.redirectError ProcessBuilder$Redirect/DISCARD))
        proc (.start pb)
        wtr (io/writer (.getOutputStream proc))
        rdr (io/reader (.getInputStream proc))
        pending* (atom {})
        reader-thread (doto (Thread. #(reader-loop rdr pending*))
                        (.setDaemon true)
                        (.start))]
    {:proc proc,
     :writer wtr,
     :pending* pending*,
     :reader-thread reader-thread,
     :next-id* (atom 0)}))

(defn- write-message!
  [{:keys [^BufferedWriter writer]} msg]
  (locking writer
    (.write writer ^String (json/write-value-as-string msg))
    (.write writer "\n")
    (.flush writer)))

(defn send-request!
  "Sends a request and returns [id promise-of-response]."
  [{:keys [pending* next-id*], :as client} method params]
  (let [id (swap! next-id* inc)
        p (promise)]
    (swap! pending* assoc id p)
    (write-message! client
                    {:jsonrpc "2.0", :id id, :method method, :params params})
    [id p]))

(defn request!
  "Sends a request and blocks until its response arrives."
  [client method params]
  (let [[id p] (send-request! client method params)
        response (deref p 60000 ::timeout)]
    (when (= ::timeout response)
      (throw (ex-info "Timed out waiting for response"
                      {:id id, :method method})))
    response))

(defn notify!
  [client method params]
  (write-message! client {:jsonrpc "2.0", :method method, :params params}))

(defn initialize!
  "Performs the MCP initialize handshake."
  [client]
  (let [response (request! client
                           "initialize"
                           {:protocolVersion "2025-03-26",
                            :capabilities {},
                            :clientInfo {:name "bench-runner",
                                         :version "0.1.0"}})]
    (notify! client "notifications/initialized" {})
    response))

(defn shutdown!
  "Gracefully destroys the server process."
  [{:keys [^Process proc]}]
  (.destroy proc)
  (when-not (.waitFor proc 5 TimeUnit/SECONDS) (.destroyForcibly proc))
  nil)

(defn call-tool!
  [client tool-name arguments]
  (request! client "tools/call" {:name tool-name, :arguments arguments}))

;;; Statistics

(defn percentile
  "Returns the p-th percentile (0-100) of `sorted` samples by rank."
  [sorted p]
  (let [n (count sorted)
        idx (min (dec n) (long (Math/ceil (- (* (/ p 100.0) n) 1))))]
    (nth sorted (max 0 idx))))

(defn stats
  "Summarizes nanosecond samples as mean/p50/p95/p99 in milliseconds."
  [nanos]
  (let [sorted (vec (sort nanos))
        ->ms #(/ (double %) 1e6)]
    {:n (count sorted),
     :mean-ms (->ms (/ (reduce + sorted) (count sorted))),
     :p50-ms (->ms (percentile sorted 50)),
     :p95-ms (->ms (percentile sorted 95)),
     :p99-ms (->ms (percentile sorted 99))}))

;;; Benchmarks

(defn bench-startup
  "Times `rounds` cold spawns from process start to initialize response.
  Does one untimed warmup spawn first so cpcache computation does not
  pollute the timings."
  [server-key rounds]
  (shutdown! (doto (spawn-client server-key) initialize!))
  (let [samples (vec (for [_ (range rounds)]
                       (let [start (System/nanoTime)
                             client (spawn-client server-key)]
                         (initialize! client)
                         (let [elapsed (- (System/nanoTime) start)]
                           (shutdown! client)
                           elapsed))))]
    {:samples-ms (mapv #(/ (double %) 1e6) samples),
     :median-ms (/ (double (percentile (vec (sort samples)) 50)) 1e6)}))

(defn- timed-serial
  [client warmup timed run-fn]
  (dotimes [_ warmup] (run-fn client))
  (vec (for [_ (range timed)]
         (let [start (System/nanoTime)]
           (run-fn client)
           (- (System/nanoTime) start)))))

(defn bench-ping
  [client]
  (stats (timed-serial client 200 1000 #(request! % "ping" {}))))

(defn bench-echo
  [client]
  (let [message "abcdefghijklmnopqrstuvwx"] ;; 24 bytes
    (stats (timed-serial client
                         200
                         1000
                         #(call-tool! % "echo" {:message message})))))

(defn bench-bigecho
  [client]
  (let [message "abcdefghijklmnopqrstuvwx"]
    (stats (timed-serial client
                         50
                         300
                         #(call-tool! % "bigecho" {:message message})))))

(defn bench-concurrency
  "Sends 100 pipelined sleepy calls (20ms each) without waiting, then
  awaits all responses (10s deadline per round). Responses that never
  arrive are counted as lost rather than aborting the run: mcp-java-sdk
  1.1.3 silently drops responses when concurrent async tool completions
  race its outbound Sinks.Many (StdioServerTransportProvider.sendMessage
  tryEmitNext returns FAIL_NON_SERIALIZED and the error is swallowed by
  fire-and-forget subscribes)."
  [client]
  (let [round (fn []
                (let [start (System/nanoTime)
                      promises (mapv (fn [_]
                                       (second (send-request! client
                                                              "tools/call"
                                                              {:name "sleepy",
                                                               :arguments
                                                               {:ms 20}})))
                                 (range 100))
                      deadline (+ (System/currentTimeMillis) 10000)
                      lost (count (filterv
                                    (fn [p]
                                      (= ::timeout
                                         (deref
                                           p
                                           (max 1
                                                (- deadline
                                                   (System/currentTimeMillis)))
                                           ::timeout)))
                                    promises))]
                  {:wall-ms (/ (double (- (System/nanoTime) start)) 1e6),
                   :lost lost}))
        rounds (vec (repeatedly 3 round))]
    {:rounds rounds,
     :best-ms (apply min (map :wall-ms rounds)),
     :total-lost (reduce + (map :lost rounds))}))

(defn run-server-benchmarks
  [server-key]
  (binding [*out* *err*] (println "==> Benchmarking" (name server-key)))
  (let [startup (bench-startup server-key 5)
        client (spawn-client server-key)]
    (try (initialize! client)
         {:startup startup,
          :ping (bench-ping client),
          :echo (bench-echo client),
          :bigecho (bench-bigecho client),
          :concurrency (bench-concurrency client)}
         (finally (shutdown! client)))))

;;; Reporting

(defn- fmt-stats
  [{:keys [mean-ms p50-ms p95-ms p99-ms]}]
  (format "mean %.3fms p50 %.3fms p95 %.3fms p99 %.3fms"
          mean-ms
          p50-ms
          p95-ms
          p99-ms))

(defn- fmt-startup
  [{:keys [samples-ms median-ms]}]
  (format "median %.0fms (%s)"
          median-ms
          (str/join " " (map #(format "%.0f" %) samples-ms))))

(defn- fmt-concurrency
  [{:keys [rounds best-ms total-lost]}]
  (if (pos? total-lost)
    (format "LOST %d/300 responses (rounds: %s)"
            total-lost
            (str/join " "
                      (map #(format "%.0fms/%d-lost" (:wall-ms %) (:lost %))
                        rounds)))
    (format "best %.1fms, 0 lost (%s)"
            best-ms
            (str/join " " (map #(format "%.1f" (:wall-ms %)) rounds)))))

(def ^:private row-formatters
  [["startup (5 cold spawns)" :startup fmt-startup]
   ["ping RTT (1000 serial)" :ping fmt-stats]
   ["echo 24B (1000 serial)" :echo fmt-stats]
   ["bigecho 64KB (300 serial)" :bigecho fmt-stats]
   ["sleepy 100x20ms pipelined" :concurrency fmt-concurrency]])

(defn print-table
  [results]
  (let [server-keys (vec (keys results))
        rows (for [[label k fmt] row-formatters]
               (into [label] (map #(fmt (get-in results [% k])) server-keys)))
        headers (into ["benchmark"] (map name server-keys))
        widths (for [col (range (count headers))]
                 (apply max (map #(count (nth % col)) (cons headers rows))))
        fmt-row (fn [row]
                  (str/join " | "
                            (map (fn [cell width]
                                   (format (str "%-" width "s") cell))
                              row
                              widths)))]
    (println)
    (println (fmt-row headers))
    (println (str/join "-+-" (map #(apply str (repeat % "-")) widths)))
    (doseq [row rows] (println (fmt-row row)))))

(defn write-results!
  [timestamp results]
  (let [file (io/file bench-dir "results" (str timestamp ".edn"))]
    (io/make-parents file)
    (spit file (pr-str {:timestamp timestamp, :results results}))
    (.getPath file)))

(defn -main
  [& args]
  (let [timestamp (System/currentTimeMillis)
        server-keys (case (first args)
                      "java" [:java-sdk]
                      "cljc" [:cljc-sdk]
                      "aot" [:cljc-sdk-aot]
                      nil [:java-sdk :cljc-sdk :cljc-sdk-aot])
        results
          (into {} (map (fn [k] [k (run-server-benchmarks k)])) server-keys)
        results-file (write-results! timestamp results)]
    (print-table results)
    (println)
    (println "Results written to" results-file)
    (shutdown-agents)))

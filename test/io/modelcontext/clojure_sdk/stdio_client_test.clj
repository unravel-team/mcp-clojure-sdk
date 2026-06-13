(ns io.modelcontext.clojure-sdk.stdio-client-test
  (:require [clojure.test :refer [deftest is testing]]
            [io.modelcontext.clojure-sdk.stdio-client :as stdio-client]))

(deftest start-and-stop-process
  (testing "Process lifecycle helpers"
    (let [process (stdio-client/start-server-process! "sh" ["-c" "sleep 5"]
                                                      :inherit-stderr? false)]
      (try (is (.isAlive process))
           (stdio-client/stop-process! process :timeout-ms 500)
           (Thread/sleep 50)
           (is (not (.isAlive process)))
           (finally (when (.isAlive process)
                      (stdio-client/stop-process! process :timeout-ms 500)))))))

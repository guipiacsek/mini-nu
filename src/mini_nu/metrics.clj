(ns mini-nu.metrics
  "In-process counters for /metrics.
  shortcut: lives in one JVM and resets on restart; export to Prometheus when there is more than one instance.")

(defonce ^:private state
  (atom {:started (System/currentTimeMillis) :requests {} :outcomes {} :latencies []}))

(defn- keep-recent [v]
  (if (> (count v) 2000) (vec (take-last 1000 v)) v))

(defn record-request! [route status ms]
  (swap! state #(-> %
                    (update-in [:requests [route status]] (fnil inc 0))
                    (update :latencies (comp keep-recent conj) ms))))

(defn record-outcome!
  "One count per money movement result: :ok or the error keyword."
  [outcome]
  (swap! state update-in [:outcomes outcome] (fnil inc 0)))

(defn- percentile [sorted p]
  (when (seq sorted)
    (nth sorted (min (dec (count sorted)) (int (* p (count sorted)))))))

(defn snapshot []
  (let [{:keys [started requests outcomes latencies]} @state
        sorted (vec (sort (take-last 1000 latencies)))]
    {:uptime-seconds (quot (- (System/currentTimeMillis) started) 1000)
     :requests (for [[[route status] n] (sort-by key requests)]
                 {:route route :status status :count n})
     :transfer-outcomes outcomes
     :latency-ms {:p50 (percentile sorted 0.5)
                  :p95 (percentile sorted 0.95)
                  :p99 (percentile sorted 0.99)
                  :max (peek sorted)
                  :samples (count sorted)}}))

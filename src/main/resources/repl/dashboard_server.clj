(ns repl.dashboard-server
  "Server-only helpers for the Electric dashboard. Runs inside the target JVM;
   never compiled to CLJS. Kept separate from dashboard.main so the Electric
   compiler only sees plain clj vars in e/server positions."
  (:require [repl.inspect :as inspect]))

;;; ---------------------------------------------------------------------------
;;; live snapshot source (1 Hz)

(defonce !snapshot (atom nil))

(defonce start-poller!
  (delay
    (doto (Thread.
            ^Runnable
            (fn []
              (loop []
                (reset! !snapshot
                        (try (inspect/snapshot)
                             (catch Throwable t {:error (str t)})))
                (Thread/sleep 1000)
                (recur)))
            "nrepl-dashboard-poller")
      (.setDaemon true)
      (.start))))

;;; ---------------------------------------------------------------------------
;;; eval console

(defn search-classes-safe
  "search-classes with error capture (Electric code cannot contain try)."
  [q limit]
  (try {:ok (inspect/search-classes q limit)}
       (catch Throwable t {:err (str t)})))

(defn eval-string
  "Eval Clojure code in the `user` namespace of the target JVM."
  [code]
  (try
    {:ok?   true
     :value (pr-str (binding [*ns* (the-ns 'user)]
                      (load-string code)))}
    (catch Throwable t
      {:ok?   false
       :error (str (.getSimpleName (class t)) ": " (ex-message t))})))

;;; ---------------------------------------------------------------------------
;;; formatting

(defn fmt-bytes [n]
  (when (number? n)
    (cond (>= n 1073741824) (format "%.1f GB" (/ n 1073741824.0))
          (>= n 1048576)    (format "%.1f MB" (/ n 1048576.0))
          (>= n 1024)       (format "%.1f KB" (/ n 1024.0))
          :else             (str n " B"))))

(defn fmt-uptime [ms]
  (when (number? ms)
    (let [s (quot ms 1000)
          h (quot s 3600)
          m (quot (rem s 3600) 60)]
      (cond (pos? h) (format "%dh %dm %ds" h m (rem s 60))
            (pos? m) (format "%dm %ds" m (rem s 60))
            :else    (format "%ds" s)))))

(defn fmt-load [x]
  (when (and (number? x) (>= x 0))
    (format "%.2f" x)))

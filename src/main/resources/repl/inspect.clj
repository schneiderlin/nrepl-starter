(ns repl.inspect
  "Live JVM introspection over JMX + Instrumentation, returned as plain
   EDN-able data.

  Auto-loaded by nrepl-starter at startup (after repl.tools). Everything here
  is a plain function returning data — the same data a dashboard frontend
  would stream. Functions that need Instrumentation require the JVM to have
  been started with -javaagent; they throw a clear error otherwise.

    (repl.inspect/memory)                       ;; heap / non-heap / per-pool usage
    (repl.inspect/threads)                      ;; counts, deadlocks
    (repl.inspect/thread-dump)                  ;; every thread + stack trace
    (repl.inspect/search-classes \"com.example\") ;; regex over all loaded classes
    (repl.inspect/class-detail \"com.example.App\")
    (repl.inspect/object-size some-object)      ;; shallow size in bytes
    (repl.inspect/heap-dump! \"/tmp/dump.hprof\")
    (repl.inspect/snapshot)                     ;; everything at once"
  (:require [repl.tools :as tools])
  (:import [java.io File]
           [java.lang.management ManagementFactory MemoryUsage ThreadInfo]))

;;; ---------------------------------------------------------------------------
;;; memory

(defn- usage-map [^MemoryUsage u]
  (when u
    {:init (.getInit u) :used (.getUsed u)
     :committed (.getCommitted u) :max (.getMax u)}))

(defn memory
  "Heap / non-heap usage and per-pool breakdown."
  []
  (let [m (ManagementFactory/getMemoryMXBean)]
    {:heap                 (usage-map (.getHeapMemoryUsage m))
     :non-heap             (usage-map (.getNonHeapMemoryUsage m))
     :pending-finalization (.getObjectPendingFinalizationCount m)
     :pools                (mapv (fn [p]
                                   {:name  (.getName p)
                                    :type  (str (.getType p))
                                    :usage (usage-map (.getUsage p))})
                                 (ManagementFactory/getMemoryPoolMXBeans))}))

(defn gc
  "Per-collector collection counts and total time."
  []
  (mapv (fn [b]
          {:name               (.getName b)
           :collection-count   (.getCollectionCount b)
           :collection-time-ms (.getCollectionTime b)})
        (ManagementFactory/getGarbageCollectorMXBeans)))

;;; ---------------------------------------------------------------------------
;;; threads

(defn threads
  "Thread counts and deadlock detection (thread ids)."
  []
  (let [t (ManagementFactory/getThreadMXBean)]
    {:count               (.getThreadCount t)
     :daemon              (.getDaemonThreadCount t)
     :peak                (.getPeakThreadCount t)
     :total-started       (.getTotalStartedThreadCount t)
     :deadlocked          (some-> (.findDeadlockedThreads t) vec)
     :monitor-deadlocked  (some-> (.findMonitorDeadlockedThreads t) vec)}))

(defn thread-dump
  "All live threads with their stack traces. Can be large on big apps."
  []
  (let [t (ManagementFactory/getThreadMXBean)]
    (mapv (fn [^ThreadInfo ti]
            {:name          (.getThreadName ti)
             :id            (.getThreadId ti)
             :state         (str (.getThreadState ti))
             :daemon        (.isDaemon ti)
             :priority      (.getPriority ti)
             :blocked-count (.getBlockedCount ti)
             :waited-count  (.getWaitedCount ti)
             :lock-name     (.getLockName ti)
             :lock-owner    (.getLockOwnerName ti)
             :stack         (mapv str (.getStackTrace ti))})
          (.dumpAllThreads t true true))))

;;; ---------------------------------------------------------------------------
;;; classes (needs -javaagent)

(defn class-stats
  "Counts of loaded classes (from Instrumentation)."
  []
  {:loaded-count (count (.getAllLoadedClasses (tools/instrumentation)))})

(defn search-classes
  "Loaded class names whose binary name matches `pattern` (regex).
   Returns at most `limit` names (default 100)."
  ([pattern] (search-classes pattern 100))
  ([pattern limit]
   (let [re (re-pattern pattern)]
     (->> (.getAllLoadedClasses (tools/instrumentation))
          (map #(.getName ^Class %))
          (filter #(re-find re %))
          (take limit)
          vec))))

(defn class-detail
  "Reflection detail for every loaded Class with this binary name (one entry
   per defining classloader): superclass, interfaces, declared methods/fields."
  [class-name]
  (mapv (fn [^Class c]
          {:name              (.getName c)
           :classloader       (str (.getClassLoader c))
           :superclass        (some-> (.getSuperclass c) .getName)
           :interfaces        (mapv #(.getName ^Class %) (.getInterfaces c))
           :declared-methods  (mapv #(.getName %) (.getDeclaredMethods c))
           :declared-fields   (mapv #(.getName %) (.getDeclaredFields c))})
        (tools/find-loaded-classes class-name)))

(defn object-size
  "Shallow size of `obj` in bytes (Instrumentation/getObjectSize)."
  [obj]
  (.getObjectSize (tools/instrumentation) obj))

;;; ---------------------------------------------------------------------------
;;; heap dump

(defn heap-dump!
  "Dump the heap to `path` (.hprof). With live-only? (default true) the JVM
   GCs first and dumps only live objects."
  ([path] (heap-dump! path true))
  ([path live-only?]
   (let [bean (ManagementFactory/getPlatformMXBean com.sun.management.HotSpotDiagnosticMXBean)]
     (.dumpHeap bean path (boolean live-only?))
     {:ok? true :path path :bytes (.length (File. path))})))

;;; ---------------------------------------------------------------------------
;;; runtime / system

(defn runtime
  "VM identity, uptime, and launch arguments."
  []
  (let [r (ManagementFactory/getRuntimeMXBean)]
    {:vm-name         (.getVmName r)
     :vm-version      (.getVmVersion r)
     :vm-vendor       (.getVmVendor r)
     :uptime-ms       (.getUptime r)
     :start-time-ms   (.getStartTime r)
     :input-arguments (vec (.getInputArguments r))
     :classpath       (.getClassPath r)}))

(defn system
  "OS and CPU info. :process-cpu-load / :system-cpu-load / physical memory are
   included when the HotSpot operating-system bean is available."
  []
  (let [o (ManagementFactory/getOperatingSystemMXBean)]
    (cond-> {:name          (.getName o)
             :version       (.getVersion o)
             :arch          (.getArch o)
             :processors    (.getAvailableProcessors o)
             :load-average  (.getSystemLoadAverage o)}
      (instance? com.sun.management.OperatingSystemMXBean o)
      (merge (let [^com.sun.management.OperatingSystemMXBean so o]
               {:process-cpu-load      (.getProcessCpuLoad so)
                :system-cpu-load       (.getSystemCpuLoad so)
                :free-physical-memory  (.getFreePhysicalMemorySize so)
                :total-physical-memory (.getTotalPhysicalMemorySize so)})))))

;;; ---------------------------------------------------------------------------
;;; dashboard snapshot

(defn snapshot
  "One call, everything a live dashboard needs: memory, GC, threads, system,
   uptime, loaded-class count (when the agent is present)."
  []
  {:at        (System/currentTimeMillis)
   :memory    (memory)
   :gc        (gc)
   :threads   (threads)
   :system    (system)
   :uptime-ms (.getUptime (ManagementFactory/getRuntimeMXBean))
   :classes   (try (class-stats)
                   (catch Throwable _ {:loaded-count nil :error "no -javaagent"}))})

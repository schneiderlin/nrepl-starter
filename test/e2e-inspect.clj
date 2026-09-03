(ns e2e-inspect
  "End-to-end Phase 2 check: exercise repl.inspect over a real nrepl
   connection to the running example app. Run with:
     java -cp <agent.jar> clojure.main -i test/e2e-inspect.clj"
  (:require [nrepl.core :as nrepl]))

(defn eval* [client code]
  (let [responses (nrepl/message client {:op "eval" :code code})]
    (if-let [v (some :value responses)]
      (read-string v)
      (throw (ex-info (str "eval failed: " (some :err responses)) {})))))

(defn check [label ok? detail]
  (println (str (if ok? "PASS" "FAIL") " " label "  " (pr-str detail)))
  (when-not ok? (System/exit 1)))

(defn -main []
  (with-open [conn (nrepl/connect :port 7888)]
    (let [client (nrepl/client conn 30000)]

      (let [m (eval* client "(repl.inspect/memory)")]
        (check "memory" (and (pos? (get-in m [:heap :used]))
                             (seq (:pools m)))
               {:heap-used (get-in m [:heap :used]) :pools (count (:pools m))}))

      (let [g (eval* client "(repl.inspect/gc)")]
        (check "gc" (and (vector? g) (seq g)) g))

      (let [t (eval* client "(repl.inspect/threads)")]
        (check "threads" (pos? (:count t)) t))

      (let [td (eval* client "(count (repl.inspect/thread-dump))")]
        (check "thread-dump" (pos? td) td))

      (let [cs (eval* client "(repl.inspect/class-stats)")]
        (check "class-stats" (pos? (:loaded-count cs)) cs))

      (let [found (eval* client "(repl.inspect/search-classes \"^com.example\")")]
        (check "search-classes" (some #{"com.example.App"} found) found))

      (let [d (eval* client "(repl.inspect/class-detail \"com.example.App\")")]
        (check "class-detail"
               (some #{"add"} (:declared-methods (first d)))
               (select-keys (first d) [:name :superclass :declared-methods])))

      (let [size (eval* client "(repl.inspect/object-size \"hello\")")]
        (check "object-size" (pos? size) size))

      (let [r (eval* client "(select-keys (repl.inspect/runtime) [:vm-name :vm-version])")]
        (check "runtime" (seq (:vm-name r)) r))

      (let [s (eval* client "(repl.inspect/system)")]
        (check "system" (pos? (:processors s)) s))

      (let [dump (eval* client "(repl.inspect/heap-dump! \"/tmp/nrepl-starter-e2e.hprof\")")]
        (check "heap-dump!" (and (:ok? dump) (pos? (:bytes dump))) dump))

      (let [snap (eval* client "(keys (repl.inspect/snapshot))")]
        (check "snapshot" (= #{:at :vm :memory :gc :threads :system :uptime-ms :classes}
                             (set snap))
               snap)))))

(-main)
;; nrepl client threads are non-daemon; exit explicitly
(shutdown-agents)
(System/exit 0)

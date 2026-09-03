(ns e2e
  "End-to-end Phase 1 check: connect to the nrepl server inside the running
   example app and exercise repl.tools. Run with:
     java -cp <agent.jar> clojure.main -i test/e2e.clj"
  (:require [nrepl.core :as nrepl]))

(defn eval* [client code]
  (let [responses (nrepl/message client {:op "eval" :code code})]
    (->> responses
         (filter :value)
         (map :value)
         last
         (read-string))))

(defn -main []
  (with-open [conn (nrepl/connect :port 7888)]
    (let [client (nrepl/client conn 20000)]

      (println "== 1. sanity: repl.tools loaded, App/add original ==")
      (println (eval* client "(com.example.App/add 1 2)"))

      (println "== 2. compile-and-load! a brand-new class ==")
      (println (eval* client
                 "(repl.tools/compile-and-load!
                    \"com.example.Greeter\"
                    \"package com.example;
                     public class Greeter {
                       public static String greet(String name) { return \\\"hello \\\" + name; }
                     }\")"))

      (println "== 3. invoke the new class reflectively ==")
      (println (eval* client
                 "(let [r (repl.tools/compile-and-load!
                            \"com.example.Greeter\"
                            \"package com.example;
                             public class Greeter {
                               public static String greet(String name) { return \\\"hello \\\" + name; }
                             }\")
                        c (:class r)]
                    (-> (.getMethod c \"greet\" (into-array Class [String]))
                        (.invoke nil (object-array [\"nrepl\"]))))"))

      (println "== 4. redefine! App/add: + becomes * ==")
      (println (eval* client
                 "(repl.tools/redefine!
                    \"com.example.App\"
                    \"package com.example;
                     public class App {
                       public static int add(int a, int b) { return a * b; }
                       public int subtract(int a, int b) { return a - b; }
                       public static void main(String[] args) throws InterruptedException {
                         Thread.currentThread().join();
                       }
                     }\")"))

      (println "== 5. App/add after redefine ==")
      (println (eval* client "(com.example.App/add 3 4)"))

      (println "== 6. instrumentation introspection ==")
      (println (eval* client "(count (repl.tools/find-loaded-classes \"com.example.App\"))")))))

(-main)
;; nrepl client threads are non-daemon; exit explicitly
(shutdown-agents)
(System/exit 0)

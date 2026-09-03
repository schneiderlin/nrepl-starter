(ns e2e-invoke
  "End-to-end check of repl.invoke over a real nrepl connection:
   static method, constructor + instance method via the object shelf,
   and EDN map -> Java bean coercion. Run with:
     java -cp <agent.jar> clojure.main -i test/e2e-invoke.clj"
  (:require [clojure.string :as str]
            [nrepl.core :as nrepl]))

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

      ;; load richer test fixtures into the target JVM
      (eval* client
        "(do
           (repl.tools/compile-and-load!
             \"com.example.IntPair\"
             \"package com.example;
              public class IntPair {
                private int a; private int b;
                public int getA() { return a; } public void setA(int a) { this.a = a; }
                public int getB() { return b; } public void setB(int b) { this.b = b; }
              }\")
           (repl.tools/compile-and-load!
             \"com.example.Calc\"
             \"package com.example;
              public class Calc {
                public static int sum(IntPair p) { return p.getA() + p.getB(); }
              }\")
           :fixtures-loaded)")

      (let [info (eval* client "(repl.invoke/list-methods \"com.example.App\")")]
        (check "list-methods"
               (some #(str/includes? (:label %) "add (int, int)") (:entries info))
               (mapv :label (take 4 (:entries info)))))

      ;; --- static method: App.add(3, 4) ---
      (let [add-sel (eval* client
                      "(->> (repl.invoke/list-methods \"com.example.App\") :entries
                            (filter #(clojure.string/includes? (:label %) \"static add\"))
                            first :key)")
            res (eval* client
                  (str "(repl.invoke/invoke! {:class-name \"com.example.App\" :sel \"" add-sel
                       "\" :args-text \"3\\n4\"})"))]
        (check "invoke static add(3,4)" (and (:ok? res) (= 7 (:result res))) res))

      ;; --- constructor -> shelf, then instance method with shelf target ---
      (let [ctor-sel (eval* client
                       "(->> (repl.invoke/list-methods \"com.example.App\") :entries
                             (filter #(= :ctor (:kind %))) first :key)")
            ctor-res (eval* client
                       (str "(repl.invoke/invoke! {:class-name \"com.example.App\" :sel \"" ctor-sel "\"})"))
            target-id (get-in ctor-res [:shelf :id])]
        (check "ctor stored on shelf" (and (:ok? ctor-res) (some? target-id)) ctor-res)
        (let [sub-sel (eval* client
                        "(->> (repl.invoke/list-methods \"com.example.App\") :entries
                              (filter #(clojure.string/includes? (:label %) \"subtract\"))
                              first :key)")
              res (eval* client
                    (str "(repl.invoke/invoke! {:class-name \"com.example.App\" :sel \"" sub-sel
                         "\" :target-id \"" target-id "\" :args-text \"10\\n3\"})"))]
          (check "instance subtract(10,3) via shelf" (and (:ok? res) (= 7 (:result res))) res)))

      ;; --- EDN map -> Java bean: Calc.sum({:a 3 :b 4}) ---
      (let [sum-sel (eval* client
                      "(->> (repl.invoke/list-methods \"com.example.Calc\") :entries
                            (filter #(clojure.string/includes? (:label %) \"sum\"))
                            first :key)")
            res (eval* client
                  (str "(repl.invoke/invoke! {:class-name \"com.example.Calc\" :sel \"" sum-sel
                       "\" :args-text \"{:a 3 :b 4}\"})"))]
        (check "map->bean sum({:a 3 :b 4})" (and (:ok? res) (= 7 (:result res))) res))

      ;; --- error paths ---
      (let [res (eval* client
                  "(repl.invoke/invoke! {:class-name \"com.example.App\" :sel \"m-0\" :args-text \"1\"})")]
        (check "arity mismatch reported" (and (not (:ok? res)) (some? (:error res))) res))

      (let [res (eval* client
                  "(repl.invoke/invoke! {:class-name \"com.example.App\" :sel \"m-99\"})")]
        (check "bad entry reported" (and (not (:ok? res)) (:error res)) res)))))

(-main)
;; nrepl client threads are non-daemon; exit explicitly
(shutdown-agents)
(System/exit 0)

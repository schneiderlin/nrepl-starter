(ns repl.core
  (:import [com.example App]))

(comment
  ;; calling static methods
  (App/add 1 2)

  ;; create instance
  (def app (App.))
  ;; calling instance methods
  (.subtract app 1 2)

  ;; --- repl.tools: on-the-fly Java compile / load / hot-swap ---

  ;; compile + load a brand-new class into a fresh classloader
  (repl.tools/compile-and-load!
    "com.example.Greeter"
    "package com.example;
     public class Greeter {
       public static String greet(String name) { return \"hello \" + name; }
     }")

  ;; invoke it reflectively (it lives in its own classloader)
  (let [c (:class *1)]
    (-> (.getMethod c "greet" (into-array Class [String]))
        (.invoke nil (object-array ["world"]))))

  ;; hot-swap method bodies of an already-loaded class (requires -javaagent)
  (repl.tools/redefine-file! "com.example.App" "src/main/java/com/example/App.java")
  (App/add 3 4)
  :rcf)
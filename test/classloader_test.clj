(ns classloader-test
  (:require [clojure.test :refer [deftest is run-tests testing]]
            [repl.tools :as tools])
  (:import [clojure.lang AFunction RT]
           [java.io File]
           [java.lang.instrument Instrumentation]
           [java.net URL URLClassLoader]
           [java.nio.file Files]
           [repl.agent NreplAgent]
           [repl.classloader BridgeClassLoader]
           [javax.tools ToolProvider]))

(defn- compile-anchor! [^File root]
  (let [source-dir (doto (File. root "isolated") .mkdirs)
        source-file (File. source-dir "Anchor.java")
        source (str "package isolated;"
                    "public class Anchor {"
                    "  public static String message() { return \"anchor\"; }"
                    "}")
        compiler (ToolProvider/getSystemJavaCompiler)
        exit-code (do
                    (spit source-file source)
                    (.run compiler nil nil nil
                          (into-array String
                                      ["-d" (.getAbsolutePath root)
                                       (.getAbsolutePath source-file)])))]
    (is (zero? exit-code) "isolated anchor fixture should compile")
    source-file))

(deftest isolated-classloader-selection-and-evaluation
  (let [root (.toFile (Files/createTempDirectory "nrepl-classloader-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (compile-anchor! root)
    (with-open [loader (URLClassLoader.
                         (into-array URL [(.toURL (.toURI root))
                                          (-> AFunction
                                              .getProtectionDomain
                                              .getCodeSource
                                              .getLocation)])
                         nil)]
      (let [anchor (.loadClass loader "isolated.Anchor")
            previous NreplAgent/instrumentation
            stub (proxy [Instrumentation] []
                   (getAllLoadedClasses [] (into-array Class [anchor])))]
        (set! NreplAgent/instrumentation stub)
        (try
          (testing "selects the defining loader from an instrumented class"
            (is (identical? loader (tools/classloader-for "isolated.Anchor")))
            (is (identical? loader (tools/classloader-for ["isolated.Anchor" 0]))))

          (testing "keeps the agent Clojure runtime authoritative"
            (let [target-clojure (.loadClass loader "clojure.lang.AFunction")
                  bridge (BridgeClassLoader. loader (RT/baseLoader))]
              (is (not (identical? AFunction target-clojure)))
              (is (identical? AFunction (.loadClass bridge "clojure.lang.AFunction")))))

          (testing "evaluates class references inside the selected loader"
            (let [context-loader (.getContextClassLoader (Thread/currentThread))]
              (is (= "anchor"
                     (tools/with-classloader "isolated.Anchor"
                       (isolated.Anchor/message))))
              (is (identical? context-loader
                              (.getContextClassLoader (Thread/currentThread))))))

          (testing "uses the selected loader for Java compilation and dynamic loading"
            (let [result (tools/call-with-classloader
                           "isolated.Anchor"
                           #(tools/compile-and-load!
                              "isolated.Child"
                              (str "package isolated;"
                                   "public class Child {"
                                   "  public static String message() {"
                                   "    return Anchor.message() + \"-child\";"
                                   "  }"
                                   "}")))
                  child (:class result)]
              (is (:ok? result) (pr-str (:diagnostics result)))
              (is (= loader (.getParent (.getClassLoader child))))
              (is (= "anchor-child"
                     (.invoke (.getMethod child "message" (make-array Class 0))
                              nil
                              (object-array 0))))))

          ;; Keep a strong reference until every assertion has used the class.
          (is (= "isolated.Anchor" (.getName anchor)))
          (finally
            (set! NreplAgent/instrumentation previous)))))))

(let [{:keys [fail error]} (run-tests 'classloader-test)]
  (shutdown-agents)
  (when-not (zero? (+ fail error))
    (throw (ex-info "classloader tests failed" {:fail fail :error error}))))

(ns repl.tools
  "On-the-fly Java compile / load / redefine tools for the target JVM.

  Loaded automatically by nrepl-starter after the nREPL server starts.
  Use from any nREPL client:

    ;; compile + load a brand-new class into a fresh classloader
    (repl.tools/compile-and-load!
      \"com.example.Greeter\"
      \"package com.example;
       public class Greeter { public static String greet() { return \\\"hi\\\"; } }\")

    ;; hot-swap the method bodies of an already-loaded class
    (repl.tools/redefine!
      \"com.example.App\"
      \"package com.example;
       public class App { public static int add(int a, int b) { return a * b; } }\")

  redefine! is JVM HotSwap: schema-preserving changes only (method bodies,
  constants, annotations). Adding/removing methods or fields, or changing
  signatures, is rejected by the JVM — for those, write a new class and
  compile-and-load! instead (or restart the module). redefine! requires the
  JVM to have been started with -javaagent; manual R/start has no
  Instrumentation handle."
  (:require [clojure.string :as str])
  (:import [clojure.lang Compiler DynamicClassLoader RT Var]
           [java.io ByteArrayOutputStream File]
           [java.lang.instrument ClassDefinition Instrumentation]
           [java.net URI URL URLClassLoader]
           [repl.classloader BridgeClassLoader]
           [javax.tools DiagnosticCollector ForwardingJavaFileManager
            JavaFileObject$Kind SimpleJavaFileObject ToolProvider]))

(declare instrumentation find-loaded-classes)

;;; ---------------------------------------------------------------------------
;;; application classloader selection

(def ^:dynamic *target-classloader*
  "Classloader selected for the current evaluation/compilation scope."
  nil)

(defn- loader-closed? [^ClassLoader loader]
  (try
    (let [method (.getMethod (class loader) "isClosed" (make-array Class 0))]
      (true? (.invoke method loader (object-array 0))))
    (catch NoSuchMethodException _ false)
    (catch Throwable _ false)))

(defn classloader-candidates
  "Loaded definitions of `class-name` and their defining classloaders.

   Quarkus dev mode may retain definitions from an older reload. Closed
   classloaders are reported but are not selected by classloader-for."
  [class-name]
  (mapv (fn [index ^Class loaded-class]
          (let [loader (.getClassLoader loaded-class)]
            {:index index
             :class loaded-class
             :classloader loader
             :classloader-type (some-> loader class .getName)
             :classloader-description (str loader)
             :closed? (boolean (and loader (loader-closed? loader)))}))
        (range)
        (find-loaded-classes class-name)))

(defn classloader-for
  "Select the live defining classloader for an already-loaded class.

   With a string selector, returns the first non-bootstrap, non-closed loader.
   Use `[class-name index]` or the two-argument form when more than one live
   definition exists after a framework reload."
  ([selector]
   (if (and (vector? selector) (= 2 (count selector)))
     (classloader-for (nth selector 0) (nth selector 1))
     (classloader-for selector nil)))
  ([class-name index]
   (let [candidates (classloader-candidates class-name)
         live (filterv #(and (:classloader %) (not (:closed? %))) candidates)
         selected (if (nil? index)
                    (first live)
                    (nth candidates index nil))]
     (when (empty? candidates)
       (throw (ex-info (str "class " class-name " is not loaded in any classloader")
                       {:class-name class-name})))
     (when (nil? selected)
       (throw (ex-info (str "classloader index " index " is unavailable for " class-name)
                       {:class-name class-name
                        :index index
                        :candidates (mapv #(dissoc % :class :classloader) candidates)})))
     (when (nil? (:classloader selected))
       (throw (ex-info (str "class " class-name " is loaded by the bootstrap classloader")
                       {:class-name class-name :index (:index selected)})))
     (when (:closed? selected)
       (throw (ex-info (str "classloader index " (:index selected)
                            " is closed for " class-name)
                       {:class-name class-name :index (:index selected)})))
     (:classloader selected))))

(defn- effective-classloader []
  (or *target-classloader*
      (.getContextClassLoader (Thread/currentThread))
      (RT/baseLoader)))

(defonce ^:private compiler-loader-var
  (delay (.get (.getField Compiler "LOADER") nil)))

(defn call-with-classloader
  "Call `f` with the loader selected by `selector` installed as both the
   thread context loader and Clojure compiler parent loader.

   Unlike with-classloader, this function can close over lexical locals."
  [selector f]
  (let [loader (classloader-for selector)
        thread (Thread/currentThread)
        previous (.getContextClassLoader thread)
        bridge-loader (BridgeClassLoader. loader (RT/baseLoader))
        compiler-loader (DynamicClassLoader. bridge-loader)]
    (.setContextClassLoader thread loader)
    (Var/pushThreadBindings {@compiler-loader-var compiler-loader})
    (try
      (binding [*target-classloader* loader]
        (f))
      (finally
        (Var/popThreadBindings)
        (.setContextClassLoader thread previous)))))

(defn eval-with-classloader
  "Compile and evaluate `form` inside the loader selected by `selector`."
  [selector form]
  (call-with-classloader selector #(eval form)))

(defmacro with-classloader
  "Compile and evaluate body using an already-loaded application's
   classloader. The body is recompiled in that scope and therefore does not
   capture lexical locals; use call-with-classloader when closures are needed.

   Examples:
     (with-classloader \"com.example.App\" (com.example.App/status))
     (with-classloader [\"com.example.App\" 1] (com.example.App/status))"
  [selector & body]
  `(eval-with-classloader ~selector (quote ~(cons 'do body))))

;;; ---------------------------------------------------------------------------
;;; in-memory compilation (javax.tools.JavaCompiler, no files touched)

(defn- source-object
  "In-memory JavaFileObject holding the source of class-name."
  [class-name source]
  (proxy [SimpleJavaFileObject]
      [(URI/create (str "string:///" (str/replace class-name "." "/") ".java"))
       JavaFileObject$Kind/SOURCE]
    (getCharContent [_] source)))

(defn- class-output-object
  "In-memory JavaFileObject that captures emitted bytecode into class-bytes."
  [class-name class-bytes]
  (proxy [SimpleJavaFileObject]
      [(URI/create (str "mem:///" (str/replace class-name "." "/") ".class"))
       JavaFileObject$Kind/CLASS]
    (openOutputStream []
      (proxy [ByteArrayOutputStream] []
        (close []
          (let [^ByteArrayOutputStream this this]
            (swap! class-bytes assoc class-name (.toByteArray this)))
          (proxy-super close))))))

(defn- memory-file-manager
  [delegate class-bytes]
  (proxy [ForwardingJavaFileManager] [delegate]
    (getJavaFileForOutput [location class-name kind sibling]
      (if (= JavaFileObject$Kind/CLASS kind)
        (class-output-object class-name class-bytes)
        (proxy-super getJavaFileForOutput location class-name kind sibling)))))

(defn- loader-chain [loader]
  (take-while some? (iterate #(.getParent ^ClassLoader %) loader)))

(defn- url->classpath-entry [^URL url]
  (try
    (when (= "file" (.getProtocol url))
      (.getAbsolutePath (File. (.toURI url))))
    (catch Throwable _ nil)))

(defn- loader-url-entries [loader]
  (mapcat (fn [candidate]
            (when (instance? URLClassLoader candidate)
              (keep url->classpath-entry (.getURLs ^URLClassLoader candidate))))
          (loader-chain loader)))

(defn- loaded-class-entries [loader]
  (try
    (let [loaders (set (loader-chain loader))]
      (->> (.getAllLoadedClasses (instrumentation))
           (filter #(contains? loaders (.getClassLoader ^Class %)))
           (keep (fn [^Class loaded-class]
                   (try
                     (some-> loaded-class
                             .getProtectionDomain
                             .getCodeSource
                             .getLocation
                             url->classpath-entry)
                     (catch Throwable _ nil))))))
    (catch Throwable _ [])))

(defn- default-classpath []
  (let [loader (effective-classloader)]
    (->> (concat [(System/getProperty "java.class.path")]
                 (loader-url-entries loader)
                 (loaded-class-entries loader))
         (remove str/blank?)
         distinct
         (str/join File/pathSeparator))))

(defn compile-java
  "Compile Java `source` for `class-name` entirely in memory.
   Options:
     :classpath - compilation classpath string, defaults to the target app's
                  java.class.path plus any URLClassLoader URLs.
   Returns {:ok? bool :classes {class-name bytes} :diagnostics [strings]}."
  ([class-name source] (compile-java class-name source {}))
  ([class-name source {:keys [classpath]}]
   (let [compiler (ToolProvider/getSystemJavaCompiler)]
     (when (nil? compiler)
       (throw (ex-info "No system Java compiler available. Run the target JVM from a JDK, not a JRE."
                       {:class-name class-name})))
     (let [diagnostics (DiagnosticCollector.)
           standard    (.getStandardFileManager compiler diagnostics nil nil)
           class-bytes (atom {})
           fm          (memory-file-manager standard class-bytes)
           options     (cond-> []
                         :always (conj "-parameters")
                         (not (str/blank? (or classpath (default-classpath))))
                         (into ["-classpath" (or classpath (default-classpath))]))
           task        (.getTask compiler nil fm diagnostics options nil
                                 [(source-object class-name source)])
           ok?         (.call task)]
       {:ok?         (boolean ok?)
        :classes     @class-bytes
        :diagnostics (mapv str (.getDiagnostics diagnostics))}))))

;;; ---------------------------------------------------------------------------
;;; loading new classes

(defonce ^:private loader* (atom nil))

(defn- new-loader!
  "Fresh DynamicClassLoader parented to the app's classloader. Each
   compile-and-load! gets its own loader, so recompiling the same name
   shadows the previous definition instead of failing."
  []
  (reset! loader*
          (DynamicClassLoader. (effective-classloader))))

(defn load-class!
  "Define a class from bytecode in the current dynamic classloader."
  ^Class [^String class-name ^bytes bytes]
  (.defineClass ^DynamicClassLoader (or @loader* (new-loader!)) class-name bytes nil))

(defn compile-and-load!
  "Compile `source` and load every generated class (including inner classes)
   into a FRESH dynamic classloader whose parent is the app's classloader.

   Because each call uses a new loader, calling this again with the same class
   name shadows the previous definition — the returned :class is the new one.
   Classes already loaded elsewhere are unaffected; use the returned Class
   (e.g. via clojure.lang.Reflector or .getMethod/.newInstance) to reach the
   new definition.

   Returns {:ok? bool :class Class :loaded [names] :diagnostics [strings]}."
  ([class-name source] (compile-and-load! class-name source {}))
  ([class-name source opts]
   (let [{:keys [ok? classes diagnostics]} (compile-java class-name source opts)]
     (if-not ok?
       {:ok? false :diagnostics diagnostics}
       (let [loader  (new-loader!)
             defined (mapv (fn [[n b]] (.defineClass ^DynamicClassLoader loader ^String n ^bytes b nil))
                           classes)]
         {:ok?         true
          :class       (some #(when (= class-name (.getName ^Class %)) %) defined)
          :loaded      (mapv #(.getName ^Class %) defined)
          :diagnostics diagnostics})))))

;;; ---------------------------------------------------------------------------
;;; redefining already-loaded classes (JVM HotSwap)

(defn instrumentation
  "The Instrumentation handle captured by the -javaagent. Throws when the JVM
   was started without the agent (e.g. nrepl started manually via R/start)."
  ^Instrumentation []
  (or repl.agent.NreplAgent/instrumentation
      (throw (ex-info "Instrumentation unavailable: start the target JVM with -javaagent:<nrepl-starter-agent.jar>"
                      {}))))

(defn find-loaded-classes
  "All loaded Class objects with this binary name (one per defining classloader)."
  [class-name]
  (filterv #(= class-name (.getName ^Class %))
           (.getAllLoadedClasses (instrumentation))))

(defn redefine!
  "Compile `source` and redefine the already-loaded class `class-name` via
   Instrumentation/redefineClasses (JVM HotSwap).

   Only schema-preserving changes are allowed: method bodies, constants,
   annotations. Adding/removing methods or fields, or changing signatures, is
   rejected by the JVM — use compile-and-load! with a fresh class instead.

   When the class was loaded by multiple classloaders, all are redefined.
   Returns {:ok? bool :redefined n :diagnostics [strings]}."
  ([class-name source] (redefine! class-name source {}))
  ([class-name source opts]
   (let [{:keys [ok? classes diagnostics]} (compile-java class-name source opts)]
     (if-not ok?
       {:ok? false :diagnostics diagnostics}
       (let [targets (find-loaded-classes class-name)]
         (if (empty? targets)
           {:ok?         false
            :error       (str "class " class-name " is not loaded in any classloader")
            :diagnostics diagnostics}
           (let [bytes (get classes class-name)
                 defs  (into-array ClassDefinition
                                   (map #(ClassDefinition. ^Class % bytes) targets))]
             (try
               (.redefineClasses (instrumentation) defs)
               {:ok? true :redefined (count targets) :diagnostics diagnostics}
               (catch Throwable t
                 {:ok?         false
                  :error       (str (.getSimpleName (class t)) ": " (.getMessage t))
                  :hint        "JVM HotSwap only allows schema-preserving changes (method bodies). For new methods/fields use compile-and-load! with a fresh class."
                  :diagnostics diagnostics})))))))))

;;; ---------------------------------------------------------------------------
;;; file conveniences — point these at edited .java files in your project

(defn compile-and-load-file!
  "Like compile-and-load! but reads the source from `path`."
  ([class-name path] (compile-and-load-file! class-name path {}))
  ([class-name path opts] (compile-and-load! class-name (slurp path) opts)))

(defn redefine-file!
  "Like redefine! but reads the source from `path` — point it at the edited
   .java file in your project to hot-swap the running class."
  ([class-name path] (redefine-file! class-name path {}))
  ([class-name path opts] (redefine! class-name (slurp path) opts)))

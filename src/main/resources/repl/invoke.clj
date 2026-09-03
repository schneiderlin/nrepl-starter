(ns repl.invoke
  "Invoke methods and constructors of any class loaded in the target JVM —
   the engine behind the dashboard's method-invocation UI, usable directly
   from nrepl too.

     (repl.invoke/list-methods \"com.example.App\")
     (repl.invoke/invoke! {:class-name \"com.example.App\" :sel \"m-0\"
                           :args-text \"3\\n4\"})
     (repl.invoke/shelf-view)

   Arguments are given as EDN text (one value per line) and coerced to the
   declared parameter types: primitives, strings, enums, and EDN maps ->
   Java beans (no-arg constructor + setters). Non-nil results are stored on
   the object shelf; instance methods take their target from the shelf."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [repl.tools :as tools])
  (:import [java.lang.reflect Array Constructor InvocationTargetException Method Modifier]))

;;; ---------------------------------------------------------------------------
;;; object shelf — instances created/obtained at runtime, targets for
;;; instance-method calls

(defonce !shelf (atom {:counter 0 :objects {}}))

;; serializable view for UIs (shelf objects themselves are not serializable)
(defonce !shelf-view (atom []))

(defn- abbrev [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn store!
  "Put an object on the shelf. Returns {:id :class :label}."
  [obj]
  (let [state (swap! !shelf (fn [s]
                              (let [n (:counter s)]
                                {:counter (inc n) :objects (assoc (:objects s) n obj)})))
        id   (dec (:counter state))
        info {:id    (str id)
              :class (.getName (class obj))
              :label (str "#" id " " (abbrev (try (str obj) (catch Throwable _ "<toString failed>")) 80))}]
    (swap! !shelf-view conj info)
    info))

(defn shelf-view [] @!shelf-view)

(defn shelf-get [id]
  (get-in @!shelf [:objects (parse-long (str id))]))

(defn shelf-instances
  "Shelf entries assignable to `class-name` — candidate targets for instance methods."
  [class-name]
  (when-let [^Class cls (first (tools/find-loaded-classes class-name))]
    (filterv (fn [info]
               (if-let [obj (shelf-get (:id info))]
                 (instance? cls obj)
                 false))
             @!shelf-view)))

;;; ---------------------------------------------------------------------------
;;; reflection metadata

(defn- type-name [^Class c] (.getName c))

(defn list-methods
  "Public methods (incl. inherited) and public constructors of a loaded class.
   Each entry has a stable :key (\"m-<index>\" / \"c-<index>\") used by invoke!."
  [class-name]
  (when-let [^Class cls (first (tools/find-loaded-classes class-name))]
    {:class   class-name
     :loaders (count (tools/find-loaded-classes class-name))
     :entries (into (mapv (fn [i ^Constructor c]
                            {:key         (str "c-" i)
                             :kind        :ctor
                             :param-types (mapv type-name (.getParameterTypes c))
                             :label       (str "new " (.getSimpleName cls)
                                               " (" (str/join ", " (map type-name (.getParameterTypes c))) ")")})
                          (range) (.getConstructors cls))
                    (mapv (fn [i ^Method m]
                            {:key         (str "m-" i)
                             :kind        :method
                             :static?     (Modifier/isStatic (.getModifiers m))
                             :param-types (mapv type-name (.getParameterTypes m))
                             :label       (str (when (Modifier/isStatic (.getModifiers m)) "static ")
                                               (.getName m)
                                               " (" (str/join ", " (map type-name (.getParameterTypes m))) ")"
                                               " : " (type-name (.getReturnType m)))})
                          (range) (.getMethods cls)))}))

;;; ---------------------------------------------------------------------------
;;; argument parsing + coercion (EDN text -> Java values)

(def ^:private primitive-types
  {"boolean" Boolean/TYPE, "byte" Byte/TYPE, "char" Character/TYPE
   "short" Short/TYPE, "int" Integer/TYPE, "long" Long/TYPE
   "float" Float/TYPE, "double" Double/TYPE, "void" Void/TYPE})

(defn- class-for
  "Resolve a parameter type name; app classes are resolved against the class's
   own classloader, array names like [Ljava.lang.String; work via forName."
  [^String name ^Class context]
  (or (primitive-types name)
      (try (Class/forName name)
           (catch Throwable _
             (Class/forName name true (.getClassLoader context))))))

(defn coerce
  "Coerce an EDN value to a Java value of `type`."
  [^Class type value]
  (cond
    (nil? value)
    (if (.isPrimitive type)
      (throw (ex-info (str "nil is not allowed for primitive " type) {}))
      nil)

    ;; already the right object (e.g. shelf reference passed through)
    (and (not (.isPrimitive type)) (instance? type value)) value

    (= type String) (if (string? value) value (pr-str value))
    (or (= type Boolean/TYPE) (= type Boolean)) (boolean value)
    (or (= type Integer/TYPE) (= type Integer)) (int value)
    (or (= type Long/TYPE) (= type Long)) (long value)
    (or (= type Double/TYPE) (= type Double)) (double value)
    (or (= type Float/TYPE) (= type Float)) (float value)
    (or (= type Short/TYPE) (= type Short)) (short value)
    (or (= type Byte/TYPE) (= type Byte)) (byte value)
    (or (= type Character/TYPE) (= type Character)) (if (char? value) value (first (str value)))
    (.isEnum type) (Enum/valueOf ^Class type (name value))

    ;; EDN map -> Java bean via no-arg constructor + setters
    (map? value)
    (let [obj (.newInstance ^Constructor (.getDeclaredConstructor type (into-array Class []))
                            (object-array []))]
      (doseq [[k v] value]
        (let [setter-name (str "set" (str/capitalize (name k)))
              setter (some (fn [^Method m]
                             (when (and (= setter-name (.getName m))
                                        (= 1 (count (.getParameterTypes m))))
                               m))
                           (.getMethods type))]
          (when setter
            (.invoke setter obj
                     (object-array [(coerce (aget (.getParameterTypes setter) 0) v)])))))
      obj)

    :else (throw (ex-info (str "cannot coerce " (pr-str value) " to " (.getName type))
                          {:value value :type (.getName type)}))))

(defn parse-args
  "Parse `args-text` (one EDN value per line) and coerce to `param-types`."
  [args-text param-types ^Class context]
  (let [values (->> (str/split-lines (or args-text ""))
                    (map str/trim)
                    (remove str/blank?)
                    (mapv edn/read-string))]
    (when (not= (count values) (count param-types))
      (throw (ex-info (str "expected " (count param-types) " arg(s), one EDN value per line, got "
                           (count values))
                      {:expected param-types})))
    (mapv (fn [t v] (coerce (class-for t context) v)) param-types values)))

;;; ---------------------------------------------------------------------------
;;; result serialization (arbitrary Java objects -> bounded EDN data)

(defn ->safe
  "Convert an invocation result to bounded EDN-able data."
  ([x] (->safe x 4))
  ([x depth]
   (cond
    (nil? x) nil
    (or (string? x) (number? x) (boolean? x) (keyword? x) (symbol? x) (char? x)) x
    (<= depth 0) (abbrev (str x) 200)
    (instance? java.util.Map x)
    (into {} (map (fn [[k v]] [(->safe k (dec depth)) (->safe v (dec depth))])) (take 100 x))
    (.isArray (class x))
    (mapv #(->safe (Array/get x %) (dec depth)) (range (min 100 (Array/getLength x))))
    (instance? java.util.Collection x)
    (mapv #(->safe % (dec depth)) (take 100 x))
    :else (str {:class (.getName (class x))
                :string (abbrev (try (str x) (catch Throwable _ "<toString failed>")) 200)}))))

;;; ---------------------------------------------------------------------------
;;; invocation

(defn find-entry [class-name sel]
  (some #(when (= (:key %) sel) %) (:entries (list-methods class-name))))

(defn invoke!
  "Invoke a method or constructor.
   {:class-name \"com.example.App\"
    :sel        \"m-0\" | \"c-0\"        ; entry key from list-methods
    :args-text  \"3\\n4\"                  ; one EDN value per line
    :target-id  \"0\"}                     ; shelf id, instance methods only
   Returns {:ok? bool :result <safe edn> :shelf {:id ...} :error str}."
  [{:keys [class-name sel args-text target-id]}]
  (try
    (let [^Class cls (or (first (tools/find-loaded-classes class-name))
                         (throw (ex-info (str "class not loaded: " class-name) {})))
          entry (or (find-entry class-name sel)
                    (throw (ex-info (str "no entry " sel " on " class-name) {})))]
      (if (= :ctor (:kind entry))
        (let [^Constructor c (nth (.getConstructors cls) (parse-long (subs sel 2)))
              args (parse-args args-text (:param-types entry) cls)
              obj  (.newInstance c (object-array args))]
          {:ok? true :result (->safe obj) :shelf (store! obj)})
        (let [^Method m (nth (.getMethods cls) (parse-long (subs sel 2)))
              target (when-not (:static? entry)
                       (or (some-> target-id shelf-get)
                           (throw (ex-info "instance method needs a target object — construct one first or pick one from the shelf"
                                           {}))))
              args   (parse-args args-text (:param-types entry) cls)
              result (try (.invoke m target (object-array args))
                          (catch InvocationTargetException e
                            (throw (or (.getCause e) e))))]
          {:ok?   true
           :result (->safe result)
           :shelf  (when (and (some? result)
                              (not (or (number? result) (string? result) (boolean? result))))
                     (store! result))})))
    (catch Throwable t
      {:ok? false :error (str (.getSimpleName (class t)) ": " (ex-message t))})))

(ns dashboard.main
  "Electric v3 dashboard UI for nrepl-starter. The server side of this program
   runs inside the target JVM (see dashboard.prod); data comes from
   repl.inspect via repl.dashboard-server.

   Electric v3 notes that shaped this code:
   - `try` is not supported inside electric code — errors are caught in
     server-side helpers (repl.dashboard-server)
   - e/for collections must be wrapped in e/diff-by to become stable tables"
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            #?@(:clj [[clojure.string :as str]
                      [repl.dashboard-server :as srv]
                      [repl.inspect :as inspect]
                      [repl.invoke :as inv]])))

;;; ---------------------------------------------------------------------------
;;; pieces

(e/defn Header [snap]
  (e/client
    (dom/div (dom/props {:class "header"})
      (dom/span (dom/props {:class "title"}) (dom/text "nrepl dashboard"))
      (dom/span (dom/text (get-in snap [:vm :name]) " " (get-in snap [:vm :version])))
      (dom/span (dom/text "uptime " (e/server (srv/fmt-uptime (:uptime-ms snap)))))
      (dom/span (dom/text "cpu " (or (e/server (srv/fmt-load (get-in snap [:system :process-cpu-load]))) "—")))
      (dom/span (dom/text "threads " (:count (:threads snap))))
      (dom/span (dom/text "classes " (e/server (str (get-in snap [:classes :loaded-count]))))))))

(e/defn Bar [label usage]
  (e/client
    (let [{:keys [used max]} usage
          bounded? (and (number? used) (number? max) (pos? max))
          pct (if bounded? (min 100 (long (* 100.0 (/ used max)))) 0)]
      (dom/div (dom/props {:class "bar-row"})
        (dom/span (dom/props {:class "bar-label mono"}) (dom/text label))
        (dom/div (dom/props {:class "bar-track"})
          (dom/div (dom/props {:class "bar-fill"
                               :style {:width (str pct "%")}})))
        (dom/span (dom/props {:class "bar-nums mono"})
          (if bounded?
            (dom/text (e/server (srv/fmt-bytes used)) " / "
                      (e/server (srv/fmt-bytes max)) " (" pct "%)")
            (dom/text (e/server (srv/fmt-bytes used)))))))))

(e/defn MemoryCard [memory]
  (e/client
    (dom/div (dom/props {:class "card"})
      (dom/h2 (dom/text "memory"))
      (Bar "heap" (:heap memory))
      (Bar "non-heap" (:non-heap memory))
      (e/for [pool (e/diff-by :name (:pools memory))]
        (Bar (:name pool) (:usage pool))))))

(e/defn GcCard [gc]
  (e/client
    (dom/div (dom/props {:class "card"})
      (dom/h2 (dom/text "gc"))
      (e/for [g (e/diff-by :name gc)]
        (dom/div (dom/props {:class "mono"})
          (dom/text (:name g) " — " (:collection-count g) " collections, "
                    (:collection-time-ms g) " ms"))))))

(e/defn ClassSearch []
  (e/client
    (dom/div (dom/props {:class "card"})
      (dom/h2 (dom/text "loaded classes"))
      (let [q (dom/input (dom/props {:class "mono" :placeholder "regex, e.g. ^com\\.example"})
                (dom/On "input" (fn [e] (.. e -target -value)) ""))]
        (when (e/server (not (str/blank? q)))
          (let [results (e/server (srv/search-classes-safe q 50))]
            (if-let [err (:err results)]
              (dom/div (dom/props {:class "err"}) (dom/text err))
              (let [rs (:ok results)]
                (dom/div (dom/text (count rs) " shown (max 50)"))
                (e/for [r (e/diff-by identity rs)]
                  (dom/div (dom/props {:class "mono result"}) (dom/text r)))))))))))

(e/defn EvalConsole []
  (e/client
    (dom/div (dom/props {:class "card"})
      (dom/h2 (dom/text "eval (user ns)"))
      (let [code (dom/textarea (dom/props {:class "mono" :rows 6
                                           :placeholder "(+ 1 2), (repl.inspect/snapshot), ..."})
                   (dom/On "input" (fn [e] (.. e -target -value)) ""))
            submitted (dom/button (dom/text "run")
                        (dom/On "click" (fn [_] code) nil))]
        (when (some? submitted)
          (let [res (e/server (srv/eval-string submitted))]
            (dom/pre (dom/props {:class (if (:ok? res) "mono result" "mono err")})
              (dom/text (if (:ok? res) (:value res) (:error res))))))))))

;;; ---------------------------------------------------------------------------
;;; method invocation (browser anydoor)

(e/defn InvokeResult [req]
  (e/client
    (let [res (e/server (srv/invoke-safe req))]
      (if (:ok? res)
        (dom/div
          (dom/pre (dom/props {:class "mono result"})
            (dom/text (pr-str (:result res))))
          (when-some [s (:shelf res)]
            (dom/div (dom/props {:class "mono"})
              (dom/text "stored on shelf: " (:label s)))))
        (dom/pre (dom/props {:class "mono err"})
          (dom/text (:error res)))))))

(e/defn MethodForm [class-name sel]
  (e/client
    (let [entry (e/server (inv/find-entry class-name sel))]
      (when (some? entry)
        (dom/div
          (dom/div (dom/props {:class "mono hint"})
            (dom/text (if (seq (:param-types entry))
                        (str "args — one EDN value per line: " (pr-str (:param-types entry)))
                        "no args")))
          (let [args-text (dom/textarea (dom/props {:class "mono" :rows "4"
                                                    :placeholder "one EDN value per line"})
                            (dom/On "input" (fn [e] (.. e -target -value)) ""))
                target-id (when (and (= :method (:kind entry)) (not (:static? entry)))
                            (dom/select (dom/props {:class "mono"})
                              (dom/option (dom/props {:value ""})
                                (dom/text "-- target instance (from shelf) --"))
                              (e/for [t (e/diff-by :id (or (e/server (:ok (srv/shelf-instances-safe class-name))) []))]
                                (dom/option (dom/props {:value (:id t)}) (dom/text (:label t))))
                              (dom/On "change" (fn [e] (.. e -target -value)) "")))
                submitted (dom/button (dom/text "call")
                            (dom/On "click" (fn [_] {:class-name class-name
                                                     :sel        sel
                                                     :args-text  args-text
                                                     :target-id  target-id})
                                    nil))]
            (when (some? submitted)
              (InvokeResult submitted))))))))

(e/defn MethodInvoker []
  (e/client
    (dom/div (dom/props {:class "card"})
      (dom/h2 (dom/text "invoke method"))
      (let [q (dom/input (dom/props {:class "mono" :placeholder "class regex — e.g. ^com\\.example"})
                (dom/On "input" (fn [e] (.. e -target -value)) ""))]
        (when (e/server (not (str/blank? q)))
          (let [found (e/server (:ok (srv/search-classes-safe q 50)))
                class-name (dom/select (dom/props {:class "mono"})
                             (dom/option (dom/props {:value ""}) (dom/text "-- class --"))
                             (e/for [c (e/diff-by identity (or found []))]
                               (dom/option (dom/props {:value c}) (dom/text c)))
                             (dom/On "change" (fn [e] (.. e -target -value)) ""))]
            (when (e/server (not (str/blank? class-name)))
              (let [info (e/server (:ok (srv/list-methods-safe class-name)))
                    sel (dom/select (dom/props {:class "mono"})
                          (dom/option (dom/props {:value ""}) (dom/text "-- method / constructor --"))
                          (e/for [m (e/diff-by :key (:entries info))]
                            (dom/option (dom/props {:value (:key m)}) (dom/text (:label m))))
                          (dom/On "change" (fn [e] (.. e -target -value)) ""))]
                (when (e/server (not (str/blank? sel)))
                  (MethodForm class-name sel))))))))))

;;; ---------------------------------------------------------------------------
;;; entrypoints

(e/defn Main [ring-request]
  (e/client
    (binding [dom/node js/document.body]
      (dom/div (dom/props {:class "page"}) ; mandatory wrapper div (electric#74)
        (when-some [snap (e/server (e/watch srv/!snapshot))]
          (if (:error snap)
            (dom/div (dom/props {:class "err"}) (dom/text "snapshot error: " (:error snap)))
            (do (Header snap)
                (MemoryCard (:memory snap))
                (GcCard (:gc snap)))))
        (ClassSearch)
        (MethodInvoker)
        (EvalConsole)))))

(defn electric-boot [ring-request]
  #?(:clj  (e/boot-server {} Main (e/server ring-request))
     :cljs (e/boot-client {} Main (e/server (e/amb)))))

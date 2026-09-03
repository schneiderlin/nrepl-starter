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
                      [repl.inspect :as inspect]])))

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
        (EvalConsole)))))

(defn electric-boot [ring-request]
  #?(:clj  (e/boot-server {} Main (e/server ring-request))
     :cljs (e/boot-client {} Main (e/server (e/amb)))))

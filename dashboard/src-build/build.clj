(ns build
  "Builds the dashboard CLJS client into the main project's resources.
   Invoke from this directory:  clj -X:build build-client
   (NOT -T: Electric shadow compilation requires the application classpath.)"
  (:require [clojure.tools.build.api :as b]
            [shadow.cljs.devtools.api :as shadow-api]
            [shadow.cljs.devtools.server :as shadow-server]))

(def resources-dir "../src/main/resources")

(def electric-user-version
  (or (b/git-process {:git-args "describe --tags --long --always --dirty"
                      :dir ".."})
      (str (System/currentTimeMillis))))

(defn build-client
  [{:keys [version] :or {version electric-user-version} :as config}]
  (b/delete {:path (str resources-dir "/public/nrepl_dashboard/js")})
  (b/delete {:path (str resources-dir "/electric-manifest.edn")})

  ;; bake the client/server version into both the js (closure-define) and the
  ;; manifest read by the server (wrap-reject-stale-client)
  (b/write-file {:path (str resources-dir "/electric-manifest.edn")
                 :content {:hyperfiddle/electric-user-version version}})

  (shadow-server/start!)
  (let [status (shadow-api/release :prod
                 {:config-merge [{:compiler-options {:optimizations :advanced}
                                  :closure-defines {'hyperfiddle.electric-client3/ELECTRIC_USER_VERSION version}}]})]
    (shadow-server/stop!)
    (assert (= :done status) "shadow-api/release failed"))
  (println "client built into" (str resources-dir "/public/nrepl_dashboard/js")))

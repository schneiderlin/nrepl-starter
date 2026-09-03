(ns repl.dashboard
  "Loader for the embedded Electric dashboard server.

  Started by nrepl-starter at JVM startup when -Dnrepl.dashboard.enabled=true
  (default). Configuration via system properties:
    nrepl.dashboard.enabled  (default true)
    nrepl.dashboard.host     (default 127.0.0.1 — loopback only)
    nrepl.dashboard.port     (default 7890)

  The dashboard assets (compiled CLJS) are built ahead of time:
    cd dashboard && clj -X:build build-client
  then rebuild the agent jar. If the assets are missing, the dashboard is
  skipped with a log line and nrepl keeps working."
  (:require [clojure.java.io :as io]))

(defn assets-built? []
  (and (io/resource "electric-manifest.edn")
       (io/resource "public/nrepl_dashboard/js/manifest.edn")))

(defn start!
  "Start the dashboard server (Electric v3 over ring/jetty, websocket).
   Returns the Jetty server object, or nil when skipped."
  ([] (start! {}))
  ([{:keys [host port]
     :or   {host (System/getProperty "nrepl.dashboard.host" "127.0.0.1")
            port (Long/parseLong (System/getProperty "nrepl.dashboard.port" "7890"))}}]
   (if-not (assets-built?)
     (do (println "[nrepl-starter] dashboard assets not built — run `cd dashboard && clj -X:build build-client` and rebuild the jar; dashboard disabled")
         nil)
     (do (require 'dashboard.prod)
         (let [server ((resolve 'dashboard.prod/start!) {:host host :port port})]
           (println (str "[nrepl-starter] dashboard on http://" host ":" port))
           server)))))

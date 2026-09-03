(ns dashboard.prod
  "Prod server + client entry for the nrepl-starter dashboard.
   Adapted from hyperfiddle/electric3-starter-app (src-prod/prod.cljc).
   The server side runs inside the target JVM, loaded from source resources."
  #?(:cljs (:require-macros [dashboard.prod :refer [comptime-resource]]))
  (:require [dashboard.main :as main]
            #?@(:clj [[clojure.edn]
                      [clojure.java.io]
                      [clojure.string]
                      [ring.adapter.jetty :as ring]
                      [ring.middleware.content-type :refer [wrap-content-type]]
                      [ring.middleware.not-modified :refer [wrap-not-modified]]
                      [ring.middleware.params :refer [wrap-params]]
                      [ring.middleware.resource :refer [wrap-resource]]
                      [ring.util.response :as ring-response]
                      [repl.dashboard-server :as srv]
                      [hyperfiddle.electric-ring-adapter3 :as electric-ring]])
            #?(:cljs [hyperfiddle.electric-client3 :as electric-client])))

(defmacro comptime-resource [filename]
  (some-> filename clojure.java.io/resource slurp clojure.edn/read-string))

#?(:clj
   (defn template
     "In string template `t`, replace all instances of $key$ with the value in map `m`."
     [t m]
     (reduce-kv (fn [acc k v] (clojure.string/replace acc (str "$" k "$") (str v))) t m)))

#?(:clj
   (defn get-compiled-javascript-modules [manifest-path]
     (when-let [manifest (clojure.java.io/resource manifest-path)]
       (let [manifest-folder (when-let [folder-name (second (rseq (clojure.string/split manifest-path #"/")))]
                               (str folder-name "/"))]
         (->> (slurp manifest)
              (clojure.edn/read-string)
              (reduce (fn [r module]
                        (assoc r (keyword "hyperfiddle.client.module" (name (:name module)))
                                 (str manifest-folder (:output-name module))))
                      {}))))))

#?(:clj
   (defn wrap-prod-index-page
     "Serves public/nrepl_dashboard/index.html at / and /index.html with the
      fingerprinted js module paths injected from the shadow build manifest."
     [next-handler config]
     (fn [ring-req]
       (if (#{"/" "/index.html"} (:uri ring-req))
         (let [response (ring-response/resource-response
                          (str (:resources-path config) "/nrepl_dashboard/index.html"))]
           (assert response "index.html missing on classpath")
           (if-let [module (get-compiled-javascript-modules (:manifest-path config))]
             (-> (ring-response/response (template (slurp (:body response)) (merge config module)))
                 (ring-response/content-type "text/html")
                 (ring-response/header "Cache-Control" "no-store"))
             (-> (ring-response/not-found (pr-str ::missing-shadow-build-manifest))
                 (ring-response/content-type "text/plain"))))
         (next-handler ring-req)))))

#?(:clj
   (defn wrap-ensure-cache-bust [next-handler]
     (fn [ring-req]
       (-> (next-handler ring-req)
           (ring-response/update-header "Cache-Control"
                                        (fn [cc] (or cc "public, max-age=0, must-revalidate")))))))

#?(:clj
   (defn start!
     "Start the dashboard server. Returns the Jetty server (call .stop to stop)."
     [{:keys [host port]}]
     (let [config (merge (comptime-resource "electric-manifest.edn")
                         {:host host :port port
                          :resources-path "public"
                          :manifest-path "public/nrepl_dashboard/js/manifest.edn"})]
       (assert (string? (:hyperfiddle/electric-user-version config))
               "electric-manifest.edn missing — build the client first (cd dashboard && clj -X:build build-client)")
       @srv/start-poller!
       (ring/run-jetty
         (-> (fn [_] (-> (ring-response/not-found "not found")
                         (ring-response/content-type "text/plain")))
             (wrap-prod-index-page config)
             (wrap-resource (:resources-path config))
             (wrap-content-type)
             (wrap-not-modified)
             (wrap-ensure-cache-bust)
             (electric-ring/wrap-electric-websocket
               (fn [ring-req] (main/electric-boot ring-req)))
             (electric-ring/wrap-reject-stale-client config)
             (wrap-params))
         {:host host :port port :join? false
          :ws-idle-timeout (* 10 60 1000)
          :ws-max-text-size (* 100 1024 1024)}))))

#?(:cljs
   (defn ^:export -main []
     ((electric-client/reload-when-stale (main/electric-boot nil)))))

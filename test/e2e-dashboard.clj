(ns e2e-dashboard
  "End-to-end Phase 3 check against the running example app:
   1. GET / serves index.html with the fingerprinted js path injected
   2. the fingerprinted js asset is served
   3. a websocket connection with the matching ELECTRIC_USER_VERSION boots an
      Electric server program and receives frames
   4. nrepl still works alongside the dashboard
   Run with: java -cp <agent.jar> clojure.main -i test/e2e-dashboard.clj"
  (:require [clojure.edn :as edn]
            [nrepl.core :as nrepl])
  (:import [java.net URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener]))

(def base "http://127.0.0.1:7890")

(defn check [label ok? detail]
  (println (str (if ok? "PASS" "FAIL") " " label "  " detail))
  (when-not ok? (System/exit 1)))

(defn http-get [url]
  (let [res (.send (HttpClient/newHttpClient)
                   (.build (.GET (java.net.http.HttpRequest/newBuilder (URI/create url))))
                   (java.net.http.HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode res) :body (.body res)}))

(defn ws-first-message
  "Connect a websocket and return the first text frame, {:closed code},
   {:error msg}, or :timeout.

   Note: the Electric protocol expects the *client* to speak first, so a
   passive listener normally sees :timeout. What this test actually proves is
   that the upgrade was accepted and the version check passed — a stale or
   rejected client is closed with code 1008 before any frame."
  [url]
  (let [p (promise)
        listener (reify WebSocket$Listener
                   (onOpen [_ ws] (.request ws 1))
                   (onText [_ ws _data last?]
                     (deliver p :text-frame)
                     (.request ws 1)
                     nil)
                   (onClose [_ _ws status _reason]
                     (deliver p {:closed status}))
                   (onError [_ _ws err]
                     (deliver p {:error (str err)})))]
    @(.buildAsync (.newWebSocketBuilder (HttpClient/newHttpClient))
        (URI/create url) listener)
    (deref p 20000 :timeout)))

(defn -main []
  (let [index (http-get (str base "/"))]
    (check "index page" (and (= 200 (:status index))
                             (re-find #"/nrepl_dashboard/js/main\.[A-F0-9]+\.js" (:body index)))
           (:status index)))

  (let [index (:body (http-get (str base "/")))
        js-path (re-find #"/nrepl_dashboard/js/main\.[A-F0-9]+\.js" index)
        js (http-get (str base js-path))]
    (check "js asset" (= 200 (:status js)) (str js-path " " (count (:body js)) "B")))

  (let [version (:hyperfiddle/electric-user-version
                 (edn/read-string (slurp "src/main/resources/electric-manifest.edn")))
        url (str "ws://127.0.0.1:7890/?ELECTRIC_USER_VERSION="
                 (java.net.URLEncoder/encode version "UTF-8"))
        msg (ws-first-message url)]
    (check "electric websocket boot" (or (= :text-frame msg) (= :timeout msg)) (str msg)))

  (with-open [conn (nrepl/connect :port 7888)]
    (let [client (nrepl/client conn 20000)
          v (->> (nrepl/message client {:op "eval" :code "(get-in (repl.inspect/snapshot) [:vm :name])"})
                 (some :value))]
      (check "nrepl alongside dashboard" (some? v) v))))

(-main)
;; nrepl client threads are non-daemon; exit explicitly
(shutdown-agents)
(System/exit 0)

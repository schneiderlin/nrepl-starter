(ns repl.core
  (:import [com.example App]))

(comment
  ;; calling static methods
  (App/add 1 2)

  ;; create instance
  (def app (App.))
  ;; calling instance methods
  (.subtract app 1 2)
  :rcf)
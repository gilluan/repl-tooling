(ns repl-tooling.repl-client.clojure-test
  (:require [clojure.test :refer-macros [testing run-tests]]
            [check.core :refer-macros [check]]
            [check.async :refer [def-async-test await!] :include-macros true]
            [clojure.core.async :as async :include-macros true]
            [repl-tooling.repl-client :as client]
            [repl-tooling.eval :as eval]
            [repl-tooling.repl-client.clojure :as clj]))

(def-async-test "Evaluate a request-response test"
  {:teardown (client/disconnect! :clj-test1)}
  (let [out (async/chan)
        repl (clj/repl :clj-test1 "localhost" 2233 #(some->> % (async/put! out)))]
    (testing "evaluating request-response"
      (eval/evaluate repl "(+ 1 2)" {} #(some->> % (async/put! out)))
      (check (await! out) =includes=> {:result "3"})
      (check (await! out) =includes=> {:result "3" :as-text "3"}))

    (testing "capturing output"
      (eval/evaluate repl "(println :foobar)" {} #(some->> % (async/put! out)))
      (check (await! out) => {:out ":foobar\n"})
      (check (await! out) =includes=> {:result "nil"})
      (check (await! out) => {:result "nil" :as-text "nil"}))

    (testing "passing args to result"
      (let [res (async/promise-chan)]
        (eval/evaluate repl "(+ 2 3)" {:pass {:literal true}} #(some->> % (async/put! res)))
        (check (await! res) =includes=> {:result "5" :literal true}))
      (check (await! out) =includes=> {:result "5" :literal true}))

    (testing "passing parameters to evaluation"
      ; FIXME: We need better integration when we stack commands
      (await! (async/timeout 200))
      (let [res (async/promise-chan)]
        (eval/evaluate repl "(/ 10 0)" {:filename "foo.clj" :row 12 :col 0}
                       #(async/put! res %))
        (check (:error (await! res)) => #"foo\.clj\" 12")))))

#_
(def-async-test "Captures specific UnREPL outputs"
  {:teardown (client/disconnect! :clj-test3)}
  (let [out (async/chan)
        repl (clj/repl :clj-test3 "localhost" 2233 identity)
        res #(async/put! out %)]

    (testing "capturing JAVA classes"
      (eval/evaluate repl "Throwable" {} res)
      (check (await! out) =includes=> {:result "java.lang.Throwable"}))

    (testing "capturing records"
      (eval/evaluate repl "(do (defrecord Foo []) (->Foo))" {} res)
      (let [r (await! out)]
        (check r =includes=> {:result "{}" :as-text {}})
        (check (-> r :as-text meta :tag) => "#user.Foo")))

    (testing "capturing exceptions"
      (eval/evaluate repl "(/ 20 0)" {} res)
      (check (:error (await! out)) => #"Divide by zero"))

    (testing "capturing big data"
      (eval/evaluate repl "(range)" {} res)
      (let [r (await! out)]
        (check (:result r) => #"(0 1 2 3 4.*)")
        (check (:as-text r) => '("0" "1" "2" "3" "4" "5" "6" "7" "8" "9" ...))

        (eval/evaluate repl (-> r :as-text last meta :get-more) {} res)
        (check (:result (await! out)) => #"(10 11 12 13 14.*)")))

    (testing "capturing big strings"
      (eval/evaluate repl "(str (range 500))" {} res)
      (let [r (await! out)]
        (check (:result r) => #"(0 1 2 3 4.*)")
        (check (pr-str (:as-text r)) => #"\(0 1 2 3 4 5.*\.\.\.")

        (eval/evaluate repl (-> r :as-text meta :get-more) {} res)
        (check (pr-str (:as-text (await! out))) => #"^\s?\d+.*\.\.\.")))))
;
; (run-tests)

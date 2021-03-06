(ns repl-tooling.repl-client.lumo
  (:require [repl-tooling.repl-client.protocols :as repl]
            [repl-tooling.repl-client :as client]
            [cljs.core.async :as async :refer-macros [go go-loop]]
            [cljs.reader :as reader]
            [repl-tooling.eval :as eval]
            [clojure.string :as str]))

(defn- code-to-lumo [identifier code]
  (let [reader (str (gensym) "reader" (gensym))
        result (str (gensym) "result" (gensym))]
    (str "(let [" reader " (goog.string/StringBuffer.)]
            (binding [cljs.core/*print-newline* true
                      cljs.core/*print-fn* (fn [x] (.append " reader " x))]
        (let [" result " (cljs.core/eval '" code "\n)]
          ['" identifier "
           (cljs.core/str " reader ")
           (cljs.core/str " result ")])))")))

(defrecord Lumo [pending-cmds]
  repl/Repl
  (cmd-to-send [_ command]
    (let [[id cmd] (if (str/starts-with? command "[")
                     (reader/read-string command)
                     [(gensym) command])
          command (code-to-lumo id cmd)]
      (swap! pending-cmds conj (str id))
      command)))

(defn- treat-output [pending-cmds out]
  (let [[_ match] (re-find #"^\s*\[(.+?) " out)]
    (if (@pending-cmds match)
      (let [[_ out result] (reader/read-string out)]
        (swap! pending-cmds disj match)
        {:id match :out out :result result})
      {:out out})))

(defn connect-socket! [session-name host port]
  (let [[in out] (client/socket! session-name host port)
        pending-cmds (atom #{})
        repl (->Lumo pending-cmds)
        [in out] (client/integrate-repl in out repl)
        new-out (async/map #(treat-output pending-cmds %) [out])]
    (async/put! in '(require 'lumo.repl))
    (async/put! in "(set! lumo.repl/*pprint-results* false)")

    [in new-out]))

;;;;;;;;;;;;;; CUT HERE ;;;;;;;;;;;;;;;;;;
(defn evaluate-code [in pending command opts callback]
  (let [id (gensym)
        code (str "(try (clojure.core/let [res " command
                  "\n] ['" id " :result (pr-str res)]) (catch :default e "
                  "['" id " :error (pr-str e)]))\n")]
    (swap! pending assoc id callback)
    (when-let [ns-name (:namespace opts)]
      (async/put! in (str "(ns " ns-name ")")))
    (async/put! in code)
    id))

(defrecord Evaluator [in pending]
  eval/Evaluator
  (evaluate [_ command opts callback]
    (evaluate-code in pending command opts callback))

  (break [this id]))

(defn- treat-result-of-call [out pending output-fn]
  (let [[_ id] (re-find #"^\s*\[(.+?) " out)]
    (if-let [callback (some->> id symbol (get @pending))]
      (let [[_ key parsed] (reader/read-string out)]
        (callback {key parsed})
        (swap! pending dissoc id)
        (output-fn {:as-text out :result parsed}))
      (output-fn {:out out}))))

(defn- pending-evals [pending output-fn out]
  (if (and out (re-find #"^\s*\[" out))
    (treat-result-of-call out pending output-fn)
    (output-fn {:out out})))

(defn repl [session-name host port on-output]
  (let [[in out] (client/socket! session-name host port)
        pending-cmds (atom {})]
    (async/go-loop []
      (if-let [output (async/<! out)]
        (do
          (pending-evals pending-cmds on-output output)
          (recur))
        (on-output nil)))
    (->Evaluator in pending-cmds)))

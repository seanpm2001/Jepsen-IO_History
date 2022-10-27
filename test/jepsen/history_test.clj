(ns jepsen.history-test
  (:require [cheshire.core :as json]
            [clojure [pprint :refer [pprint]]
                     [set :as set]
                     [test :refer :all]]
            [clojure.core.reducers :as r]
            [clojure.java [io :as io]
                          [shell :refer [sh]]]
            [clojure.test :refer [deftest is]]
            [clojure.test.check [clojure-test :refer [defspec]]
                                [generators :as gen]
                                [properties :as prop]
                                [results :as results :refer [Result]]
                                [rose-tree :as rose]]
            [clojure.tools.logging :refer [info warn]]
            [com.gfredericks.test.chuck.clojure-test :as chuck
             :refer [checking for-all]]
            [dom-top.core :refer [assert+ letr loopr real-pmap]]
            [jepsen.history :as h]
            [jepsen.history [core :as hc]
                            [fold :as f]]
            [tesser.core :as t])
  (:import (io.lacuna.bifurcan IEntry
                               ISet
                               IMap
                               Map)
           (java.io StringWriter)
           (java.util ArrayList)
           (jepsen.history History)
           ))

(def n
  "How aggressive should tests be? We always do at least this many trials. QC
  scales up scaling factors to 200. Some of our tests are orders of magnitude
  more expensive than others, so we scale many tests to N x 10 or whatever."
  ;5
  100
  ;500
  )

;; Simple example-based tests


;; Generative tests

(def small-pos-int
  "A nonzero positive integer."
  (gen/sized (fn [size] (gen/choose 1 (inc size)))))

(def client-process-gen
  "Generates a random client process"
  (gen/choose 0 3))

(def nemesis-process-gen
  "Generates a random nemesis process"
  (gen/return :nemesis))

(def process-gen
  "Generates a random process."
  (gen/one-of [client-process-gen nemesis-process-gen]))

(def client-f-gen
  "Generates a random :f for a client operation"
  (gen/elements [:r :w]))

(def nemesis-f-gen
  "Generates a random :f for a nemesis operation"
  (gen/elements [:kill :start]))

(defn invoke-gen
  "Makes a random invocation"
  []
  (gen/let [type (gen/elements [:client :nemesis])]
    (case type
      :client  (gen/hash-map :type (gen/return :info)
                             :process nemesis-process-gen
                             :f nemesis-f-gen)
      :nemesis (gen/hash-map :type (gen/return :invoke)
                             :process client-process-gen
                             :f client-f-gen))))

(defn complete-gen
  "Returns a generator for the given invoke op"
  [{:keys [process f]}]
  (if (number? process)
    ; Client
    (gen/let [type (gen/elements [:ok :info :fail])]
      {:process process
       :type    type
       :f       f})
    ; Nemesis
    (gen/return {:process process
                 :type    :info
                 :f       f})))

(def op-pair-gen
  "Generates a pair of matched invocation and completion."
  (gen/let [invoke   (invoke-gen)
            complete (complete-gen invoke)]
    [invoke complete]))

(def ops-gen*
  "Generates a series of ops without indices"
  ; Either do a pair of ops, or complete a pending op.
  (gen/let [instructions (gen/vector
                           (gen/one-of [op-pair-gen (gen/return :complete)]))]
    ; Restructure to be concurrent
    (loopr [history (transient [])
            ; Map of process to invoke op
            ^IMap pending (.linear (Map.))]
           [instruction instructions]
           (if (= :complete instruction)
             ; Complete a random op
             (if (< 0 (.size pending))
               (let [^IEntry kv (.nth pending 0)
                     process    (.key kv)
                     complete   (.value kv)]
                 (recur (conj! history complete)
                        (.remove pending process)))
               ; Can't complete anything
               (recur history pending))
             ; Invocation
             (let [[{:keys [process] :as invoke} complete] instruction]
               (if-let [last-complete (.get pending process nil)]
                 ; Have to complete before we can start this
                 (recur (-> history (conj! last-complete) (conj! invoke))
                        (.put pending process complete))
                 ; Can begin right away
                 (recur (conj! history invoke)
                        (.put pending process complete)))))
           (persistent! history))))

(def ops-gen
  "Generates a series of ops with indices"
  (gen/fmap h/index-ops ops-gen*))

(defn model-dense-pair-index
  "A naive implementation of a dense pair index."
  [ops]
  (let [pair-index (int-array (count ops))]
    (loopr [; A map of processes to the index of the op they just
            ; invoked
            invokes (transient {})]
           [op ops]
           (let [p (:process op)]
             (case (:type op)
               :invoke
               (if-let [invoke (get invokes p)]
                 (throw (ex-info
                          (str "Process " p " is still executing "
                               (pr-str invoke) " and cannot invoke "
                               (pr-str op))
                          {:type       ::double-invoke
                           :op         op
                           :running-op invoke}))
                 (recur (assoc! invokes p op)))

               :info
               (if (h/client-op? op)
                 ; For clients, you need to have invoked something
                 (if-let [invoke (get invokes p)]
                   (do (aset-int pair-index (:index invoke) (:index op))
                       (aset-int pair-index (:index op) (:index invoke))
                       (recur (dissoc! invokes p)))
                   (throw (ex-info (str "Client " p " logged an :info without invoking anything: " (pr-str op))
                                   {:type    ::info-without-invoke
                                    :op      op})))
                 ; For non-clients, match successive pairs of infos
                 (if-let [invoke (get invokes p)]
                   ; Second
                   (do (aset-int pair-index (:index invoke) (:index op))
                       (aset-int pair-index (:index op) (:index invoke))
                       (recur (dissoc! invokes p)))
                   ; First
                   (recur (assoc! invokes p op))))

               (:ok, :fail)
               (if-let [invoke (get invokes p)]
                 (do (aset-int pair-index (:index invoke) (:index op))
                     (aset-int pair-index (:index op) (:index invoke))
                     (recur (dissoc! invokes p)))
                 (throw (ex-info (str "Process " p " can not complete "
                                     (pr-str op) "without invoking it first")
                                 {:type ::complete-without-invoke
                                  :op   op})))))
           ; Any remaining invokes, fill in -1
           (doseq [op (-> invokes persistent! vals)]
             (aset-int pair-index (:index op) -1)))
    pair-index))

(deftest ^:focus pair-index-spec
  (checking "pair index" n
            [ops ops-gen]
            (is (= (vec (model-dense-pair-index ops))
                   (vec (h/dense-history-pair-index
                          (f/folder (hc/chunked 8 ops))))))))

(defn rewrite-op
  "Adds a testing field to each operation and wraps the value"
  [{:keys [process value] :as op}]
  (assoc op
         :process (if (integer? process)
                    (inc process)
                    process)
         :value [:rewrite value]))

(defn process-set
  "A reducing fn for finding the set of all processes in a history."
  ([] #{})
  ([ps op]
   (conj ps (:process op))))

(defn check-history-equiv
  "checks that two histories are equivalent."
  [a b]
  ;(prn :a (class a) a)
  ;(prn :b (class b) b)
  (testing "count"
    (is (= (count a) (count b))))
  (testing "hash"
    (is (= (hash a) (hash b)))
    (is (= (.hashCode a) (.hashCode b))))
  (testing "equality"
    (is (= a b))
    (is (= b a))
    (is (.equals a b))
    (is (.equals b a)))
  (testing "string"
    (is (= (str a) (str b))))
  (testing "nth/get/fn"
    (doseq [i (range (count a))]
      (is (= (nth a i) (nth b i)))
      (is (= (get a i) (get b i)))
      (is (= (a i) (b i)))))
  (testing "seq"
    (is (= (seq a) (seq b))))
  (testing "iterator"
    (is (= (iterator-seq (.iterator a))
           (iterator-seq (.iterator b)))))

  (testing "reduce"
    ; trivial reducer
    (is (= (into [] a) (into [] b)))
    ; a set of processes
    (is (= (reduce process-set #{} a) (reduce process-set #{} b))))

  (testing "coll-fold"
    (is (= (r/fold set/union process-set a)
           (r/fold set/union process-set b))))

  (when (instance? History a)
    (testing "pair-index"
      (doseq [i (range (count a))]
        (is (= (h/pair-index a i) (h/pair-index b i)))))

    (testing "get-index"
      (doseq [op a]
        (let [i (:index op)]
          (is (= op (h/get-index a i)))
          (is (= op (h/get-index b i))))))

    (testing "invocation & completion"
      (doseq [op a]
        (if (or (h/invoke? op) (not (h/client-op? op)))
          (is (= (h/completion a op)
                 (h/completion b op)))
          (is (= (h/invocation a op)
                 (h/invocation b op))))))

    (testing "fold"
      (testing "reduce equivalence"
        (let [into-vec {:reducer-identity (constantly [])
                        :reducer conj}]
          (is (= (vec a)
                 (h/fold a into-vec)
                 (h/fold b into-vec)))))

      (testing "complex"
        (is (= (h/fold a h/pair-index-fold)
               (h/fold b h/pair-index-fold)))))

    (testing "tesser"
      (is (= (->> (t/map :f) (t/frequencies) (h/tesser a))
             (->> (t/map :f) (t/frequencies) (h/tesser b)))))))

(defn check-invoke-complete
  "Takes a history, an invocation, and a completion and validates that they're
  of the same process, have ascending indices, etc."
  [h invoke complete]
  (is (map? invoke))
  (is (map? complete))
  (testing "order"
    (is (< (:index invoke) (:index complete)))
    (is (= (:process invoke) (:process complete))))
  (testing "same process"
    (is (= (:f invoke) (:f complete))))
  (testing "nothing between"
    (is (->> (range (inc (:index invoke)) (:index complete))
             (map (partial h/get-index h))
             (filter (partial = (:process invoke)))
             seq
             not))))

(def dense-history-gen
  "Generator of dense histories."
  (gen/let [ops ops-gen]
    (h/dense-history ops)))

(deftest ^:focus dense-history-test
  (checking "dense history" n
            [ops ops-gen]
            (let [h (h/dense-history ops)]
              ; Should be equivalent to underlying ops vector
              (check-history-equiv ops h)

              (testing "indices"
                (is (h/dense-indices? h))
                (is (= (range (count h))
                       (mapv :index (seq h)))))
              (doseq [op h]
                (testing "get-index"
                  (is (identical? op (h/get-index h (:index op)))))

                (testing "pairs"
                  (testing "symmetric"
                    (when (or (h/invoke? op)
                              (not (h/client-op? op)))
                      (when-let [completion (h/completion h op)]
                        (is (identical? op (h/invocation h completion))))))
                  (if (h/client-op? op)
                    ; For clients, we should have an obvious invoke/complete
                    ; pair
                    (let [invoke (if (h/invoke? op)
                                   op
                                   (h/invocation h op))
                          complete (if (h/invoke? op)
                                     (h/completion h op)
                                     op)]
                      (when complete
                        (is (h/invoke? invoke))
                        (is (not (h/invoke? complete)))
                        (check-invoke-complete h invoke complete)))
                    ; For nemeses, we won't know which is invoke and which is
                    ; fail
                    (do (is (= :info (:type op)))
                        (let [other (h/completion h op)]
                          (when other
                            (let [[a b] (sort-by :index [op other])]
                              (check-invoke-complete h a b))))))))

              (testing "tesser"
                (is (= (frequencies (map :f ops))
                       (->> (t/map :f)
                            (t/frequencies)
                            (h/tesser h))))))))

(deftest ^:focus map-test
  (checking "rewrite" n
            [h dense-history-gen]
            (check-history-equiv (h/dense-history (mapv rewrite-op h))
                                 (h/map rewrite-op h))))

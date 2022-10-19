(defproject jepsen.history "0.1.0-SNAPSHOT"
  :description "A library for working with Jepsen's histories of concurrent systems."
  :url "https://github.com/jepsen-io/history"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [; Logging
                 [org.clojure/tools.logging "1.2.4"]
                 ; Control flow, including fast single-threaded reductions
                 [dom-top "1.0.7"]
                 ; Fast reductions
                 [tesser.core "1.0.5"]
                 ; Defining datatypes
                 [potemkin "0.4.6"]
                 ; Fast data structures
                 [io.lacuna/bifurcan "0.2.0-alpha6"]]
  :repl-options {:init-ns jepsen.history}
  :profiles {:dev {:dependencies
                   [[org.clojure/clojure "1.10.3"]
                    [org.clojure/test.check "1.1.1"]
                    [org.slf4j/slf4j-simple "2.0.3"]
                    ]}}
  :test-selectors {:default (fn [m]
                              (not (or (:perf m)
                                       (:integration m))))
                   :focus       :focus
                   :perf        :perf})

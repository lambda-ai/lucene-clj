(def lucene-version "8.8.0")

(defproject org.msync/lucene-clj "0.2.0-SNAPSHOT"
  :description "Lucene bindings for Clojure"
  :url "https://github.com/jaju/lucene-clj"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.apache.lucene/lucene-core ~lucene-version]
                 [org.apache.lucene/lucene-queryparser ~lucene-version]
                 [org.apache.lucene/lucene-analyzers-common ~lucene-version]
                 [org.apache.lucene/lucene-suggest ~lucene-version]
                 [org.apache.lucene/lucene-highlighter ~lucene-version]
                 [org.apache.lucene/lucene-memory ~lucene-version]]
  :implicits false

  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :javac-options ["-target" "8" "-source" "8" "-deprecation" "-Xlint:-options"]

  :profiles {:dev  {:dependencies [[org.clojure/data.csv "1.0.0"]
                                   [criterium "0.4.6"]]
                    :source-paths ["test" "dev"]
                    :resource-paths ["test-resources"]}

             :test {:dependencies   [[org.clojure/data.csv "1.0.0"]]
                    :resource-paths ["test-resources"]}

             :1.9  {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :1.8  {:dependencies [[org.clojure/clojure "1.8.0"]]}}

  :deploy-repositories [["releases" :clojars]
                        ["snapshots" :clojars]])

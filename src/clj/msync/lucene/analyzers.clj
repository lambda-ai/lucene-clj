(ns msync.lucene.analyzers
  (:require [clojure.walk :refer [stringify-keys]])
  (:import [org.apache.lucene.analysis.miscellaneous PerFieldAnalyzerWrapper]
           [org.apache.lucene.analysis Analyzer CharArraySet]
           [org.apache.lucene.analysis.core KeywordAnalyzer SimpleAnalyzer]
           [java.util Collection]
           [org.apache.lucene.analysis.standard StandardAnalyzer]))

;; Convenience constructors for the most commonly used analyzers
(defn ^Analyzer simple-analyzer [] (SimpleAnalyzer.))

(defn ^Analyzer keyword-analyzer [] (KeywordAnalyzer.))

(defn ^Analyzer standard-analyzer
  "StandardAnalyzer, with configurable stop-words and case-ignore behavior."
  ([] (standard-analyzer [] true))
  ([^Collection stop-words] (standard-analyzer stop-words true))
  ([^Collection stop-words ignore-case?]
   (StandardAnalyzer. (CharArraySet. stop-words ignore-case?))))

;; Create your own composed, field-aware analyzer
(defn ^PerFieldAnalyzerWrapper per-field-analyzer
  "Composed, per-field analyzer.
  Takes a default analyzer, and a map from field-name to analyzer"
  [^Analyzer default-analyzer field->analyzer]
  (PerFieldAnalyzerWrapper. default-analyzer (stringify-keys field->analyzer)))

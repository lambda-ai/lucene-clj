(ns msync.lucene
  (:require [clojure.java.io :as io])
  (:import [org.apache.lucene.store RAMDirectory Directory FSDirectory]
           [org.apache.lucene.analysis CharArraySet Analyzer]
           [org.apache.lucene.analysis.standard StandardAnalyzer]
           [java.util Collection]
           [java.io File]
           [org.apache.lucene.index IndexWriterConfig IndexWriter IndexableFieldType IndexOptions IndexReader DirectoryReader]
           [org.apache.lucene.document Field Document FieldType]
           [java.util.logging Logger Level]
           [clojure.lang Sequential]
           [org.apache.lucene.util QueryBuilder]
           [org.apache.lucene.search IndexSearcher Query TopDocs ScoreDoc]
           [org.apache.lucene.search.suggest.document SuggestField]))

(defonce logger (Logger/getLogger "msync.lucene"))

(def ^:private index-options
  {:full           IndexOptions/DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS
   true            IndexOptions/DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS

   :none           IndexOptions/NONE
   :nil            IndexOptions/NONE
   false           IndexOptions/NONE

   :docs-freqs     IndexOptions/DOCS_AND_FREQS
   :docs-freqs-pos IndexOptions/DOCS_AND_FREQS_AND_POSITIONS})

(defn ^Analyzer standard-analyzer>
  "StandardAnalyzer, with configurable stop-words and case-ignore behavior."
  ([] (StandardAnalyzer.))
  ([^Collection stop-words] (standard-analyzer> stop-words true))
  ([^Collection stop-words ^Boolean ignore-case?] (StandardAnalyzer. (CharArraySet. stop-words ignore-case?))))

(def ^:dynamic *analyzer>* standard-analyzer>)

(defn- index-writer-config>
  ([]
   (index-writer-config> (*analyzer>*)))
  ([^Analyzer analyzer]
   (IndexWriterConfig. analyzer)))

(defn- ^IndexWriter index-writer>
  ([^Directory directory]
   (index-writer> directory (index-writer-config>)))
  ([^Directory directory
    ^IndexWriterConfig index-writer-config]
   (IndexWriter. directory index-writer-config)))

(defn- ^IndexReader index-reader>
  [^Directory directory]
  (DirectoryReader/open directory))

(defn- ^IndexableFieldType field-type>
  ""
  [{:keys [index-type stored?]}]
  (let [index-option (index-options index-type IndexOptions/NONE)]
    (doto (FieldType.)
      (.setIndexOptions index-option)
      (.setStored stored?))))

(defn- ^Field field> [key value opts]
  {:pre [(not (.startsWith (name key) "$suggest"))]}
  (let [^FieldType field-type (field-type> opts)
        value                 (if (keyword? value) (name value) (str value))]
    (Field. ^String (name key) ^String value field-type)))

(defn- ^SuggestField suggest-field>
  ([key value weight]
   (let [key (str "$suggest-" (name key))]
     (.log logger Level/FINEST (str "Created suggest field with name " key " and value " value))
     (SuggestField. key value weight))))

(defonce -docs (atom []))
(defn map->document [m {:keys [stored-fields indexed-fields suggest-fields]}]
  "Convert a map to a Lucene document.
  Lossy on the way back. String field names come back as keywords."
  (let [stored-fields            (or stored-fields (->> m keys (into #{})))
        indexed-fields           (or indexed-fields (zipmap (keys m) (repeat :full)))
        suggest-fields           (or suggest-fields {})
        field-keys               (keys m)
        field-creator-fn         (fn [k]
                                   (field> k (get m k)
                                           {:index-type (get indexed-fields k false)
                                            :stored?    (contains? stored-fields k)}))
        suggest-field-creator-fn (fn [[field-name weight]]
                                   (let [value (get m field-name)]
                                     (suggest-field> field-name value weight)))
        fields                   (map field-creator-fn field-keys)
        suggest-fields           (map suggest-field-creator-fn suggest-fields)
        doc                      (Document.)]
    (doseq [^Field field fields]
      (.add doc field))
    (doseq [^SuggestField field suggest-fields]
      (.add doc field))
    (swap! -docs conj doc)
    doc))

(defn document->map [^Document document]
  "Lucene document to map. Keys are always keywords.
  Only stored fields come back."
  (reduce
    (fn [m field]
      (assoc m (-> field .name keyword) (-> field .stringValue)))
    {}
    document))

(defn ^Directory memory-index>
  "Lucene Directory for transient indexes"
  []
  (RAMDirectory.))

(defn ^Directory disk-index>
  "Persistent index on disk"
  [^String dir-path]
  (let [path (.toPath ^File (io/as-file dir-path))]
    (FSDirectory/open path)))

(defmulti index-all! (fn [store & _] (class store)))

(defmethod index-all! Directory
  [^Directory directory
   ^Sequential map-docs
   {:keys [analyzer]
    :or   {analyzer (*analyzer>*)}
    :as   opts}]
  (let [index-writer-config (index-writer-config> analyzer)
        index-writer        (index-writer> directory index-writer-config)]
    (try
      (index-all! index-writer map-docs (dissoc opts :analyzer))
      (.commit index-writer)
      (catch Exception e
        (.log logger Level/SEVERE
              (str *ns* " - Error in IO with index writer - " (.getMessage e))))
      (finally
        (.close index-writer)))))

(defmethod index-all! IndexWriter
  [^IndexWriter index-writer
   map-docs
   {:keys [stored-fields indexed-fields suggest-fields]
    :as   opts}]
  (doseq [document (map
                     #(map->document %
                                     {:stored-fields  stored-fields
                                      :indexed-fields indexed-fields
                                      :suggest-fields suggest-fields}) map-docs)]
    (.addDocument index-writer document)))

;; Picked from https://github.com/federkasten/clucie
(defmulti ^:private search* #(class (first %&)))
(defmulti ^:private parse-query
          (fn [_ {:keys [query-type]}] query-type))

(defmethod parse-query :query
  [query-form {:keys [analyzer field-name]}]
  (let [^QueryBuilder q-builder (QueryBuilder. analyzer)]
    (.createBooleanQuery q-builder (name field-name) query-form)))

(defmethod parse-query :phrase-query
  [query-form {:keys [analyzer field-name]}]
  (let [^QueryBuilder q-builder (QueryBuilder. analyzer)]
    (.createPhraseQuery q-builder (name field-name) query-form)))

(defmethod search* Directory
  [^Directory index-store query-type query-form opts]
  (with-open [reader (index-reader> index-store)]
    (search* reader query-type query-form opts)))

(defmethod search* IndexReader
  [^IndexReader index-store query-type query-form
   {:keys [field-name results-per-page max-results analyzer page]
    :or   {results-per-page 10
           max-results      results-per-page
           page             0
           analyzer         (*analyzer>*)}}]
  (let [^IndexSearcher searcher (IndexSearcher. index-store)
        ^Query query            (parse-query query-form {:analyzer analyzer :query-type query-type :field-name field-name})
        ^TopDocs hits           (.search searcher query (int max-results))
        start                   (* page results-per-page)
        end                     (min (+ start results-per-page) max-results (.totalHits hits))]
    (vec
      (for [^ScoreDoc hit (map (partial aget (.scoreDocs hits))
                               (range start end))]
        (let [doc-id  (.doc hit)
              doc     (.doc searcher doc-id)
              doc-map (document->map doc)
              score   (.score hit)]
          {:hit doc-map :score score :doc-id doc-id})))))

(defn search [store query-form opts]
  (search* store :query query-form opts))
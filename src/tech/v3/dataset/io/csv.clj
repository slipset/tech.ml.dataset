(ns tech.v3.dataset.io.csv
  "CSV parsing based on [charred.api/read-csv](https://cnuernber.github.io/charred/)."
  (:require [charred.api :as charred]
            [charred.coerce :as coerce]
            [tech.v3.dataset.io :as ds-io]
            [tech.v3.parallel.for :as pfor]
            [tech.v3.parallel.queue-iter :as queue-iter]
            [tech.v3.datatype :as dtype]
            [tech.v3.io :as io]
            [tech.v3.dataset.io.column-parsers :as column-parsers]
            [tech.v3.dataset.io.context :as parse-context]
            [tech.v3.dataset.impl.dataset :as ds-impl]
            [ham-fisted.api :as hamf])
  (:import [tech.v3.datatype ArrayHelpers]
           [clojure.lang IReduceInit]
           [java.lang AutoCloseable]
           [java.util Iterator]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(deftype ^:private TakeReducer [^Iterator src
                                ^{:unsynchronized-mutable true
                                  :tag long} count]
  IReduceInit
  (reduce [this rfn acc]
    (let [cnt count]
      (loop [idx 0
             continue? (.hasNext src)
             acc acc]
        ;;Note no reduced? check.
        (if (and continue? (< idx cnt))
          (let [acc (rfn acc (.next src))]
            (recur (unchecked-inc idx) (.hasNext src) acc))
          (do
            (set! count (- cnt idx))
            acc))))))


(defn- parse-next-batch
  [^Iterator row-iter header-row options]
  (when (.hasNext row-iter)
    (let [n-header-cols (count header-row)
          num-rows (long (get options :batch-size
                              (get options :n-records
                                   (get options :num-rows Long/MAX_VALUE))))
          {:keys [parsers col-idx->parser]}
          (parse-context/options->col-idx-parse-context
           options :string (fn [^long col-idx]
                             (when (< col-idx n-header-cols)
                               (header-row col-idx))))]
      (reduce (hamf/indexed-accum
               acc row-idx row
               (reduce (hamf/indexed-accum
                        acc col-idx field
                        (-> (col-idx->parser col-idx)
                            (column-parsers/add-value! row-idx field)))
                       nil
                       row))
              nil
              (TakeReducer. row-iter num-rows))
      (cons (parse-context/parsers->dataset options parsers)
            (lazy-seq (parse-next-batch row-iter header-row options))))))


(defn rows->dataset-seq
  "Given a sequence of rows each row container a sequence of strings, parse into columnar data.
  See csv->columns."
  [{:keys [header-row?]
    :or {header-row? true}
    :as options}
   row-seq]
  (let [row-iter (pfor/->iterator row-seq)
        n-initial-skip-rows (long (get options :n-initial-skip-rows 0))
        _ (dotimes [idx n-initial-skip-rows]
            (when (.hasNext row-iter) (.next row-iter)))
        header-row (if (and header-row? (.hasNext row-iter))
                     (vec (.next row-iter))
                     [])]
    (if (not (.hasNext row-iter))
      [(let [n-header-cols (count header-row)
             {:keys [parsers col-idx->parser]}
             (parse-context/options->col-idx-parse-context
              options :string (fn [^long col-idx]
                                (when (< col-idx n-header-cols)
                                  (header-row col-idx))))]
         (dotimes [idx n-header-cols]
           (col-idx->parser idx))
         (parse-context/parsers->dataset options parsers))]
      (parse-next-batch row-iter header-row options))))


(defn csv->dataset-seq
  "Read a csv into a lazy sequence of datasets.  All options of [[tech.v3.dataset/->dataset]]
  are suppored with an additional option of `:batch-size` which defaults to 128000.

  The input will only be closed once the entire sequence is realized."
  [input & [options]]
  (let [options (update options :batch-size #(or % 128000))]
    (->> (charred/read-csv-supplier (ds-io/input-stream-or-reader input) options)
         (coerce/->iterator)
         (rows->dataset-seq options))))


(defn csv->dataset
  "Read a csv into a dataset.  Same options as [[tech.v3.dataset/->dataset]]."
  [input & [options]]
  (let [iter (-> (charred/read-csv-supplier (ds-io/input-stream-or-reader input) options)
                 (coerce/->iterator))
        retval (->> (rows->dataset-seq options iter)
                    (first))]
    (when (instance? AutoCloseable iter)
      (.close ^AutoCloseable iter))
    retval))


(defn- load-csv
  [data options]
  (ds-io/wrap-stream-fn
   data (:gzipped? options)
   #(csv->dataset %1 options)))


(defmethod ds-io/data->dataset :csv
  [data options]
  (load-csv data options))


(defmethod ds-io/data->dataset :tsv
  [data options]
  (load-csv data (merge {:separator \tab} options)))


(defmethod ds-io/data->dataset :txt
  [data options]
  (load-csv data options))


(comment
  (require '[tech.v3.dataset.io.univocity :as univocity])
  (require '[criterium.core :as crit])


  (crit/quick-bench (univocity/csv->dataset "test/data/issue-292.csv"))
  ;; Evaluation count : 24 in 6 samples of 4 calls.
  ;;            Execution time mean : 27.045594 ms
  ;;   Execution time std-deviation : 887.643388 µs
  ;;  Execution time lower quantile : 26.015627 ms ( 2.5%)
  ;;  Execution time upper quantile : 27.984189 ms (97.5%)
  ;;                  Overhead used : 1.721587 ns
  (crit/quick-bench (csv->dataset "test/data/issue-292.csv"))
  ;; Evaluation count : 6 in 6 samples of 1 calls.
  ;;            Execution time mean : 139.203976 ms
  ;;   Execution time std-deviation : 3.406500 ms
  ;;  Execution time lower quantile : 136.348543 ms ( 2.5%)
  ;;  Execution time upper quantile : 143.004906 ms (97.5%)
  ;;                  Overhead used : 1.721587 ns

  (crit/quick-bench (ds-csv->dataset "test/data/issue-292.csv"))

  (dotimes [idx 1000]
    (ds-csv->dataset "test/data/issue-292.csv"))


  (with-bindings {#'*compile-path* "compiled"}
    (compile 'tech.v3.dataset.io.csv))

  (defn read-all
    [^java.io.Reader reader]
    (loop [data (.read reader)]
      (if (== -1 data)
        :ok
        (recur (.read reader)))))


  (defn read-all-cbuf
    [^java.io.Reader reader]
    (let [cbuf (char-array 1024)]
      (loop [data (.read reader)]
        (if (== -1 data)
          :ok
          (recur (.read reader cbuf))))))


  (defn read-all-cbuf-ibuf
    [^java.io.Reader reader]
    (let [cbuf (char-array 1024)
          ibuf (int-array 1024)]
      (loop [data (.read reader cbuf)]
        (if (== -1 data)
          :ok
          (do
            (dotimes [idx data]
              (ArrayHelpers/aset ibuf idx (clojure.lang.RT/intCast (aget cbuf idx))))
            (recur (.read reader cbuf)))))))


  )

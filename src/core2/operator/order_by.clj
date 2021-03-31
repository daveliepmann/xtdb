(ns core2.operator.order-by
  (:require [core2.util :as util])
  (:import clojure.lang.Keyword
           core2.ICursor
           java.util.function.Consumer
           [java.util ArrayList Collections Comparator List]
           org.apache.arrow.algorithm.sort.DefaultVectorComparators
           org.apache.arrow.memory.BufferAllocator
           [org.apache.arrow.vector IntVector VectorSchemaRoot]
           org.apache.arrow.vector.types.pojo.Field))

(deftype OrderSpec [^String col-name, ^Keyword direction])

(defn ->order-spec [col-name direction]
  (OrderSpec. col-name direction))

(defn- accumulate-roots ^org.apache.arrow.vector.VectorSchemaRoot [^ICursor in-cursor, ^BufferAllocator allocator]
  (let [!acc-root (atom nil)]
    (.forEachRemaining in-cursor
                       (reify Consumer
                         (accept [_ in-root]
                           (let [^VectorSchemaRoot in-root in-root
                                 ^VectorSchemaRoot
                                 acc-root (swap! !acc-root (fn [^VectorSchemaRoot acc-root]
                                                             (if acc-root
                                                               (do
                                                                 (assert (= (.getSchema acc-root) (.getSchema in-root)))
                                                                 acc-root)
                                                               (VectorSchemaRoot/create (.getSchema in-root) allocator))))
                                 schema (.getSchema acc-root)
                                 acc-row-count (.getRowCount acc-root)
                                 in-row-count (.getRowCount in-root)]

                             (doseq [^Field field (.getFields schema)]
                               (let [acc-vec (.getVector acc-root field)
                                     in-vec (.getVector in-root field)]
                                 (dotimes [idx in-row-count]
                                   (.copyFromSafe acc-vec idx (+ acc-row-count idx) in-vec))))

                             (util/set-vector-schema-root-row-count acc-root (+ acc-row-count in-row-count))))))
    @!acc-root))

(defn order-root ^java.util.List [^VectorSchemaRoot root, ^List #_<OrderSpec> order-specs]
  (let [idxs (ArrayList. ^List (range (.getRowCount root)))
        comparator (reduce (fn [^Comparator acc ^OrderSpec order-spec]
                             (let [^String column-name (.col-name order-spec)
                                   direction (.direction order-spec)
                                   in-vec (util/maybe-single-child-dense-union (.getVector root column-name))
                                   arrow-comparator (doto (DefaultVectorComparators/createDefaultComparator in-vec)
                                                      (.attachVector in-vec))
                                   ^Comparator comparator (cond-> (reify Comparator
                                                                    (compare [_ left-idx right-idx]
                                                                      (.compare arrow-comparator left-idx right-idx)))
                                                            (= :desc direction) (.reversed))]
                               (if acc
                                 (.thenComparing acc comparator)
                                 comparator)))
                           nil
                           order-specs)]
    (Collections/sort idxs comparator)
    idxs))

(deftype OrderByCursor [^BufferAllocator allocator
                        ^ICursor in-cursor
                        ^List #_<OrderSpec> order-specs
                        ^:unsynchronized-mutable ^VectorSchemaRoot out-root]
  ICursor
  (tryAdvance [this c]
    (when out-root
      (.close out-root))

    (if-not out-root
      (with-open [acc-root (accumulate-roots in-cursor allocator)]
        (let [sorted-idxs (order-root acc-root order-specs)
              out-root (VectorSchemaRoot/create (.getSchema acc-root) allocator)]

          (set! (.out-root this) out-root)

          (if (pos? (.getRowCount acc-root))
            (do (dotimes [n (util/root-field-count acc-root)]
                  (let [in-vec (.getVector acc-root n)
                        out-vec (.getVector out-root n)]
                    (util/set-value-count out-vec (.getValueCount in-vec))
                    (dotimes [m (.size sorted-idxs)]
                      (.copyFrom out-vec (.get sorted-idxs m) m in-vec))))
                (util/set-vector-schema-root-row-count out-root (.getRowCount acc-root))
                (.accept c out-root)
                true)
            false)))
      false))

  (close [_]
    (util/try-close out-root)
    (util/try-close in-cursor)))

(defn ->order-by-cursor ^core2.ICursor [^BufferAllocator allocator, ^ICursor in-cursor, ^List #_<OrderSpec> order-specs]
  (OrderByCursor. allocator in-cursor order-specs nil))
(ns core2.temporal
  (:require [core2.metadata :as meta]
            core2.object-store
            [core2.types :as t]
            [core2.temporal.kd-tree :as kd]
            core2.tx
            [core2.util :as util])
  (:import core2.buffer_pool.BufferPool
           core2.object_store.ObjectStore
           core2.metadata.IMetadataManager
           core2.temporal.TemporalCoordinates
           [core2.tx TransactionInstant Watermark]
           org.apache.arrow.memory.util.ArrowBufPointer
           [org.apache.arrow.memory ArrowBuf BufferAllocator]
           [org.apache.arrow.vector.types.pojo Field Schema]
           [org.apache.arrow.vector BigIntVector TimeStampMilliVector VectorSchemaRoot]
           org.apache.arrow.vector.complex.DenseUnionVector
           org.roaringbitmap.longlong.Roaring64Bitmap
           java.nio.ByteBuffer
           [java.util Arrays Comparator Date List Map HashMap SortedMap SortedSet Spliterator Spliterators TreeMap]
           [java.util.function Consumer Function IntFunction LongConsumer Predicate ToLongFunction]
           [java.util.concurrent CompletableFuture ConcurrentHashMap]
           java.util.concurrent.locks.StampedLock
           java.util.concurrent.atomic.AtomicLong
           java.util.stream.StreamSupport
           java.io.Closeable))

;; Temporal proof-of-concept plan:

;; From a BCDM point of view, core2 (and Crux) are similar to Jensen's
;; event log approach, that is, we know tx-time, and we know the vt
;; range, but not the actual real state as expressed in the Snodgrass'
;; timestamped tuple approach, which is the relation we want scan to
;; produce. Theoretically, one can map between these via the BCDM, as
;; described in the paper for snapshot equivalent representations, and
;; that serves as a good reference, but not practical.

;; The only update that needs to happen to the append only data is
;; setting tx-time-end to the current tx-time when closing
;; rows. Working around this is what the current uni-temporal tx-time
;; support does. This fact will help later when and if we decide to
;; store the temporal index per chunk in Arrow and merge between them.

;; Further, I think we can decide that a put or delete always know its
;; full vt range, that is, if vt-time isn't known it's set to tx-time,
;; and if vt-time-end isn't know, it's set to end-of-time (at least
;; for the proof-of-concept).

;; In the temporal index structure, this means that when you do a put
;; (delete) you find any current rows (tx-time-end == UC) for the id
;; that overlaps the vt range, and mark those rows with the
;; tx-time-end to current tx-time (the part that cannot be done append
;; only). You then insert the new row entry (for put) normally. If the
;; put (delete) didn't fully overlap you copy the start (and/or) end
;; partial row entries forward, referring to the original row-id,
;; updating their vt-time-end (for start) and vt-time (for end) to
;; match the slice, you also set tx-time to that of the current tx,
;; and tx-time-end to UC.

;; We assume that the column store has a 1-to-1 mapping between
;; operations and row-ids, but the temporal index can refer to them
;; more than once in the case of splits. These could also be stored in
;; the column store if we later decide to break the 1-to-1 mapping.

;; For simplicitly, let's assume that this structure is an in-memory
;; kd-tree for now with 6 dimensions: id, row-id, vt-time,
;; vt-time-end, tx-time, tx-time-end. When updating tx-time-end, one
;; has a few options, either one deletes the node and reinserts it, or
;; one can have an extra value (not part of the actual index),
;; tx-time-delete, which if it exists, supersedes tx-time-end when
;; doing the element-level comparision. That would imply that these
;; nodes would needlessly be found by the kd-tree navigation itself,
;; so moving them might be better. But a reason to try to avoid moving
;; nodes is that later this tree can be an implicit kd-tree stored as
;; Arrow, one per chunk, and the query would need to merge them. How
;; to solve this problem well can be saved for later.

;; Once this structure exists, it could also potentially be used to
;; replace the tombstone check (to see if a row is a deletion) I added
;; as those rows won't sit in the tree. But again, we can postpone
;; that, as this might be superseded by a per-row _op struct.

(set! *unchecked-math* :warn-on-boxed)

(def ^java.util.Date end-of-time #inst "9999-12-31T23:59:59.999Z")

(defn row-id->coordinates ^core2.temporal.TemporalCoordinates  [^long row-id]
  (let [coords (TemporalCoordinates. row-id)]
    (set! (.validTimeEnd coords) (.getTime end-of-time))
    (set! (.txTimeEnd coords) (.getTime end-of-time))
    coords))

(defn ->coordinates ^core2.temporal.TemporalCoordinates [{:keys [id
                                                                 ^long row-id
                                                                 ^Date tx-time-start
                                                                 ^Date tx-time-end
                                                                 ^Date valid-time-start
                                                                 ^Date valid-time-end
                                                                 tombstone?]}]
  (let [coords (TemporalCoordinates. row-id)]
    (set! (.id coords) id)
    (set! (.validTimeStart coords) (.getTime (or valid-time-start tx-time-start)))
    (set! (.validTimeEnd coords) (.getTime (or valid-time-end end-of-time)))
    (set! (.txTimeStart coords) (.getTime tx-time-start))
    (set! (.txTimeEnd coords) (.getTime (or tx-time-end end-of-time)))
    (set! (.tombstone coords) (boolean tombstone?))
    coords))

(defrecord TemporalRoots [^Roaring64Bitmap row-id-bitmap ^Map roots]
  Closeable
  (close [_]
    (doseq [root (vals roots)]
      (util/try-close root))))

(definterface ITemporalManager
  (^Object getTemporalWatermark [])
  (^long getInternalId [^Object id])
  (^void registerNewChunk [^long chunk-idx])
  (^void updateTemporalCoordinates [^java.util.SortedMap row-id->temporal-coordinates])
  (^core2.temporal.TemporalRoots createTemporalRoots [^core2.tx.Watermark watermark
                                                      ^java.util.List columns
                                                      ^longs temporal-min-range
                                                      ^longs temporal-max-range
                                                      ^org.roaringbitmap.longlong.Roaring64Bitmap row-id-bitmap]))

(definterface TemporalManangerPrivate
  (^void populateKnownChunks []))

(def ^:private temporal-columns
  (->> (for [col-name ["_tx-time-start" "_tx-time-end" "_valid-time-start" "_valid-time-end"]]
         [col-name (t/->primitive-dense-union-field col-name #{:timestampmilli})])
       (into {})))

(defn temporal-column? [col-name]
  (contains? temporal-columns col-name))

(def ^:private timestampmilli-type-id
  (-> (t/primitive-type->arrow-type :timestampmilli)
      (t/arrow-type->type-id)))

(defn ->temporal-root-schema ^org.apache.arrow.vector.types.pojo.Schema [col-name]
  (Schema. [t/row-id-field (get temporal-columns col-name)]))

(def ^:const ^int k 6)

(def ^:const ^int id-idx 0)
(def ^:const ^int row-id-idx 1)
(def ^:const ^int valid-time-start-idx 2)
(def ^:const ^int valid-time-end-idx 3)
(def ^:const ^int tx-time-start-idx 4)
(def ^:const ^int tx-time-end-idx 5)

(def ^:private column->idx {"_valid-time-start" valid-time-start-idx
                            "_valid-time-end" valid-time-end-idx
                            "_tx-time-start" tx-time-start-idx
                            "_tx-time-end" tx-time-end-idx})

(declare insert-coordinates)

(defn- ->temporal-obj-key [chunk-idx]
  (format "temporal-%08x.arrow" chunk-idx))

(deftype TemporalManager [^BufferAllocator allocator
                          ^ObjectStore object-store
                          ^BufferPool buffer-pool
                          ^IMetadataManager metadata-manager
                          ^AtomicLong id-counter
                          ^Map id->internal-id
                          ^:unsynchronized-mutable chunk-kd-tree
                          ^:volatile-mutable kd-tree]
  TemporalManangerPrivate
  (populateKnownChunks [this]
    (let [acc (atom nil)
          known-chunks (.knownChunks metadata-manager)
          futs (->> (for [chunk-idx known-chunks]
                      [(-> (.getBuffer buffer-pool (->temporal-obj-key chunk-idx))
                           (util/then-apply util/try-close))
                       (-> (.getBuffer buffer-pool (meta/->chunk-obj-key chunk-idx "_id"))
                           (util/then-apply util/try-close))])
                    (reduce into []))]
      @(CompletableFuture/allOf (into-array CompletableFuture futs))
      (doseq [chunk-idx known-chunks]
        (with-open [^ArrowBuf temporal-buffer @(.getBuffer buffer-pool (->temporal-obj-key chunk-idx))
                    temporal-chunks (util/->chunks temporal-buffer allocator)]
          (.forEachRemaining temporal-chunks
                             (reify Consumer
                               (accept [_ temporal-root]
                                 (swap! acc #(kd/merge-kd-trees allocator % temporal-root))))))
        (with-open [^ArrowBuf id-buffer @(.getBuffer buffer-pool (meta/->chunk-obj-key chunk-idx "_id"))
                    id-chunks (util/->chunks id-buffer allocator)]
          (.forEachRemaining id-chunks
                             (reify Consumer
                               (accept [_ id-root]
                                 (let [^VectorSchemaRoot id-root id-root
                                       id-vec (.getVector id-root 1)]
                                   (dotimes [n (.getValueCount id-vec)]
                                     (.getInternalId this (.getObject id-vec n)))))))))
      (set! (.kd-tree this) @acc)))

  ITemporalManager
  (getTemporalWatermark [_]
    (some->> kd-tree (kd/retain-node-kd-tree allocator)))

  (getInternalId [_ id]
    (.computeIfAbsent id->internal-id
                      (if (bytes? id)
                        (ByteBuffer/wrap id)
                        id)
                      (reify Function
                        (apply [_ x]
                          (.incrementAndGet id-counter)))))

  (registerNewChunk [this chunk-idx]
    (when chunk-kd-tree
      (with-open [^Closeable old-chunk-kd-tree chunk-kd-tree
                  rebuilt-chunk-kd-tree (kd/rebuild-node-kd-tree allocator old-chunk-kd-tree)]
        (let [temporal-buf (with-open [^VectorSchemaRoot temporal-root (kd/->column-kd-tree allocator rebuilt-chunk-kd-tree k)]
                             (util/root->arrow-ipc-byte-buffer temporal-root :file))]
          @(.putObject object-store (->temporal-obj-key chunk-idx) temporal-buf)
          (set! (.chunk-kd-tree this) nil))))
    (when kd-tree
      (with-open [^Closeable old-kd-tree kd-tree]
        (set! (.kd-tree this) (kd/rebuild-node-kd-tree allocator old-kd-tree)))))

  (updateTemporalCoordinates [this row-id->temporal-coordinates]
    (let [id->long-id-fn (reify ToLongFunction
                           (applyAsLong [_ id]
                             (.getInternalId this id)))
          update-kd-tree-fn (fn [kd-tree]
                              (reduce
                               (fn [kd-tree coordinates]
                                 (insert-coordinates kd-tree allocator id->long-id-fn coordinates))
                               kd-tree
                               (vals row-id->temporal-coordinates)))]
      (set! (.chunk-kd-tree this) (update-kd-tree-fn chunk-kd-tree))
      (set! (.kd-tree this) (update-kd-tree-fn kd-tree))))

  (createTemporalRoots [_ watermark columns temporal-min-range temporal-max-range row-id-bitmap]
    (let [kd-tree (.temporal-watermark watermark)
          row-id-bitmap-out (Roaring64Bitmap.)
          roots (HashMap.)
          coordinates (-> (StreamSupport/intStream (kd/kd-tree-range-search kd-tree temporal-min-range temporal-max-range) false)
                          (.mapToObj (reify IntFunction
                                       (apply [_ x]
                                         (kd/kd-tree-array-point kd-tree x)))))]
      (if (empty? columns)
        (.forEach coordinates
                  (reify Consumer
                    (accept [_ x]
                      (.addLong row-id-bitmap-out (aget ^longs x row-id-idx)))))
        (let [coordinates (-> coordinates
                              (.filter (reify Predicate
                                         (test [_ x]
                                           (.contains row-id-bitmap (aget ^longs x row-id-idx)))))
                              (.sorted (Comparator/comparingLong (reify ToLongFunction
                                                                   (applyAsLong [_ x]
                                                                     (aget ^longs x row-id-idx)))))
                              (.toArray))
              value-count (count coordinates)]
          (doseq [col-name columns]
            (let [col-idx (get column->idx col-name)
                  out-root (VectorSchemaRoot/create allocator (->temporal-root-schema col-name))
                  ^BigIntVector row-id-vec (.getVector out-root 0)
                  ^DenseUnionVector temporal-duv-vec (.getVector out-root 1)
                  ^TimeStampMilliVector temporal-vec (.getVectorByType temporal-duv-vec timestampmilli-type-id)]
              (util/set-value-count row-id-vec value-count)
              (util/set-value-count temporal-duv-vec value-count)
              (util/set-value-count temporal-vec value-count)
              (dotimes [n value-count]
                (let [offset (util/write-type-id temporal-duv-vec n timestampmilli-type-id)
                      ^longs coordinate (aget coordinates n)
                      row-id (aget coordinate row-id-idx)]
                  (.addLong row-id-bitmap-out row-id)
                  (.set row-id-vec n row-id)
                  (.set temporal-vec n (aget coordinate col-idx))))
              (util/set-vector-schema-root-row-count out-root value-count)
              (.put roots col-name out-root)))))
      (->TemporalRoots (doto row-id-bitmap-out
                         (.and row-id-bitmap))
                       roots)))

  Closeable
  (close [this]
    (util/try-close kd-tree)
    (set! (.kd-tree this) nil)
    (util/try-close chunk-kd-tree)
    (set! (.chunk-kd-tree this) nil)
    (.clear id->internal-id)))

(defn ->temporal-manager ^core2.temporal.ITemporalManager [^BufferAllocator allocator
                                                           ^ObjectStore object-store
                                                           ^BufferPool buffer-pool
                                                           ^IMetadataManager metadata-manager]
  (doto (TemporalManager. allocator object-store buffer-pool metadata-manager (AtomicLong.) (ConcurrentHashMap.) nil nil)
    (.populateKnownChunks)))

(defn ->min-range ^longs []
  (long-array k Long/MIN_VALUE))

(defn ->max-range ^longs []
  (long-array k Long/MAX_VALUE))

(defn ->copy-range ^longs [^longs range]
  (some-> range (Arrays/copyOf (alength range))))

(defn insert-coordinates [kd-tree ^BufferAllocator allocator ^ToLongFunction id->internal-id ^TemporalCoordinates coordinates]
  (let [id (.applyAsLong id->internal-id (.id coordinates))
        row-id (.rowId coordinates)
        tx-time-start-ms (.txTimeStart coordinates)
        valid-time-start-ms (.validTimeStart coordinates)
        valid-time-end-ms (.validTimeEnd coordinates)
        end-of-time-ms (.getTime end-of-time)
        min-range (doto (->min-range)
                    (aset id-idx id)
                    (aset valid-time-end-idx valid-time-start-ms)
                    (aset tx-time-end-idx end-of-time-ms))
        max-range (doto (->max-range)
                    (aset id-idx id)
                    (aset valid-time-start-idx (dec valid-time-end-ms))
                    (aset tx-time-end-idx end-of-time-ms))
        overlap (-> ^Spliterator$OfInt (kd/kd-tree-range-search
                                        kd-tree
                                        min-range
                                        max-range)
                    (StreamSupport/intStream false)
                    (.mapToObj (reify IntFunction
                                 (apply [_ x]
                                   (kd/kd-tree-array-point kd-tree x))))
                    (.toArray))
        kd-tree (reduce
                 (fn [kd-tree ^longs point]
                   (kd/kd-tree-delete kd-tree allocator (->copy-range point)))
                 kd-tree
                 overlap)
        kd-tree (cond-> kd-tree
                  (not (.tombstone coordinates))
                  (kd/kd-tree-insert allocator
                                     (doto (long-array k)
                                       (aset id-idx id)
                                       (aset row-id-idx row-id)
                                       (aset valid-time-start-idx valid-time-start-ms)
                                       (aset valid-time-end-idx valid-time-end-ms)
                                       (aset tx-time-start-idx tx-time-start-ms)
                                       (aset tx-time-end-idx end-of-time-ms))))]
    (reduce
     (fn [kd-tree ^longs coord]
       (cond-> (kd/kd-tree-insert kd-tree allocator (doto (->copy-range coord)
                                                      (aset tx-time-end-idx tx-time-start-ms)))
         (< (aget coord valid-time-start-idx) valid-time-start-ms)
         (kd/kd-tree-insert allocator (doto (->copy-range coord)
                                        (aset tx-time-start-idx tx-time-start-ms)
                                        (aset valid-time-end-idx valid-time-start-ms)))

         (> (aget coord valid-time-end-idx) valid-time-end-ms)
         (kd/kd-tree-insert allocator (doto (->copy-range coord)
                                        (aset tx-time-start-idx tx-time-start-ms)
                                        (aset valid-time-start-idx valid-time-end-ms)))))
     kd-tree
     overlap)))
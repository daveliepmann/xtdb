(ns core2.operator.select
  (:require [core2.util :as util]
            [core2.vector :as vec])
  (:import [core2 IChunkCursor ICursor]
           core2.vector.IReadRelation
           java.util.function.Consumer))

(set! *unchecked-math* :warn-on-boxed)

(definterface IRelationSelector
  (^org.roaringbitmap.RoaringBitmap select [^core2.vector.IReadRelation in-rel]))

(definterface IColumnSelector
  (^org.roaringbitmap.RoaringBitmap select [^core2.vector.IReadColumn in-col]))

(deftype SelectCursor [^IChunkCursor in-cursor
                       ^IRelationSelector selector]
  ICursor
  (tryAdvance [_ c]
    (let [!advanced (atom false)]
      (while (and (.tryAdvance in-cursor
                               (reify Consumer
                                 (accept [_ in-rel]
                                   (let [^IReadRelation in-rel in-rel]
                                     (when-let [idxs (.select selector in-rel)]
                                       (when-not (.isEmpty idxs)
                                         (.accept c (vec/select in-rel (.toArray idxs)))
                                         (reset! !advanced true)))))))
                  (not @!advanced)))
      @!advanced))

  (close [_]
    (util/try-close in-cursor)))

(defn ->select-cursor ^core2.ICursor [^ICursor in-cursor, ^IRelationSelector selector]
  (SelectCursor. in-cursor selector))

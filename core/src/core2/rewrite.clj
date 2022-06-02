(ns core2.rewrite
  (:require [clojure.walk :as w]
            [clojure.core.match :as m])
  (:import java.util.regex.Pattern
           [java.util ArrayList HashMap List Map Objects]
           [clojure.lang Box IPersistentVector ILookup MapEntry]))

;; TODO:
;; - try go via IZip.

(set! *unchecked-math* :warn-on-boxed)

(deftype Zip [node ^int idx parent ^:unsynchronized-mutable ^int hash_ ^int depth]
  ILookup
  (valAt [_ k]
    (when (= :node k)
      node))

  (valAt [_ k not-found]
    (if (= :node k)
      node
      not-found))

  Object
  (equals [this other]
    (if (identical? this other)
      true
      (let [^Zip other other]
        (and (identical? Zip (.getClass other))
             (= (.idx this) (.idx other))
             (= (.depth this) (.depth other))
             (= (.node this) (.node other))
             (= (.parent this) (.parent other))))))

  (hashCode [this]
    (if (zero? hash_)
      (let [result (unchecked-int 1)
            prime (unchecked-int 31)
            result (unchecked-add-int (unchecked-multiply-int prime result)
                                      (Objects/hashCode node))
            result (unchecked-add-int (unchecked-multiply-int prime result)
                                      (Integer/hashCode idx))
            result (unchecked-add-int (unchecked-multiply-int prime result)
                                      (Objects/hashCode node))
            result (unchecked-add-int (unchecked-multiply-int prime result)
                                      (Integer/hashCode depth))]
        (set! (.hash_ this) result)
        result)
      hash_)))

(deftype StrategyDone [^Zip z])
(deftype StrategyRepeat [^Zip z])

(defmacro zstate-done? [r]
  `(let [^Object r# ~r]
     (identical? StrategyDone (.getClass r#))))

(defmacro zstate-repeat? [r]
  `(let [^Object r# ~r]
     (identical? StrategyRepeat (.getClass r#))))

(defmacro zip? [z]
  `(let [^Object z# ~z]
     (identical? Zip (.getClass z#))))

(defn ->zipper [x]
  (if (zip? x)
    x
    (Zip. x -1 nil 0 0)))

(defmacro zup [z]
  `(let [^Zip z# ~z]
     (when-let [^Zip parent# (.parent z#)]
       (let [node# (.node z#)
             ^IPersistentVector level# (.node parent#)
             idx# (.idx z#)]
         (if (identical? node# (.nth level# idx#))
           parent#
           (Zip. (.assocN level# idx# node#) (.idx parent#) (.parent parent#) 0 (.depth parent#)))))))

(defmacro znode [z]
  `(let [^Zip z# ~z]
     (.node z#)))

(defmacro zbranch? [z]
  `(instance? IPersistentVector (znode ~z)))

(defmacro zleft [z]
  `(let [^Zip z# ~z]
     (when-let [^Zip parent# (zup z#)]
       (let [idx# (unchecked-dec-int (.idx z#))
             ^IPersistentVector level# (.node parent#)]
         (when-let [child-node# (.nth level# idx# nil)]
           (Zip. child-node# idx# parent# 0 (.depth z#)))))))

(defmacro zleft-no-edit [z]
  `(let [^Zip z# ~z]
     (when-let [^Zip parent# (.parent z#)]
       (let [idx# (unchecked-dec-int (.idx z#))
             ^IPersistentVector level# (.node parent#)]
         (when-let [child-node# (.nth level# idx# nil)]
           (Zip. child-node# idx# parent# 0 (.depth z#)))))))

(defmacro zright [z]
  `(let [^Zip z# ~z]
     (when-let [^Zip parent# (zup z#)]
       (let [idx# (unchecked-inc-int (.idx z#))
             ^IPersistentVector level# (.node parent#)]
         (when-let [child-node# (.nth level# idx# nil)]
           (Zip. child-node# idx# parent# 0 (.depth z#)))))))

(defmacro zright-no-edit [z]
  `(let [^Zip z# ~z]
     (when-let [^Zip parent# (.parent z#)]
       (let [idx# (unchecked-inc-int (.idx z#))
             ^IPersistentVector level# (.node parent#)]
         (when-let [child-node# (.nth level# idx# nil)]
           (Zip. child-node# idx# parent# 0 (.depth z#)))))))

(defmacro znth [z idx]
  `(let [^Zip z# ~z
         ^IPersistentVector node# (.node z#)]
     (when (instance? IPersistentVector node#)
       (let [idx# (if (neg? ~idx)
                    (unchecked-add-int (.count node#) ~idx)
                    ~idx)]
         (when-let [child-node# (.nth node# idx# nil)]
           (Zip. child-node# idx# z# 0 (unchecked-inc-int (.depth z#))))))))

(defmacro zdown [z]
  `(let [^Zip z# ~z
         ^IPersistentVector node# (.node z#)]
     (when (instance? IPersistentVector node#)
       (when-let [child-node# (.nth node# 0 nil)]
         (Zip. child-node# 0 z# 0 (unchecked-inc-int (.depth z#)))))))

(defmacro zups [z depth]
  `(loop [^Zip z# ~z]
     (if (= ~depth (.depth z#))
       z#
       (recur (zup z#)))))

(defmacro zroot [z]
  `(loop [^Zip z# ~z]
     (if-let [z# (zup z#)]
       (recur z#)
       (.node z#))))

(defmacro zright-or-up [z depth]
  `(loop [^Zip z# ~z]
     (if (= ~depth (.depth z#))
       (StrategyDone. z#)
       (or (zright z#)
           (recur (zup z#))))))

(defmacro znext
  ([z]
   `(let [^Zip z# ~z]
      (znext z# (.depth z#))))
  ([z depth]
   `(let [^Zip z# ~z]
      (or (zdown z#)
          (zright-or-up z# ~depth)))))

(defmacro zright-or-up-bu [z depth out-fn]
  `(loop [^Zip z# ~z]
     (if (= ~depth (.depth z#))
       (StrategyDone. z#)
       (when-let [^Zip z# (~out-fn z#)]
         (if (zstate-repeat? z#)
           (.z ^StrategyRepeat z#)
           (or (zright z#)
               (recur (zup z#))))))))

(defmacro znext-bu [z depth out-fn]
  `(let [^Zip z# ~z]
     (or (zdown z#)
         (zright-or-up-bu z# ~depth ~out-fn))))

(defmacro zright-or-up-no-edit [z depth]
  `(loop [z# ~z]
     (when-not (= ~depth (.depth z#))
       (or (zright-no-edit z#)
           (recur (.parent z#))))))

(defmacro znext-no-edit [z depth]
  `(let [^Zip z# ~z]
     (or (zdown z#)
         (zright-or-up-no-edit z# ~depth))))

(defmacro zprev [z]
  `(let [^Zip z# ~z]
     (if-let [z# (zleft z#)]
       (loop [z# z#]
         (if-let [z# (znth z# -1)]
           (recur z#)
           z#))
       (zup z#))))

(defmacro zprev-no-edit [z]
  `(let [^Zip z# ~z]
     (if-let [^Zip z# (zleft-no-edit z#)]
       (loop [z# z#]
         (if-let [z# (znth z# -1)]
           (recur z#)
           z#))
       (.parent z#))))

(defmacro zchild-idx [z]
  `(let [^Zip z# ~z]
     (.idx z#)))

(defmacro zchildren [z]
  `(vec (.node ^Zip (.parent ~z))))

(defmacro zrights [z]
  `(let [^Zip z# ~z]
     (seq (subvec (zchildren z#) (unchecked-inc-int (.idx z#))))))

(defmacro zlefts [z]
  `(let [^Zip z# ~z]
     (seq (subvec (zchildren z#) 0 (.idx z#)))))

(defmacro zreplace [z x]
  `(let [^Zip z# ~z
         x# ~x]
     (if (identical? (.node z#) x#)
       z#
       (Zip. x# (.idx z#) (.parent z#) 0 (.depth z#)))))

;; Zipper pattern matching

(derive ::m/zip ::m/vector)

(defmethod m/nth-inline ::m/zip
  [t ocr i]
  `(let [^Zip z# ~ocr]
     (Zip. (.nth ^IPersistentVector (.node z#) ~i) ~i z# 0 (unchecked-inc-int (.depth z#)))))

(defmethod m/count-inline ::m/zip
  [t ocr]
  `(let [^Zip z# ~ocr
         n# (.node z#)]
     (if (instance? IPersistentVector n#)
       (.count ^IPersistentVector n#)
       0)))

(defmethod m/subvec-inline ::m/zip
  ([_ ocr start] (throw (UnsupportedOperationException.)))
  ([_ ocr start end] (throw (UnsupportedOperationException.))))

(defmethod m/tag ::m/zip
  [_] "core2.rewrite.Zip")

(defmacro zmatch {:style/indent 1} [z & clauses]
  (let [pattern+exprs (partition 2 clauses)
        else-clause (when (odd? (count clauses))
                      (last clauses))
        variables (->> pattern+exprs
                       (map first)
                       (flatten)
                       (filter symbol?))
        zip-matches? (some (comp :z meta) variables)
        shadowed-locals (filter (set variables) (keys &env))]
    (when-not (empty? shadowed-locals)
      (throw (IllegalArgumentException. (str "Match variables shadow locals: " (set shadowed-locals)))))
    (if-not zip-matches?
      `(let [z# ~z
             node# (if (zip? z#)
                     (znode z#)
                     z#)]
         (m/match node#
                  ~@(->> (for [[pattern expr] pattern+exprs]
                           [pattern expr])
                         (reduce into []))
                  :else ~else-clause))
      `(m/matchv ::m/zip [(->zipper ~z)]
                 ~@(->> (for [[pattern expr] pattern+exprs]
                          [[(w/postwalk
                             (fn [x]
                               (cond
                                 (symbol? x)
                                 (if (or (= '_ x)
                                         (:z (meta x)))
                                   x
                                   {:node x})

                                 (vector? x)
                                 x

                                 :else
                                 {:node x}))
                             pattern)]
                           expr])
                        (reduce into []))
                 :else ~else-clause))))

;; Attribute Grammar spike.

;; See related literature:
;; https://inkytonik.github.io/kiama/Attribution (no code is borrowed)
;; https://arxiv.org/pdf/2110.07902.pdf
;; https://haslab.uminho.pt/prmartins/files/phd.pdf
;; https://github.com/christoff-buerger/racr

(defn ctor [^Zip ag]
  (when ag
    (let [node (znode ag)]
      (when (vector? node)
        (.nth ^IPersistentVector node 0 nil)))))

(defn ctor? [kw ag]
  (= kw (ctor ag)))

(defmacro vector-zip [x]
  `(->zipper ~x))
(defmacro node [x]
  `(znode ~x))
(defmacro root [x]
  `(zroot ~x))
(defmacro left [x]
  `(zleft ~x))
(defmacro right [x]
  `(zright ~x))
(defmacro prev [x]
  `(zprev ~x))
(defmacro parent [x]
  `(zup ~x))
(defmacro $ [x n]
  `(znth ~x ~n))
(defmacro child-idx [x]
  `(zchild-idx ~x))

(defmacro lexeme [ag n]
  `(some-> ($ ~ag ~n) (znode)))

(defmacro first-child? [ag]
  `(= 1 (count (zlefts ~ag))))

(defmacro left-or-parent [ag]
  `(let [^Zip ag# ~ag]
     (if (first-child? ag#)
       (parent ag#)
       (zleft ag#))))

(defmacro zcase {:style/indent 1} [ag & body]
  `(case (ctor ~ag) ~@body))

(def ^:dynamic *memo*)

(defn zmemoize-with-inherited [f]
  (fn [x]
    (let [^Map memo *memo*
          memo-box (Box. nil)]
      (loop [^Zip x x
             inherited? false]
        (let [k (MapEntry/create f x)
              ^Box stored-memo-box (.getOrDefault memo k memo-box)]
          (if (identical? memo-box stored-memo-box)
            (let [v (f x)]
              (.put memo k memo-box)
              (if (= ::inherit v)
                (some-> x (parent) (recur true))
                (do (set! (.val memo-box) v)
                    v)))
            (let [v (.val stored-memo-box)]
              (when inherited?
                (set! (.val memo-box) v))
              v)))))))

(defn zmemoize [f]
  (fn [^Zip x]
    (let [^Map memo *memo*
          k (MapEntry/create f x)
          v (.getOrDefault memo k ::not-found)]
      (if (= ::not-found v)
        (let [v (f x)]
          (if (= ::inherit v)
            (some-> x (parent) (recur))
            (doto v
              (->> (.put memo k)))))
        v))))

;; Strategic Zippers based on Ztrategic

;; https://arxiv.org/pdf/2110.07902.pdf
;; https://www.di.uminho.pt/~joost/publications/SBLP2004LectureNotes.pdf

;; Strafunski:
;; https://www.di.uminho.pt/~joost/publications/AStrafunskiApplicationLetter.pdf
;; https://arxiv.org/pdf/cs/0212048.pdf
;; https://arxiv.org/pdf/cs/0204015.pdf
;; https://arxiv.org/pdf/cs/0205018.pdf

;; Type Preserving

(defn choice-tp
  ([x y]
   (fn [z]
     (choice-tp x y z)))
  ([x y z]
   (if-let [z (x z)]
     z
     (y z))))

(defn full-td-tp [f ^Zip z]
  (let [depth (.depth z)]
    (loop [z z]
      (when-let [^Zip z (f z)]
        (let [z (znext z depth)]
          (if (zstate-done? z)
            (.z ^StrategyDone z)
            (recur z)))))))

(defn full-bu-tp [f ^Zip z]
  (let [depth (.depth z)]
    (loop [^Zip z z]
      (when-let [z (znext-bu z depth f)]
        (if (zstate-done? z)
          (f (.z ^StrategyDone z))
          (recur z))))))

(defn once-td-tp [f ^Zip z]
  (let [depth (.depth z)]
    (loop [z z]
      (if-let [^Zip z (f z)]
        (zups z depth)
        (when-let [z (znext z depth)]
          (when-not (zstate-done? z)
            (recur z)))))))

(defn z-try-apply-m [f]
  (fn [^Zip z]
    (some->> (f z)
             (zreplace z))))

(defn adhoc-tp [f g]
  (choice-tp (z-try-apply-m g) f))

(defn id-tp [x] x)

(defn fail-tp [_])

(def mono-tp (partial adhoc-tp fail-tp))

(defn innermost [f ^Zip z]
  (let [depth (.depth z)
        inner-f (fn [z]
                  (if-let [z (f z)]
                    (StrategyRepeat. z)
                    z))]
    (loop [^Zip z z]
      (if-let [z (znext-bu z depth inner-f)]
        (if (zstate-done? z)
          (if-let [z (f (.z ^StrategyDone z))]
            (recur z)
            (.z ^StrategyDone z))
          (recur z))))))

(def topdown full-td-tp)

(def bottomup full-bu-tp)

;; Type Unifying

(defn- into-array-list
  ([] (ArrayList. 0))
  ([x] (vec x))
  ([^List x ^List y]
   (if y
     (doto x
       (.addAll y))
     x)))

(defn collect
  ([f z]
   (collect f into-array-list z))
  ([f m ^Zip z]
   (let [depth (.depth z)]
     (loop [z z
            acc (m)]
       (let [acc (if-some [x (f z)]
                   (m acc x)
                   acc)]
         (if-let [z (znext-no-edit z depth)]
           (recur z acc)
           (m acc)))))))

(defn collect-stop
  ([f z]
   (collect-stop f into-array-list z))
  ([f m ^Zip z]
   (let [depth (.depth z)]
     (loop [z z
            acc (m)]
       (let [x (f z)
             stop? (some? x)
             acc (if stop?
                   (m acc x)
                   acc)]
         (if-let [z (if stop?
                      (zright-or-up-no-edit z depth)
                      (znext-no-edit z depth))]
           (recur z acc)
           (m acc)))))))

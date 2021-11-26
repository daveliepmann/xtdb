(ns core2.expression
  (:require [clojure.set :as set]
            [clojure.string :as str]
            core2.operator.project
            core2.operator.select
            [core2.types :as types]
            [core2.util :as util]
            [core2.vector.indirect :as iv]
            [core2.vector.writer :as vw])
  (:import clojure.lang.MapEntry
           core2.operator.project.ProjectionSpec
           [core2.operator.select IColumnSelector IRelationSelector]
           core2.types.LegType
           [core2.vector IIndirectRelation IIndirectVector]
           [core2.vector.extensions KeywordType UuidType]
           java.lang.reflect.Method
           java.nio.ByteBuffer
           java.nio.charset.StandardCharsets
           [java.time Duration Instant ZoneOffset]
           [java.time.temporal ChronoField ChronoUnit]
           [java.util Date LinkedHashMap]
           [org.apache.arrow.vector BaseVariableWidthVector DurationVector ValueVector]
           org.apache.arrow.vector.complex.DenseUnionVector
           [org.apache.arrow.vector.types TimeUnit Types Types$MinorType]
           [org.apache.arrow.vector.types.pojo ArrowType ArrowType$Binary ArrowType$Bool ArrowType$Duration ArrowType$FloatingPoint ArrowType$Int ArrowType$Null ArrowType$Timestamp ArrowType$Utf8 Field FieldType]
           org.roaringbitmap.RoaringBitmap))

(set! *unchecked-math* :warn-on-boxed)

(defn expand-variadics [{:keys [op] :as expr}]
  (letfn [(expand-l [{:keys [f args]}]
            (reduce (fn [acc arg]
                      {:op :call, :f f, :args [acc arg]})
                    args))

          (expand-r [{:keys [f args]}]
            (reduce (fn [acc arg]
                      {:op :call, :f f, :args [arg acc]})
                    (reverse args)))]

    (or (when (= :call op)
          (let [{:keys [f args]} expr]
            (when (> (count args) 2)
              (cond
                (contains? '#{+ - * /} f) (expand-l expr)
                (contains? '#{and or} f) (expand-r expr)
                (contains? '#{<= < = != > >=} f) (expand-r {:op :call
                                                            :f 'and
                                                            :args (for [[x y] (partition 2 1 args)]
                                                                    {:op :call
                                                                     :f f
                                                                     :args [x y]})})))))

        expr)))

(defn form->expr [form params]
  (cond
    (symbol? form) (if (contains? params form)
                     {:op :param, :param form}
                     {:op :variable, :variable form})

    (sequential? form) (let [[f & args] form]
                         (case f
                           'if (do
                                 (when-not (= 3 (count args))
                                   (throw (IllegalArgumentException. (str "'if' expects 3 args: " (pr-str form)))))

                                 (let [[pred then else] args]
                                   {:op :if,
                                    :pred (form->expr pred params),
                                    :then (form->expr then params),
                                    :else (form->expr else params)}))

                           (-> {:op :call, :f f, :args (mapv #(form->expr % params) args)}
                               expand-variadics)))

    :else {:op :literal, :literal form}))

(defmulti direct-child-exprs
  (fn [{:keys [op] :as expr}]
    op)
  :default ::default)

(defmethod direct-child-exprs ::default [_] #{})
(defmethod direct-child-exprs :if [{:keys [pred then else]}] [pred then else])
(defmethod direct-child-exprs :call [{:keys [args]}] args)

(defmulti postwalk-expr
  (fn [f {:keys [op] :as expr}]
    op)
  :default ::default)

(defmethod postwalk-expr ::default [f expr]
  (f expr))

(defmethod postwalk-expr :if [f {:keys [pred then else]}]
  (f {:op :if
      :pred (postwalk-expr f pred)
      :then (postwalk-expr f then)
      :else (postwalk-expr f else)}))

(defmethod postwalk-expr :call [f {expr-f :f, :keys [args]}]
  (f {:op :call
      :f expr-f
      :args (mapv #(postwalk-expr f %) args)}))

(defn lits->params [expr]
  (->> expr
       (postwalk-expr (fn [{:keys [op] :as expr}]
                        (case op
                          :literal (let [{:keys [literal]} expr
                                         sym (gensym 'literal)]
                                     (-> {:op :param, :param sym, :literal literal}
                                         (vary-meta (fnil into {})
                                                    {:literals {sym literal}
                                                     :params #{sym}})))

                          :param (-> expr
                                     (vary-meta (fnil into {})
                                                {:params #{(:param expr)}}))

                          (let [child-exprs (direct-child-exprs expr)]
                            (-> expr
                                (vary-meta (fnil into {})
                                           {:literals (->> child-exprs
                                                           (into {} (mapcat (comp :literals meta))))
                                            :params (->> child-exprs
                                                         (into #{} (mapcat (comp :params meta))))}))))))))

(defn expr-seq [expr]
  (lazy-seq
   (cons expr (mapcat expr-seq (direct-child-exprs expr)))))

(defn with-tag [sym tag]
  (-> sym
      (vary-meta assoc :tag (if (symbol? tag)
                              tag
                              (symbol (.getName ^Class tag))))))

(defn variables [expr]
  (->> (expr-seq expr)
       (into [] (comp (filter (comp #(= :variable %) :op))
                      (map :variable)
                      (distinct)))))

(def type->cast
  {ArrowType$Bool/INSTANCE 'boolean
   (.getType Types$MinorType/TINYINT) 'byte
   (.getType Types$MinorType/SMALLINT) 'short
   (.getType Types$MinorType/INT) 'int
   types/bigint-type 'long
   types/float8-type 'double
   types/timestamp-micro-tz-type 'long
   types/duration-micro-type 'long})

(def idx-sym (gensym "idx"))

(defmulti codegen-expr
  "Returns a map containing
    * `:return-types` (set)
    * `:continue` (fn).
      Returned fn expects a function taking a single return-type and the emitted code for that return-type.
      May be called multiple times if there are multiple return types.
      Returned fn returns the emitted code for the expression (including the code supplied by the callback)."
  (fn [{:keys [op]} {:keys [var->types]}]
    op))

(defn resolve-string ^String [x]
  (cond
    (instance? ByteBuffer x)
    (str (.decode StandardCharsets/UTF_8 (.duplicate ^ByteBuffer x)))

    (bytes? x)
    (String. ^bytes x StandardCharsets/UTF_8)

    (string? x)
    x))

(defmethod codegen-expr :param [{:keys [param] :as expr} {:keys [param->type]}]
  (let [return-type (or (get param->type param)
                        (throw (IllegalArgumentException. (str "parameter not provided: " param))))]
    (into {:return-types #{return-type}
           :continue (fn [f]
                       (f return-type param))}
          (select-keys expr #{:literal}))))

(defn compare-nio-buffers-unsigned ^long [^ByteBuffer x ^ByteBuffer y]
  (let [rem-x (.remaining x)
        rem-y (.remaining y)
        limit (min rem-x rem-y)
        char-limit (bit-shift-right limit 1)
        diff (.compareTo (.limit (.asCharBuffer x) char-limit)
                         (.limit (.asCharBuffer y) char-limit))]
    (if (zero? diff)
      (loop [n (bit-and-not limit 1)]
        (if (= n limit)
          (- rem-x rem-y)
          (let [x-byte (.get x n)
                y-byte (.get y n)]
            (if (= x-byte y-byte)
              (recur (inc n))
              (Byte/compareUnsigned x-byte y-byte)))))
      diff)))

(defn element->nio-buffer ^java.nio.ByteBuffer [^BaseVariableWidthVector vec ^long idx]
  (let [value-buffer (.getDataBuffer vec)
        offset-buffer (.getOffsetBuffer vec)
        offset-idx (* idx BaseVariableWidthVector/OFFSET_WIDTH)
        offset (.getInt offset-buffer offset-idx)
        end-offset (.getInt offset-buffer (+ offset-idx BaseVariableWidthVector/OFFSET_WIDTH))]
    (.nioBuffer value-buffer offset (- end-offset offset))))

(defmulti get-value-form
  (fn [arrow-type vec-sym idx-sym]
    (class arrow-type)))

(defmethod get-value-form ArrowType$Bool [_ vec-sym idx-sym] `(= 1 (.get ~vec-sym ~idx-sym)))
(defmethod get-value-form ArrowType$FloatingPoint [_ vec-sym idx-sym] `(.get ~vec-sym ~idx-sym))
(defmethod get-value-form ArrowType$Int [_ vec-sym idx-sym] `(.get ~vec-sym ~idx-sym))
(defmethod get-value-form ArrowType$Timestamp [_ vec-sym idx-sym] `(.get ~vec-sym ~idx-sym))
(defmethod get-value-form ArrowType$Duration [_ vec-sym idx-sym] `(DurationVector/get (.getDataBuffer ~vec-sym) ~idx-sym))
(defmethod get-value-form ArrowType$Utf8 [_ vec-sym idx-sym] `(element->nio-buffer ~vec-sym ~idx-sym))
(defmethod get-value-form ArrowType$Binary [_ vec-sym idx-sym] `(element->nio-buffer ~vec-sym ~idx-sym))
(defmethod get-value-form :default [_ vec-sym idx-sym] `(normalize-union-value (.getObject ~vec-sym ~idx-sym)))

(defmulti extension-type-literal-form class)

(defmethod extension-type-literal-form KeywordType [_] `KeywordType/INSTANCE)
(defmethod extension-type-literal-form UuidType [_] `UuidType/INSTANCE)

(defmethod codegen-expr :variable [{:keys [variable]} {:keys [var->types]}]
  (let [field-types (or (get var->types variable)
                        (throw (AssertionError. (str "unknown variable: " variable))))]
    (if-not (vector? field-types)
      (let [^FieldType field-type field-types
            arrow-type (.getType field-type)
            vec-type (types/arrow-type->vector-type arrow-type)

            code `(let [~idx-sym (.getIndex ~variable ~idx-sym)
                        ~(-> variable (with-tag vec-type)) (.getVector ~variable)]
                    ~(get-value-form arrow-type variable idx-sym))]

        {:return-types #{arrow-type}
         :continue (fn [f]
                     (f arrow-type code))})

      {:return-types
       (->> field-types
            (into #{} (mapcat (fn [^FieldType field-type]
                                (cond-> #{(.getType field-type)}
                                  (.isNullable field-type) (conj ArrowType$Null/INSTANCE))))))

       :continue
       (fn [f]
         (let [var-idx-sym (gensym 'var-idx)
               var-vec-sym (gensym 'var-vec)]
           `(let [~var-idx-sym (.getIndex ~variable ~idx-sym)
                  ~(-> var-vec-sym (with-tag DenseUnionVector)) (.getVector ~variable)]
              (case (.getTypeId ~var-vec-sym ~var-idx-sym)
                ~@(->> field-types
                       (map-indexed
                        (fn [type-id ^FieldType field-type]
                          (let [arrow-type (.getType field-type)]
                            [type-id
                             (f arrow-type (get-value-form arrow-type
                                                           (-> `(.getVectorByType ~var-vec-sym ~type-id)
                                                               (with-tag (types/arrow-type->vector-type arrow-type)))
                                                           `(.getOffset ~var-vec-sym ~var-idx-sym)))])))
                       (apply concat))))))})))

(defmethod codegen-expr :if [{:keys [pred then else]} opts]
  (let [{p-rets :return-types, p-cont :continue} (codegen-expr pred opts)
        {t-rets :return-types, t-cont :continue} (codegen-expr then opts)
        {e-rets :return-types, e-cont :continue} (codegen-expr else opts)
        return-types (set/union t-rets e-rets)]
    (when-not (= #{ArrowType$Bool/INSTANCE} p-rets)
      (throw (IllegalArgumentException. (str "pred expression doesn't return boolean "
                                             (pr-str p-rets)))))

    {:return-types return-types
     :continue (let [p-code (p-cont (fn [_ code] code))]
                 (if (= 1 (count return-types))
                   ;; only generate the surrounding code once if we're monomorphic
                   (fn [f]
                     (f (first return-types)
                        `(if ~p-code
                           ~(t-cont (fn [_ code] code))
                           ~(e-cont (fn [_ code] code)))))
                   (fn [f]
                     `(if ~p-code
                        ~(t-cont f)
                        ~(e-cont f)))))}))

(defmulti codegen-call
  "Expects a map containing both the expression and an `:arg-types` key - a vector of ArrowTypes.
   This `:arg-types` vector should be monomorphic - if the args are polymorphic, call this multimethod multiple times.

   Returns a map containing
    * `:return-types` (set)
    * `:continue-call` (fn).
      Returned fn expects:
      * a function taking a single return-type and the emitted code for that return-type.
      * the code for the arguments to the call
      May be called multiple times if there are multiple return types.
      Returned fn returns the emitted code for the expression (including the code supplied by the callback)."
  (fn [{:keys [f arg-types]}]
    (vec (cons (keyword (name f)) (map class arg-types))))
  :hierarchy #'types/arrow-type-hierarchy)

(defmethod codegen-expr :call [{:keys [args] :as expr} opts]
  (let [emitted-args (mapv #(codegen-expr % opts) args)
        all-arg-types (reduce (fn [acc {:keys [return-types]}]
                                (for [el acc
                                      return-type return-types]
                                  (conj el return-type)))
                              [[]]
                              emitted-args)

        emitted-calls (->> (for [arg-types all-arg-types]
                             (MapEntry/create arg-types
                                              (codegen-call (assoc expr
                                                                   :args emitted-args
                                                                   :arg-types arg-types))))
                           (into {}))

        return-types (->> emitted-calls
                          (into #{} (mapcat (comp :return-types val))))]

    {:return-types return-types
     :continue (fn continue-call-expr [handle-emitted-expr]
                 (let [build-args-then-call
                       (reduce (fn step [build-next-arg {continue-this-arg :continue}]
                                 ;; step: emit this arg, and pass it through to the inner build-fn
                                 (fn continue-building-args [arg-types emitted-args]
                                   (continue-this-arg (fn [arg-type emitted-arg]
                                                        (build-next-arg (conj arg-types arg-type)
                                                                        (conj emitted-args emitted-arg))))))

                               ;; innermost fn - we're done, call continue-call for these types
                               (fn call-with-built-args [arg-types emitted-args]
                                 (let [{:keys [continue-call]} (get emitted-calls arg-types)]
                                   (continue-call handle-emitted-expr emitted-args)))

                               ;; reverse because we're working inside-out
                               (reverse emitted-args))]

                   (build-args-then-call [] [])))}))

(defmethod codegen-call [:= ::types/Number ::types/Number] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(== ~@emitted-args)))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:= ::types/Object ::types/Object] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(= ~@emitted-args)))
   :return-types #{types/bool-type}})

(prefer-method codegen-call [:= ::types/Number ::types/Number] [:= ::types/Object ::types/Object])

(defmethod codegen-call [:!= ::types/Object ::types/Object] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(not= ~@emitted-args)))
   :return-types #{types/bool-type}})

(prefer-method codegen-call [:!= ::types/Number ::types/Number] [:!= ::types/Object ::types/Object])

(defmethod codegen-call [:< ::types/Number ::types/Number] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(< ~@emitted-args)))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:< ArrowType$Timestamp ArrowType$Timestamp] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(< ~@emitted-args)))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:< ArrowType$Duration ArrowType$Duration] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(< ~@emitted-args)))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:< ::types/Object ::types/Object] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(neg? (compare ~@emitted-args))))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:< ArrowType$Binary ArrowType$Binary] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(neg? (compare-nio-buffers-unsigned ~@emitted-args))))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:< ArrowType$Utf8 ArrowType$Utf8] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(neg? (compare-nio-buffers-unsigned ~@emitted-args))))
   :return-types #{types/bool-type}})

(prefer-method codegen-call [:< ::types/Number ::types/Number] [:< ::types/Object ::types/Object])
(prefer-method codegen-call [:< ArrowType$Timestamp ArrowType$Timestamp] [:< ::types/Object ::types/Object])
(prefer-method codegen-call [:< ArrowType$Duration ArrowType$Duration] [:< ::types/Object ::types/Object])
(prefer-method codegen-call [:< ArrowType$Utf8 ArrowType$Utf8] [:< ::types/Object ::types/Object])

(defmethod codegen-call [:<= ::types/Number ::types/Number] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(<= ~@emitted-args)))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:<= ArrowType$Timestamp ArrowType$Timestamp] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(<= ~@emitted-args)))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:<= ArrowType$Duration ArrowType$Duration] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(<= ~@emitted-args)))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:<= ::types/Object ::types/Object] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(not (pos? (compare ~@emitted-args)))))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:<= ArrowType$Binary ArrowType$Binary] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(not (pos? (compare-nio-buffers-unsigned ~@emitted-args)))))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:<= ArrowType$Utf8 ArrowType$Utf8] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(not (pos? (compare-nio-buffers-unsigned ~@emitted-args)))))
   :return-types #{types/bool-type}})

(prefer-method codegen-call [:<= ::types/Number ::types/Number] [:<= ::types/Object ::types/Object])
(prefer-method codegen-call [:<= ArrowType$Timestamp ArrowType$Timestamp] [:<= ::types/Object ::types/Object])
(prefer-method codegen-call [:<= ArrowType$Duration ArrowType$Duration] [:<= ::types/Object ::types/Object])
(prefer-method codegen-call [:<= ArrowType$Utf8 ArrowType$Utf8] [:<= ::types/Object ::types/Object])

(defmethod codegen-call [:> ::types/Number ::types/Number] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(> ~@emitted-args)))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:> ArrowType$Timestamp ArrowType$Timestamp] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(> ~@emitted-args)))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:> ArrowType$Duration ArrowType$Duration] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(> ~@emitted-args)))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:> ArrowType$Binary ArrowType$Binary] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(pos? (compare-nio-buffers-unsigned ~@emitted-args))))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:> ArrowType$Utf8 ArrowType$Utf8] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(pos? (compare-nio-buffers-unsigned ~@emitted-args))))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:> ::types/Object ::types/Object] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(pos? (compare ~@emitted-args))))
   :return-types #{types/bool-type}})

(prefer-method codegen-call [:> ::types/Number ::types/Number] [:> ::types/Object ::types/Object])
(prefer-method codegen-call [:> ArrowType$Timestamp ArrowType$Timestamp] [:> ::types/Object ::types/Object])
(prefer-method codegen-call [:> ArrowType$Duration ArrowType$Duration] [:> ::types/Object ::types/Object])
(prefer-method codegen-call [:> ArrowType$Utf8 ArrowType$Utf8] [:> ::types/Object ::types/Object])


(defmethod codegen-call [:>= ::types/Number ::types/Number] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(>= ~@emitted-args)))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:>= ArrowType$Timestamp ArrowType$Timestamp] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(>= ~@emitted-args)))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:>= ArrowType$Duration ArrowType$Duration] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(>= ~@emitted-args)))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:>= ArrowType$Binary ArrowType$Binary] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(not (neg? (compare-nio-buffers-unsigned ~@emitted-args)))))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:>= ArrowType$Utf8 ArrowType$Utf8] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(not (neg? (compare-nio-buffers-unsigned ~@emitted-args)))))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:>= ::types/Object ::types/Object] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(not (neg? (compare ~@emitted-args)))))
   :return-types #{types/bool-type}})

(prefer-method codegen-call [:>= ::types/Number ::types/Number] [:>= ::types/Object ::types/Object])
(prefer-method codegen-call [:>= ArrowType$Timestamp ArrowType$Timestamp] [:>= ::types/Object ::types/Object])
(prefer-method codegen-call [:>= ArrowType$Duration ArrowType$Duration] [:>= ::types/Object ::types/Object])
(prefer-method codegen-call [:>= ArrowType$Utf8 ArrowType$Utf8] [:>= ::types/Object ::types/Object])


(defmethod codegen-call [:and ArrowType$Bool ArrowType$Bool] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(and ~@emitted-args)))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:or ArrowType$Bool ArrowType$Bool] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(or ~@emitted-args)))
   :return-types #{types/bool-type}})

(defmethod codegen-call [:not ArrowType$Bool] [_]
  {:continue-call (fn [f emitted-args]
                    (f types/bool-type
                       `(not ~@emitted-args)))
   :return-types #{types/bool-type}})

(defn- with-math-integer-cast
  "java.lang.Math's functions only take int or long, so we introduce an up-cast if need be"
  [^ArrowType$Int arrow-type emitted-args]
  (let [arg-cast (if (= 64 (.getBitWidth arrow-type)) 'long 'int)]
    (map #(list arg-cast %) emitted-args)))

(defmethod codegen-call [:+ ArrowType$Int ArrowType$Int] [{:keys [arg-types]}]
  (let [^ArrowType$Int return-type (types/least-upper-bound arg-types)]
    {:return-types #{return-type}
     :continue-call (fn [f emitted-args]
                      (f return-type
                         (list (type->cast return-type)
                               `(Math/addExact ~@(with-math-integer-cast return-type emitted-args)))))}))

(defmethod codegen-call [:+ ::types/Number ::types/Number] [{:keys [arg-types]}]
  (let [return-type (types/least-upper-bound arg-types)]
    {:return-types #{return-type}
     :continue-call (fn [f emitted-args]
                      (f return-type `(+ ~@emitted-args)))}))

(defmethod codegen-call [:- ArrowType$Int ArrowType$Int] [{:keys [arg-types]}]
  (let [^ArrowType$Int return-type (types/least-upper-bound arg-types)]
    {:return-types #{return-type}
     :continue-call (fn [f emitted-args]
                      (f return-type
                         (list (type->cast return-type)
                               `(Math/subtractExact ~@(with-math-integer-cast return-type emitted-args)))))}))

(defmethod codegen-call [:- ::types/Number ::types/Number] [{:keys [arg-types]}]
  (let [return-type (types/least-upper-bound arg-types)]
    {:return-types #{return-type}
     :continue-call (fn [f emitted-args]
                      (f return-type `(- ~@emitted-args)))}))

(defmethod codegen-call [:- ArrowType$Int] [{[^ArrowType$Int x-type] :arg-types}]
  {:continue-call (fn [f emitted-args]
                    (f x-type
                       (list (type->cast x-type)
                             `(Math/negateExact ~@(with-math-integer-cast x-type emitted-args)))))
   :return-types #{x-type}})

(defmethod codegen-call [:- ::types/Number] [{[x-type] :arg-types}]
  {:return-types #{x-type}
   :continue-call (fn [f emitted-args]
                    (f x-type `(- ~@emitted-args)))})

(defmethod codegen-call [:* ArrowType$Int ArrowType$Int] [{:keys [arg-types]}]
  (let [^ArrowType$Int return-type (types/least-upper-bound arg-types)
        arg-cast (if (= 64 (.getBitWidth return-type)) 'long 'int)]
    {:return-types #{return-type}
     :continue-call (fn [f emitted-args]
                      (f return-type
                         (list (type->cast return-type)
                               `(Math/multiplyExact ~@(map #(list arg-cast %) emitted-args)))))}))

(defmethod codegen-call [:* ::types/Number ::types/Number] [{:keys [arg-types]}]
  (let [return-type (types/least-upper-bound arg-types)]
    {:return-types #{return-type}
     :continue-call (fn [f emitted-args]
                      (f return-type `(* ~@emitted-args)))}))

(defmethod codegen-call [:% ::types/Number ::types/Number] [{:keys [ arg-types]}]
  (let [return-type (types/least-upper-bound arg-types)]
    {:continue-call (fn [f emitted-args]
                      (f return-type
                         `(mod ~@emitted-args)))
     :return-types #{return-type}}))

(defmethod codegen-call [:/ ::types/Number ::types/Number] [{:keys [ arg-types]}]
  (let [return-type (types/least-upper-bound arg-types)]
    {:continue-call (fn [f emitted-args]
                      (f return-type
                         `(/ ~@emitted-args)))
     :return-types #{return-type}}))

(defmethod codegen-call [:/ ArrowType$Int ArrowType$Int] [{:keys [ arg-types]}]
  (let [return-type (types/least-upper-bound arg-types)]
    {:continue-call (fn [f emitted-args]
                      (f return-type
                         `(quot ~@emitted-args)))
     :return-types #{return-type}}))

(defmethod codegen-call [:like ::types/Object ArrowType$Utf8] [{[_ {:keys [literal]}] :args}]
  {:return-types #{types/bool-type}
   :continue-call (fn [f [haystack-code]]
                    (f types/bool-type
                       `(boolean (re-find ~(re-pattern (str "^" (str/replace literal #"%" ".*") "$"))
                                          (resolve-string ~haystack-code)))))})

(defmethod codegen-call [:substr ::types/Object ArrowType$Int ArrowType$Int] [_]
  {:return-types #{ArrowType$Utf8/INSTANCE}
   :continue-call (fn [f [x start length]]
                    (f ArrowType$Utf8/INSTANCE
                       `(ByteBuffer/wrap (.getBytes (subs (resolve-string ~x) (dec ~start) (+ (dec ~start) ~length))
                                                    StandardCharsets/UTF_8))))})

(defmethod codegen-call [:extract ArrowType$Utf8 ArrowType$Timestamp] [{[{field :literal} _] :args}]
  {:return-types #{types/bigint-type}
   :continue-call (fn [f [_ ts-code]]
                    (f types/bigint-type
                       `(.get (.atOffset ^Instant (util/micros->instant ~ts-code) ZoneOffset/UTC)
                              ~(case field
                                 "YEAR" `ChronoField/YEAR
                                 "MONTH" `ChronoField/MONTH_OF_YEAR
                                 "DAY" `ChronoField/DAY_OF_MONTH
                                 "HOUR" `ChronoField/HOUR_OF_DAY
                                 "MINUTE" `ChronoField/MINUTE_OF_HOUR))))})

(defmethod codegen-call [:date-trunc ArrowType$Utf8 ArrowType$Timestamp] [{[{field :literal} _] :args, [_ date-type] :arg-types}]
  {:return-types #{date-type}
   :continue-call (fn [f [_ x]]
                    (f date-type
                       `(util/instant->micros (.truncatedTo ^Instant (util/micros->instant ~x)
                                                            ~(case field
                                                               ;; can't truncate instants to years/months
                                                               ;; ah, but you can truncate ZDTs, which is what these really are. TODO
                                                               "DAY" `ChronoUnit/DAYS
                                                               "HOUR" `ChronoUnit/HOURS
                                                               "MINUTE" `ChronoUnit/MINUTES
                                                               "SECOND" `ChronoUnit/SECONDS
                                                               "MILLISECOND" `ChronoUnit/MILLIS
                                                               "MICROSECOND" `ChronoUnit/MICROS)))))})

(def ^:private type->arrow-type
  {Double/TYPE types/float8-type
   Long/TYPE types/bigint-type
   Boolean/TYPE ArrowType$Bool/INSTANCE})

(doseq [^Method method (.getDeclaredMethods Math)
        :let [math-op (.getName method)
              param-types (map type->arrow-type (.getParameterTypes method))
              return-type (get type->arrow-type (.getReturnType method))]
        :when (and return-type (every? some? param-types))]
  (defmethod codegen-call (vec (cons (keyword math-op) (map class param-types))) [_]
    {:return-types #{return-type}
     :continue-call (fn [f emitted-args]
                      (f return-type
                         `(~(symbol "Math" math-op) ~@emitted-args)))}))

(defn normalize-union-value [v]
  (cond
    (instance? Date v) (Math/multiplyExact (.getTime ^Date v) 1000)
    (instance? Instant v) (util/instant->micros v)
    (instance? Duration v) (.toMillis ^Duration v)
    (string? v) (ByteBuffer/wrap (.getBytes ^String v StandardCharsets/UTF_8))
    (bytes? v) (ByteBuffer/wrap v)
    :else v))

(defn normalise-params [expr params]
  (let [expr (lits->params expr)
        {expr-params :params, lits :literals} (meta expr)
        params (->> expr-params
                    (util/into-linked-map
                     (map (fn [param-k]
                            (MapEntry/create param-k
                                             (val (or (find params param-k)
                                                      (find lits param-k))))))))]
    {:expr expr
     :params params
     :param-types (->> params
                       (util/into-linked-map
                        (util/map-entries (fn [param-k param-v]
                                            (let [leg-type (types/value->leg-type param-v)
                                                  normalized-expr-type (normalize-union-value param-v)
                                                  primitive-tag (get type->cast (.arrowType (types/value->leg-type normalized-expr-type)))]
                                              (MapEntry/create (cond-> param-k
                                                                 primitive-tag (with-tag primitive-tag))
                                                               (.arrowType leg-type)))))))

     :emitted-params (->> params
                          (util/into-linked-map
                           (util/map-values (fn [_param-k param-v]
                                              (normalize-union-value param-v)))))}))

(defn- expression-in-cols [^IIndirectRelation in-rel expr]
  (->> (variables expr)
       (util/into-linked-map
        (map (fn [variable]
               (MapEntry/create variable (.vectorForName in-rel (name variable))))))))

(defmulti set-value-form
  (fn [arrow-type out-vec-sym idx-sym code]
    (class arrow-type)))

(defmethod set-value-form ArrowType$Bool [_ out-vec-sym idx-sym code]
  `(.set ~out-vec-sym ~idx-sym (if ~code 1 0)))

(defmethod set-value-form ArrowType$Int [_ out-vec-sym idx-sym code]
  `(.set ~out-vec-sym ~idx-sym ~code))

(defmethod set-value-form ArrowType$FloatingPoint [_ out-vec-sym idx-sym code]
  `(.set ~out-vec-sym ~idx-sym (double ~code)))

(defmethod set-value-form ArrowType$Utf8 [_ out-vec-sym idx-sym code]
  `(let [buf# ~code]
     (.setSafe ~out-vec-sym ~idx-sym buf#
               (.position buf#) (.remaining buf#))))

(defmethod set-value-form ArrowType$Binary [_ out-vec-sym idx-sym code]
  `(let [buf# ~code]
     (.setSafe ~out-vec-sym ~idx-sym buf#
               (.position buf#) (.remaining buf#))))

(defmethod set-value-form ArrowType$Timestamp [_ out-vec-sym idx-sym code]
  `(.set ~out-vec-sym ~idx-sym ~code))

(defmethod set-value-form ArrowType$Duration [_ out-vec-sym idx-sym code]
  `(.set ~out-vec-sym ~idx-sym ~code))

(def ^:private out-vec-sym (gensym 'out-vec))
(def ^:private out-writer-sym (gensym 'out-writer-sym))

(defn- arrow-type-literal-form [^ArrowType arrow-type]
  (letfn [(timestamp-type-literal [time-unit-literal]
            `(ArrowType$Timestamp. ~time-unit-literal ~(.getTimezone ^ArrowType$Timestamp arrow-type)))]
    (let [minor-type-name (.name (Types/getMinorTypeForArrowType arrow-type))]
      (case minor-type-name
        "DURATION" `(ArrowType$Duration. ~(symbol (name `TimeUnit) (.name (.getUnit ^ArrowType$Duration arrow-type))))

        "TIMESTAMPSECTZ" (timestamp-type-literal `TimeUnit/SECOND)
        "TIMESTAMPMILLITZ" (timestamp-type-literal `TimeUnit/MILLISECOND)
        "TIMESTAMPMICROTZ" (timestamp-type-literal `TimeUnit/MICROSECOND)
        "TIMESTAMPNANOTZ" (timestamp-type-literal `TimeUnit/NANOSECOND)

        "EXTENSIONTYPE" `~(extension-type-literal-form arrow-type)

        ;; TODO there are other minor types that don't have a single corresponding ArrowType
        `(.getType ~(symbol (name 'org.apache.arrow.vector.types.Types$MinorType) minor-type-name))))))

(defn- write-value-out-code [return-types]
  (if (= 1 (count return-types))
    {:write-value-out!
     (fn [^ArrowType arrow-type code]
       (let [vec-type (types/arrow-type->vector-type arrow-type)]
         (set-value-form arrow-type
                         (-> `(.getVector ~out-writer-sym)
                             (with-tag vec-type))
                         `(.getPosition ~out-writer-sym)
                         code)))}

    (let [->writer-sym (->> return-types
                            (into {} (map (juxt identity (fn [_] (gensym 'writer))))))]
      {:writer-bindings
       (->> (cons [out-writer-sym `(.asDenseUnion ~out-writer-sym)]
                  (for [[arrow-type writer-sym] ->writer-sym]
                    [writer-sym `(.writerForType ~out-writer-sym
                                                 ;; HACK: pass leg-types through instead
                                                 (LegType. ~(arrow-type-literal-form arrow-type)))]))
            (apply concat))

       :write-value-out!
       (fn [^ArrowType arrow-type code]
         (let [writer-sym (->writer-sym arrow-type)
               vec-type (types/arrow-type->vector-type arrow-type)]
           `(do
              (.startValue ~writer-sym)
              ~(set-value-form arrow-type
                               (-> `(.getVector ~writer-sym)
                                   (with-tag vec-type))
                               `(.getPosition ~writer-sym)
                               code)
              (.endValue ~writer-sym))))})))

(def ^:private memo-generate-projection
  (-> (fn [expr var->types param-types]
        (let [variable-syms (->> (keys var->types)
                                 (mapv #(with-tag % IIndirectVector)))

              codegen-opts {:var->types var->types, :param->type param-types}
              {:keys [return-types continue]} (codegen-expr expr codegen-opts)

              {:keys [writer-bindings write-value-out!]} (write-value-out-code return-types)]

          {:expr-fn (eval
                     `(fn [~(-> out-vec-sym (with-tag ValueVector))
                           [~@variable-syms] [~@(keys param-types)] ^long row-count#]

                        (let [~out-writer-sym (vw/vec->writer ~out-vec-sym)
                              ~@writer-bindings]
                          (.setValueCount ~out-vec-sym row-count#)
                          (dotimes [~idx-sym row-count#]
                            (.startValue ~out-writer-sym)
                            ~(continue write-value-out!)
                            (.endValue ~out-writer-sym)))))

           :return-types return-types}))
      (memoize)))

(defn field->value-types [^Field field]
  ;; potential duplication with LegType
  ;; probably doesn't work with structs, not convinced about lists either.
  (case (.name (Types/getMinorTypeForArrowType (.getType field)))
    "DENSEUNION"
    (mapv #(.getFieldType ^Field %) (.getChildren field))

    (.getFieldType field)))

(defn ->expression-projection-spec ^core2.operator.project.ProjectionSpec [^String col-name form params]
  (let [{:keys [expr param-types emitted-params]} (-> (form->expr form params)
                                                      (normalise-params params))]
    (reify ProjectionSpec
      (project [_ allocator in-rel]
        (let [in-cols (expression-in-cols in-rel expr)
              var-types (->> in-cols
                             (util/into-linked-map
                              (util/map-values (fn [_variable ^IIndirectVector read-col]
                                                 (assert read-col)
                                                 (field->value-types (.getField (.getVector read-col)))))))
              {:keys [expr-fn return-types]} (memo-generate-projection expr var-types param-types)
              ^ValueVector out-vec (if (= 1 (count return-types))
                                     (-> (FieldType. false (first return-types) nil)
                                         (.createNewSingleVector col-name allocator nil))
                                     (DenseUnionVector/empty col-name allocator))]
          (try
            (expr-fn out-vec (vals in-cols) (vals emitted-params) (.rowCount in-rel))
            (iv/->direct-vec out-vec)
            (catch Exception e
              (.close out-vec)
              (throw e))))))))

(def ^:private memo-generate-selection
  (-> (fn [expr var->types param-types]
        (let [variable-syms (->> (keys var->types)
                                 (mapv #(with-tag % IIndirectVector)))

              codegen-opts {:var->types var->types, :param->type param-types}
              {:keys [return-types continue]} (codegen-expr expr codegen-opts)
              acc-sym (gensym 'acc)]

          (assert (= #{ArrowType$Bool/INSTANCE} return-types))

          (eval
           `(fn [[~@variable-syms] [~@(keys param-types)] ^long row-count#]
              (let [~acc-sym (RoaringBitmap.)]
                (dotimes [~idx-sym row-count#]
                  ~(continue (fn [_ code]
                               `(when ~code
                                  (.add ~acc-sym ~idx-sym)))))
                ~acc-sym)))))
      (memoize)))

(defn ->expression-relation-selector ^core2.operator.select.IRelationSelector [form params]
  (let [{:keys [expr param-types emitted-params]} (-> (form->expr form params)
                                                      (normalise-params params))]
    (reify IRelationSelector
      (select [_ in-rel]
        (if (pos? (.rowCount in-rel))
          (let [in-cols (expression-in-cols in-rel expr)
                var-types (->> in-cols
                               (util/into-linked-map
                                (util/map-values (fn [_variable ^IIndirectVector read-col]
                                                   (field->value-types (.getField (.getVector read-col)))))))
                expr-fn (memo-generate-selection expr var-types param-types)]

            (expr-fn (vals in-cols) (vals emitted-params) (.rowCount in-rel)))
          (RoaringBitmap.))))))

(defn ->expression-column-selector ^core2.operator.select.IColumnSelector [form params]
  (let [{:keys [expr param-types emitted-params]} (-> (form->expr form params)
                                                      (normalise-params params))
        vars (variables expr)
        _ (assert (= 1 (count vars)))
        variable (first vars)]
    (reify IColumnSelector
      (select [_ in-col]
        (if (pos? (.getValueCount in-col))
          (let [in-cols (doto (LinkedHashMap.)
                          (.put variable in-col))
                var-types (doto (LinkedHashMap.)
                            (.put variable (field->value-types (.getField (.getVector in-col)))))
                expr-fn (memo-generate-selection expr var-types param-types)]
            (expr-fn (vals in-cols) (vals emitted-params) (.getValueCount in-col)))
          (RoaringBitmap.))))))

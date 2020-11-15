(ns malli.schema.impl
  "Macros and macro helpers used in schema.core."
  (:require
    [malli.core :as m]
    [clojure.string :as string]
    #?@(:cljs [goog.string.format
               [goog.object :as gobject]
               [goog.string :as gstring]]))
  #?(:cljs (:require-macros [malli.schema.impl :refer [char-map]])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Miscellaneous helpers

(defn type-of [x]
  #?(:clj (class x), :cljs (js* "typeof ~{}" x)))

(defn fn-schema-bearer
  "What class can we associate the fn schema with? In Clojure use the class of the fn; in
   cljs just use the fn itself."
  [f] #?(:clj (class f), :cljs f))

(defn format* [fmt & args]
  (apply #?(:clj format, :cljs gstring/format) fmt args))

(def max-value-length (atom 19))

(defmacro char-map []
  clojure.lang.Compiler/CHAR_MAP)

(defn unmunge
  "TODO: eventually use built in demunge in latest cljs."
  [s]
  (->> (char-map)
       (sort-by #(- (count (second %))))
       (reduce (fn [^String s [to from]] (string/replace s from (str to))) s)))

(defn fn-name
  "A meaningful name for a function that looks like its symbol, if applicable."
  [f]
  #?(:cljs (let [[_ s] (re-matches #"#object\[(.*)\]" (pr-str f))]
             (if (= "Function" s)
               "function"
               (->> s demunge (re-find #"[^/]+(?:$|(?=/+$))"))))
     :clj  (let [s (.getName (class f))
                 slash (.lastIndexOf s "$")
                 raw (unmunge
                       (if (>= slash 0)
                         (str (subs s 0 slash) "/" (subs s (inc slash)))
                         s))]
             (string/replace raw #"^clojure.core/" ""))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Registry for attaching schemas to classes, used for defn and defrecord

#?(:clj  (let [^java.util.Map +class-schemata+ (java.util.Collections/synchronizedMap (java.util.WeakHashMap.))]
           (defn declare-class-schema! [klass schema]
             "Globally set the schema for a class (above and beyond a simple instance? check).
            Use with care, i.e., only on classes that you control.  Also note that this
            schema only applies to instances of the concrete type passed, i.e.,
            (= (class x) klass), not (instance? klass x)."
             (assert (class? klass)
                     (format* "Cannot declare class schema for non-class %s" (class klass)))
             (.put +class-schemata+ klass schema))

           (defn class-schema [klass]
             "The last schema for a class set by declare-class-schema!, or nil."
             (.get +class-schemata+ klass)))

   :cljs (do (defn declare-class-schema! [klass schema]
               (gobject/set klass "schema$utils$schema" schema))

             (defn class-schema [klass]
               (gobject/get klass "schema$utils$schema"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities for fast-as-possible reference to use to turn fn schema validation on/off

(def use-fn-validation
  "Turn on run-time function validation for functions compiled when
   s/compile-fn-validation was true -- has no effect for functions compiled
   when it is false."
  ;; specialize in Clojure for performance
  #?(:clj  (java.util.concurrent.atomic.AtomicReference. false)
     :cljs (atom false)))

(defn cljs-env? [env] (boolean (:ns env)))
(defmacro if-cljs [then else] (if (cljs-env? &env) then else))

(defmacro error!
  "Generate a cross-platform exception appropriate to the macroexpansion context"
  ([s]
   `(if-cljs
      (throw (js/Error. ~s))
      (throw (RuntimeException. ~(with-meta s `{:tag java.lang.String})))))
  ([s m]
   (let [m (merge {:type :schema.core/error} m)]
     `(if-cljs
        (throw (ex-info ~s ~m))
        (throw (clojure.lang.ExceptionInfo. ~(with-meta s `{:tag java.lang.String}) ~m))))))

(defmacro assert!
  "Like assert, but throws a RuntimeException (in Clojure) and takes args to format."
  [form & format-args]
  `(when-not ~form
     (error! (format* ~@format-args))))

(defn maybe-split-first [pred s]
  (if (pred (first s))
    [(first s) (next s)]
    [nil s]))

(def primitive-sym? '#{float double boolean byte char short int long
                       floats doubles booleans bytes chars shorts ints longs objects})

(defn valid-tag? [env tag]
  (and (symbol? tag) (or (primitive-sym? tag) (class? (resolve env tag)))))

(defn normalized-metadata
  "Take an object with optional metadata, which may include a :tag,
   plus an optional explicit schema, and normalize the
   object to have a valid Clojure :tag plus a :schema field."
  [env imeta explicit-schema]
  (let [{:keys [tag s s? schema]} (meta imeta)]
    (assert! (not (or s s?)) "^{:s schema} style schemas are no longer supported.")
    (assert! (< (count (remove nil? [schema explicit-schema])) 2)
             "Expected single schema, got meta %s, explicit %s" (meta imeta) explicit-schema)
    (let [schema (or explicit-schema schema tag `any?)
          tag (let [t (or tag schema)] (if (valid-tag? env t) t))]
      (with-meta
        imeta (-> (or (meta imeta) {}) (dissoc :tag) (cond-> schema (assoc :schema schema) tag (assoc :tag tag)))))))

(defn extract-schema-form
  "Pull out the schema stored on a thing.  Public only because of its use in a public macro."
  [symbol]
  (let [s (:schema (meta symbol))]
    (assert! s "%s is missing a schema" symbol)
    s))

(defn extract-arrow-schematized-element
  "Take a nonempty seq, which may start like [a ...] or [a :- schema ...], and return
   a list of [first-element-with-schema-attached rest-elements]"
  [env s]
  (assert (seq s))
  (let [[f & more] s]
    (if (= :- (first more))
      [(normalized-metadata env f (second more)) (drop 2 more)]
      [(normalized-metadata env f nil) more])))

(defn process-arrow-schematized-args
  "Take an arg vector, in which each argument is followed by an optional :- schema,
   and transform into an ordinary arg vector where the schemas are metadata on the args."
  [env args]
  (loop [in args out []]
    (if (empty? in)
      out
      (let [[arg more] (extract-arrow-schematized-element env in)]
        (recur more (conj out arg))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Helpers for schematized fn/defn

(defn split-rest-arg [env bind]
  (let [[pre-& [_ rest-arg :as post-&]] (split-with #(not= % '&) bind)]
    (if (seq post-&)
      (do (assert! (= (count post-&) 2) "& must be followed by a single binding" (vec post-&))
          (assert! (or (symbol? rest-arg)
                       (and (vector? rest-arg)
                            (not-any? #{'&} rest-arg)))
                   "Bad & binding form: currently only bare symbols and vectors supported" (vec post-&))

          [(vec pre-&)
           (if (vector? rest-arg)
             (with-meta (process-arrow-schematized-args env rest-arg) (meta rest-arg))
             rest-arg)])
      [bind nil])))

(defn single-arg-schema-form [rest? [index arg]]
  #_`(~(if rest? `schema.core/optional `schema.core/one)
       ~(extract-schema-form arg)
       ~(if (symbol? arg)
          `'~arg
          `'~(symbol (str (if rest? "rest" "arg") index))))
  `[~(extract-schema-form arg) {:name ~(if (symbol? arg)
                                         `'~arg
                                         `'~(symbol (str (if rest? "rest" "arg") index)))}])


(defn simple-arglist-schema-form [rest? regular-args]
  (into [:tuple] (mapv (partial single-arg-schema-form rest?) (map-indexed vector regular-args))))

(defn rest-arg-schema-form [arg]
  (let [s (extract-schema-form arg)]
    (if (= s `schema.core/Any)
      (if (vector? arg)
        (simple-arglist-schema-form true arg)
        [`schema.core/Any])
      (do (assert! (vector? s) "Expected seq schema for rest args, got %s" s)
          s))))

(defn input-schema-form [regular-args rest-arg]
  (let [base (simple-arglist-schema-form false regular-args)]
    (if rest-arg (vec (concat base (rest-arg-schema-form rest-arg))) base)))

(defn apply-prepost-conditions
  "Replicate pre/postcondition logic from clojure.core/fn."
  [body]
  (let [[conds body] (maybe-split-first #(and (map? %) (next body)) body)]
    (concat (map (fn [c] `(assert ~c)) (:pre conds))
            (if-let [post (:post conds)]
              `((let [~'% (do ~@body)]
                  ~@(map (fn [c] `(assert ~c)) post)
                  ~'%))
              body))))

(def ^:dynamic *compile-fn-validation* (atom true))

(defn compile-fn-validation?
  "Returns true if validation should be included at compile time, otherwise false.
   Validation is elided for any of the following cases:
   *   function has :never-validate metadata
   *   *compile-fn-validation* is false
   *   *assert* is false AND function is not :always-validate"
  [env fn-name]
  (let [fn-meta (meta fn-name)]
    (and
      @*compile-fn-validation*
      (not (:never-validate fn-meta))
      (or (:always-validate fn-meta)
          *assert*))))

(defn process-fn-arity
  "Process a single (bind & body) form, producing an output tag, schema-form,
   and arity-form which has asserts for validation purposes added that are
   executed when turned on, and have very low overhead otherwise.
   tag? is a prospective tag for the fn symbol based on the output schema.
   schema-bindings are bindings to lift eval outwards, so we don't build the schema
   every time we do the validation."
  [env fn-name output-schema-sym bind-meta [bind & body]]
  (assert! (vector? bind) "Got non-vector binding form %s" bind)
  (when-let [bad-meta (seq (filter (or (meta bind) {}) [:tag :s? :s :schema]))]
    (throw (RuntimeException. (str "Meta not supported on bindings, put on fn name" (vec bad-meta)))))
  (let [original-arglist bind
        bind (with-meta (process-arrow-schematized-args env bind) bind-meta)
        [regular-args rest-arg] (split-rest-arg env bind)
        input-schema-sym (gensym "input-schema")
        input-explainer-sym (gensym "input-explainer")
        output-explainer-sym (gensym "output-explainer")
        compile-validation (compile-fn-validation? env fn-name)]
    {:schema-binding [input-schema-sym (input-schema-form regular-args rest-arg)]
     :more-bindings (when compile-validation
                      [input-explainer-sym `(delay (m/explainer ~input-schema-sym))
                       output-explainer-sym `(delay (m/explainer ~output-schema-sym))])
     :arglist bind
     :raw-arglist original-arglist
     :arity-form (if compile-validation
                   (let [bind-syms (vec (repeatedly (count regular-args) gensym))
                         rest-sym (when rest-arg (gensym "rest"))
                         metad-bind-syms (with-meta (mapv #(with-meta %1 (meta %2)) bind-syms bind) bind-meta)]

                     ;; sequence schemas not supported
                     (when rest-arg
                       (m/-fail! ::varags-not-supported {:args bind}))

                     (list
                       (if rest-arg
                         (into metad-bind-syms ['& rest-sym])
                         metad-bind-syms)
                       `(let [validate# ~(if (:always-validate (meta fn-name)) `true `(if-cljs (deref ~'ufv__) (.get ~'ufv__)))]
                          (when validate#
                            (let [args# ~(if rest-arg `(list* ~@bind-syms ~rest-sym) bind-syms)]
                              (malli.schema/fn-validator :input '~fn-name ~input-schema-sym @~input-explainer-sym args#)))
                          (let [o# (loop ~(into (vec (interleave (map #(with-meta % {}) bind) bind-syms))
                                                (when rest-arg [rest-arg rest-sym]))
                                     ~@(apply-prepost-conditions body))]
                            (when validate#
                              (malli.schema/fn-validator :output '~fn-name ~output-schema-sym @~output-explainer-sym o#))
                            o#))))
                   (cons (into regular-args (when rest-arg ['& rest-arg]))
                         body))}))

(defrecord FnSchema [output-schema input-schemas])

(defn- arity [input-schema]
  (count (m/children input-schema))
  #_(if (seq input-schema)
      (if (instance? One (last input-schema))
        (count input-schema)
        Long/MAX_VALUE)
      0))

(defn make-fn-schema
  "A function outputting a value in output schema, whose argument vector must match one of
   input-schemas, each of which should be a sequence schema.
   Currently function schemas are purely descriptive; they validate against any function,
   regardless of actual input and output types."
  [output-schema input-schemas]
  (assert! (seq input-schemas) "Function must have at least one input schema")
  #_(assert! (every? vector? input-schemas) "Each arity must be a vector.")
  (assert! (apply distinct? (map arity input-schemas)) "Arities must be distinct")
  (FnSchema. output-schema (sort-by arity input-schemas)))

(defn process-fn-
  "Process the fn args into a final tag proposal, schema form, schema bindings, and fn form"
  [env name fn-body]
  (let [compile-validation (compile-fn-validation? env name)
        output-schema (extract-schema-form name)
        output-schema-sym (gensym "output-schema")
        bind-meta (or (when-let [t (:tag (meta name))]
                        (when (primitive-sym? t)
                          {:tag t}))
                      {})
        processed-arities (map (partial process-fn-arity env name output-schema-sym bind-meta)
                               (if (vector? (first fn-body))
                                 [fn-body]
                                 fn-body))
        schema-bindings (map :schema-binding processed-arities)
        fn-forms (map :arity-form processed-arities)]
    {:outer-bindings (vec (concat
                            (when compile-validation
                              `[~(with-meta 'ufv__ {:tag 'java.util.concurrent.atomic.AtomicReference}) use-fn-validation])
                            [output-schema-sym output-schema]
                            (apply concat schema-bindings)
                            (mapcat :more-bindings processed-arities)))
     :arglists (map :arglist processed-arities)
     :raw-arglists (map :raw-arglist processed-arities)
     :schema-form (if (= 1 (count processed-arities))
                    `(->FnSchema (m/schema ~output-schema-sym) (mapv m/schema ~[(ffirst schema-bindings)]))
                    `(make-fn-schema (m/schema ~output-schema-sym) (mapv m/schema ~(mapv first schema-bindings))))
     :fn-body fn-forms}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public: helpers for schematized functions

(defn normalized-defn-args
  "Helper for defining defn-like macros with schemas.  Env is &env
   from the macro body.  Reads optional docstring, return type and
   attribute-map and normalizes them into the metadata of the name,
   returning the normalized arglist.  Based on
   clojure.tools.macro/name-with-attributes."
  [env macro-args]
  (let [[name macro-args] (extract-arrow-schematized-element env macro-args)
        [maybe-docstring macro-args] (maybe-split-first string? macro-args)
        [maybe-attr-map macro-args] (maybe-split-first map? macro-args)]
    (cons (vary-meta name merge
                     (or maybe-attr-map {})
                     (when maybe-docstring {:doc maybe-docstring}))
          macro-args)))

(defn set-compile-fn-validation!
  "Globally turn on or off function validation from being compiled into s/fn and s/defn.
   Enabled by default.
   See (doc compile-fn-validation?) for all conditions which control fn validation compilation"
  [on?]
  (reset! *compile-fn-validation* on?))
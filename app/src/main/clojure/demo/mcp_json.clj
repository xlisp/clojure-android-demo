(ns demo.mcp-json
  "A Jackson-free implementation of io.modelcontextprotocol.json.McpJsonMapper for
  Android API 26–32.

  Why: the SDK's stock mapper (mcp-json-jackson3) drives Jackson 3, whose bean
  introspection calls java.lang.Class.isRecord() / getRecordComponents() — JDK 16+
  reflection that Android's ART only gained in API 33 (Android 13). On older
  devices that throws NoSuchMethodError the moment any message is (de)serialized.

  This mapper avoids ALL record reflection. It:
    - parses/emits JSON with a tiny pure-Clojure reader/writer (no deps), and
    - binds JSON <-> the SDK's schema types using ordinary Constructor/Method
      reflection driven by the @JsonProperty annotations the SDK classes already
      carry (plus @JsonTypeInfo/@JsonSubTypes for the polymorphic `Content` `type`
      discriminator, and @JsonValue for enums).

  It only needs to satisfy the calls the MCP *server* makes on the mapper:
    readValue(String|bytes, TypeRef)      -> parse a JSON-RPC envelope to a Map
    convertValue(Object, Class)           -> type an envelope/params Map into a
                                             JSONRPCRequest / CallToolRequest / ...
    readValue(String, Class=JsonSchema)   -> parse a tool's input schema
    writeValueAsString/Bytes(Object)      -> emit JSONRPCResponse (-> CallToolResult
                                             -> Content with its `type` discriminator)
  See demo.mcp, which installs (demo.mcp-json/make-mapper) into the SDK."
  (:import
   [io.modelcontextprotocol.json McpJsonMapper TypeRef]
   [com.fasterxml.jackson.annotation JsonProperty JsonValue JsonTypeInfo JsonSubTypes JsonSubTypes$Type]
   [java.lang.reflect Constructor Method ParameterizedType Type]
   [java.util Map Collection List]))

;; ============================================================================
;; JSON reader  (string -> Clojure data: maps with String keys, vectors, scalars)
;; ============================================================================

(declare ^:private parse-value)

(defn- skip-ws ^long [^String s ^long i]
  (let [n (.length s)]
    (loop [i i]
      (if (and (< i n) (Character/isWhitespace (.charAt s i))) (recur (inc i)) i))))

(defn- parse-string [^String s ^long i]
  ;; i points at the opening quote
  (let [sb (StringBuilder.)]
    (loop [i (inc i)]
      (let [c (.charAt s i)]
        (cond
          (= c \") [(.toString sb) (inc i)]
          (= c \\)
          (let [d (.charAt s (inc i))]
            (case d
              \" (.append sb \")
              \\ (.append sb \\)
              \/ (.append sb \/)
              \b (.append sb \backspace)
              \f (.append sb \formfeed)
              \n (.append sb \newline)
              \r (.append sb \return)
              \t (.append sb \tab)
              \u (.append sb (char (Integer/parseInt (subs s (+ i 2) (+ i 6)) 16))))
            (recur (if (= d \u) (+ i 6) (+ i 2))))
          :else (do (.append sb c) (recur (inc i))))))))

(defn- parse-number [^String s ^long i]
  (let [n (.length s)
        end (loop [j i]
              (if (and (< j n)
                       (let [c (.charAt s j)]
                         (or (Character/isDigit c) (#{\- \+ \. \e \E} c))))
                (recur (inc j)) j))
        tok (subs s i end)]
    [(if (re-find #"[.eE]" tok) (Double/parseDouble tok) (Long/parseLong tok)) end]))

(defn- parse-object [^String s ^long i]
  (let [i (skip-ws s (inc i))]
    (if (= \} (.charAt s i))
      [{} (inc i)]
      (loop [i i acc (transient {})]
        (let [[k i1] (parse-string s (skip-ws s i))
              i2 (inc (skip-ws s i1))                 ; consume ':'
              [v i3] (parse-value s i2)
              i4 (skip-ws s i3)
              acc (assoc! acc k v)]
          (if (= \, (.charAt s i4))
            (recur (skip-ws s (inc i4)) acc)
            [(persistent! acc) (inc i4)]))))))         ; consume '}'

(defn- parse-array [^String s ^long i]
  (let [i (skip-ws s (inc i))]
    (if (= \] (.charAt s i))
      [[] (inc i)]
      (loop [i i acc (transient [])]
        (let [[v i1] (parse-value s i)
              i2 (skip-ws s i1)
              acc (conj! acc v)]
          (if (= \, (.charAt s i2))
            (recur (skip-ws s (inc i2)) acc)
            [(persistent! acc) (inc i2)]))))))          ; consume ']'

(defn- parse-value [^String s ^long i]
  (let [i (skip-ws s i)
        c (.charAt s i)]
    (case c
      \{ (parse-object s i)
      \[ (parse-array s i)
      \" (parse-string s i)
      \t [true (+ i 4)]
      \f [false (+ i 5)]
      \n [nil (+ i 4)]
      (parse-number s i))))

(defn read-str
  "Parse a JSON string into Clojure data (objects -> maps with String keys)."
  [^String s]
  (first (parse-value s 0)))

;; ============================================================================
;; JSON writer  (Clojure/Java data -> string)
;; ============================================================================

(defn- write-string [^StringBuilder sb ^String s]
  (.append sb \")
  (dotimes [i (.length s)]
    (let [c (.charAt s i)]
      (case c
        \" (.append sb "\\\"")
        \\ (.append sb "\\\\")
        \newline (.append sb "\\n")
        \return (.append sb "\\r")
        \tab (.append sb "\\t")
        \backspace (.append sb "\\b")
        \formfeed (.append sb "\\f")
        (if (< (int c) 0x20)
          (.append sb (format "\\u%04x" (int c)))
          (.append sb c)))))
  (.append sb \"))

(declare ^:private write-value)

(defn- write-object [^StringBuilder sb m]
  (.append sb \{)
  (reduce-kv (fn [first? k v]
               (when-not first? (.append sb \,))
               (write-string sb (if (keyword? k) (name k) (str k)))
               (.append sb \:)
               (write-value sb v)
               false)
             true m)
  (.append sb \}))

(defn- write-array [^StringBuilder sb xs]
  (.append sb \[)
  (reduce (fn [first? v]
            (when-not first? (.append sb \,))
            (write-value sb v)
            false)
          true xs)
  (.append sb \]))

(defn- write-value [^StringBuilder sb v]
  (cond
    (nil? v) (.append sb "null")
    (string? v) (write-string sb v)
    (keyword? v) (write-string sb (name v))
    (instance? Boolean v) (.append sb (if v "true" "false"))
    (instance? Map v) (write-object sb v)
    (instance? Collection v) (write-array sb v)
    (and (some? v) (.isArray (class v))) (write-array sb (seq v))
    (number? v) (.append sb (str v))
    :else (write-string sb (str v))))

(defn write-str ^String [v]
  (let [sb (StringBuilder.)] (write-value sb v) (.toString sb)))

;; ============================================================================
;; Reflective bind:  JSON tree -> SDK object   (no record reflection)
;; ============================================================================

(declare ^:private bind-type unbind)

(defn- jp-value
  "The @JsonProperty name from an annotation array, or nil."
  [annos]
  (some (fn [a] (when (instance? JsonProperty a)
                  (let [v (.value ^JsonProperty a)] (when (seq v) v))))
        annos))

(defn- best-ctor ^Constructor [^Class cls]
  (let [ctors (seq (.getConstructors cls))]
    (or (->> ctors
             (filter (fn [^Constructor c]
                       (let [pa (.getParameterAnnotations c)]
                         (and (pos? (alength pa))
                              (every? (fn [annos] (boolean (jp-value annos))) pa)))))
             (sort-by (fn [^Constructor c] (alength (.getParameterTypes c))))
             last)
        (->> ctors (sort-by (fn [^Constructor c] (alength (.getParameterTypes c)))) last))))

(defn- raw-class ^Class [^Type t]
  (cond
    (instance? Class t) t
    (instance? ParameterizedType t) (raw-class (.getRawType ^ParameterizedType t))
    :else Object))

(defn- default-primitive [^Class cls]
  (condp = cls
    Boolean/TYPE false, Integer/TYPE (int 0), Long/TYPE 0, Double/TYPE 0.0
    Float/TYPE (float 0), Short/TYPE (short 0), Byte/TYPE (byte 0)
    Character/TYPE \space nil))

(defn- bind-enum [^Class cls v]
  (let [s (str v)
        consts (.getEnumConstants cls)]
    (or (some (fn [c] (when (= s (unbind c)) c)) consts)
        (try (Enum/valueOf cls s) (catch Exception _ nil))
        (first consts))))

(defn- bind-bean [^Class cls m]
  (let [ctor (best-ctor cls)
        gtypes (.getGenericParameterTypes ctor)
        pannos (.getParameterAnnotations ctor)
        args (object-array
              (map (fn [gt annos]
                     (let [k (jp-value annos)
                           v (when k (get m k))]
                       (bind-type v gt)))
                   gtypes pannos))]
    (.setAccessible ctor true)
    (.newInstance ctor args)))

(defn- bind-type
  "Bind a parsed JSON value `v` to the (possibly generic) target type `t`."
  [v ^Type t]
  (let [^Class cls (raw-class t)]
    (cond
      (nil? v) (when (.isPrimitive cls) (default-primitive cls))
      (= cls Object) v
      (= cls String) (str v)
      (or (= cls Boolean) (= cls Boolean/TYPE)) (boolean v)
      (or (= cls Integer) (= cls Integer/TYPE)) (int (long v))
      (or (= cls Long) (= cls Long/TYPE)) (long v)
      (or (= cls Double) (= cls Double/TYPE)) (double v)
      (or (= cls Float) (= cls Float/TYPE)) (float (double v))
      (.isEnum cls) (bind-enum cls v)
      (.isAssignableFrom Map cls) v                       ; leave as a plain map
      (.isAssignableFrom Collection cls)
      (let [et (when (instance? ParameterizedType t)
                 (first (.getActualTypeArguments ^ParameterizedType t)))]
        (java.util.ArrayList. ^Collection (mapv #(bind-type % (or et Object)) v)))
      :else (bind-bean cls v))))

;; ============================================================================
;; Reflective unbind:  SDK object -> JSON tree (plain maps/lists/scalars)
;; ============================================================================

(defn- supertypes [^Class cls]
  (when cls
    (lazy-cat [cls]
              (mapcat supertypes (.getInterfaces cls))
              (supertypes (.getSuperclass cls)))))

(defn- discriminator
  "If `cls` participates in @JsonTypeInfo/@JsonSubTypes polymorphism (e.g. Content),
   return {type-property subtype-name}, else nil."
  [^Class cls]
  (some (fn [^Class c]
          (let [ti (.getAnnotation c JsonTypeInfo)
                st (.getAnnotation c JsonSubTypes)]
            (when (and ti st)
              (let [prop (let [p (.property ^JsonTypeInfo ti)] (if (seq p) p "type"))
                    nm (some (fn [^JsonSubTypes$Type t]
                               (when (= (.value t) cls) (.name t)))
                             (.value ^JsonSubTypes st))]
                (when nm {prop nm})))))
        (supertypes cls)))

(defn- jvalue-method ^Method [^Class cls]
  (some (fn [^Method m]
          (when (and (.getAnnotation m JsonValue) (zero? (alength (.getParameterTypes m)))) m))
        (.getMethods cls)))

(defn- prop-methods [^Class cls]
  (->> (.getMethods cls)
       (filter (fn [^Method m]
                 (and (zero? (alength (.getParameterTypes m)))
                      (.getAnnotation m JsonProperty))))))

(defn- bean->map [obj]
  (let [cls (.getClass obj)]
    (reduce (fn [acc ^Method m]
              (let [k (let [v (.value ^JsonProperty (.getAnnotation m JsonProperty))]
                        (when (seq v) v))]
                (if (and k (not (contains? acc k)))
                  (let [uv (unbind (try (.invoke m obj (object-array 0)) (catch Throwable _ nil)))]
                    (if (nil? uv) acc (assoc acc k uv)))
                  acc)))
            (or (discriminator cls) {})
            (prop-methods cls))))

(defn- unbind
  "Convert an SDK object/value into plain JSON-ready Clojure data."
  [v]
  (cond
    (nil? v) nil
    (string? v) v
    (instance? Boolean v) v
    (keyword? v) (name v)
    (number? v) v
    (instance? Map v)
    (reduce-kv (fn [acc k val]
                 (let [uv (unbind val)]
                   (if (nil? uv) acc (assoc acc (if (keyword? k) (name k) (str k)) uv))))
               {} (into {} v))
    (instance? Collection v) (mapv unbind v)
    (and (some? v) (.isArray (class v))) (mapv unbind (seq v))
    (instance? Enum v) (let [m (jvalue-method (.getDeclaringClass ^Enum v))]
                         (if m (str (.invoke m v (object-array 0))) (.name ^Enum v)))
    (instance? Class v) (.getName ^Class v)
    :else (bean->map v)))

(defn- normalize
  "Reduce an arbitrary input to a plain tree before binding (used by convertValue)."
  [o]
  (if (or (nil? o) (string? o) (number? o) (instance? Boolean o)
          (instance? Map o) (instance? Collection o))
    o
    (unbind o)))

;; ============================================================================
;; The McpJsonMapper
;; ============================================================================

(defn make-mapper
  "An io.modelcontextprotocol.json.McpJsonMapper that needs no record reflection,
   so it works on Android API 26+."
  ^McpJsonMapper []
  (reify McpJsonMapper
    (readValue [_ ^String s ^Class c] (bind-type (read-str s) c))
    (readValue [_ ^bytes b ^Class c] (bind-type (read-str (String. b "UTF-8")) c))
    (readValue [_ ^String s ^TypeRef t] (bind-type (read-str s) (.getType t)))
    (readValue [_ ^bytes b ^TypeRef t] (bind-type (read-str (String. b "UTF-8")) (.getType t)))
    (convertValue [_ o ^Class c] (bind-type (normalize o) c))
    (convertValue [_ o ^TypeRef t] (bind-type (normalize o) (.getType t)))
    (writeValueAsString [_ o] (write-str (unbind o)))
    (writeValueAsBytes [_ o] (.getBytes (write-str (unbind o)) "UTF-8"))))

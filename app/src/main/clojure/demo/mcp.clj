(ns demo.mcp
  "An MCP (Model Context Protocol) server that runs ON the Android device — the
  same idea as the on-device nREPL (MyApp.NREPL_PORT 6688), but speaking MCP
  instead of nREPL. It is built with the official Java SDK
  `io.modelcontextprotocol.sdk/mcp` (bundled into the APK at build time, see
  app/build.gradle) and glued together here in Clojure that is itself compiled
  on-device by DalvikDynamicClassLoader (d8) — exactly like demo.ui.

  Transport: the MCP stdio transport (`StdioServerTransportProvider`) accepts a
  custom (InputStream, OutputStream). We hand it the streams of an accepted
  socket, so the whole server is reachable over TCP — no servlet container /
  Jetty needed. One ServerSocket on port 6689, one MCP session per connection,
  newline-delimited JSON-RPC over the socket (the stdio framing).

  Tools exposed to the MCP client:
    - clojure_eval     : evaluate Clojure in this device JVM (state persists)
    - android_ui_show  : reload demo.ui and swap its View onto the foreground Activity

  Connecting (mirror of the CIDER nREPL flow in README.md). An MCP client speaks
  stdio, so bridge its stdin/stdout to the device socket with nc/socat:

      adb forward tcp:6689 tcp:6689
      # configure your MCP client to launch this command:
      #   nc localhost 6689            (or: socat - TCP:localhost:6689)

  Start from Java at boot (MyApp), or live from CIDER:
      (require 'demo.mcp) (demo.mcp/start!)   ; => \"MCP server listening ...\"
      (demo.mcp/stop!)"
  (:require [demo.mcp-json :as mjson])
  (:import
   [java.net ServerSocket Socket]
   [java.io FilterInputStream StringWriter InputStream OutputStream]
   [java.util.function Consumer BiFunction]
   [clojure.lang DalvikDynamicClassLoader]
   [io.modelcontextprotocol.json McpJsonMapper]
   [io.modelcontextprotocol.json.schema JsonSchemaValidator JsonSchemaValidator$ValidationResponse]
   [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
   [io.modelcontextprotocol.server McpServer McpServerFeatures$AsyncToolSpecification]
   [io.modelcontextprotocol.spec
    McpSchema$ServerCapabilities
    McpSchema$Tool
    McpSchema$CallToolRequest
    McpSchema$CallToolResult
    McpSchema$TextContent]
   [reactor.core.publisher Mono]))

(def ^:const TAG "ClojureDemo")

;; Port the MCP server listens on; connect with `adb forward tcp:6689 tcp:6689`.
;; (nREPL is on 6688 — see MyApp.NREPL_PORT.)
(def ^:const mcp-port 6689)

(defn- log-i [msg] (android.util.Log/i TAG (str msg)))
(defn- log-e [msg ^Throwable t] (android.util.Log/e TAG (str msg) t))

;; ---- on-device eval ---------------------------------------------------------

;; A Dalvik class loader for the eval threads' context. RT.makeClassLoader()
;; already picks the Dalvik loader for *compilation*, but `require` and friends
;; resolve resources through the thread context classloader — so set it, mirror-
;; ing how MyApp seeds the nREPL bootstrap thread. Parent = the app loader.
(defonce ^ClassLoader dalvik-cl
  (DalvikDynamicClassLoader. (.getClassLoader com.example.clojuredemo.MyApp)))

(defn eval-string
  "Evaluate Clojure source in this device JVM, capturing stdout. Returns
   {:text <string> :error? <bool>}. Uses load-string so all forms run and the
   last value is returned; defs persist in their namespaces across calls."
  [code]
  (let [sw (StringWriter.)]
    (try
      (let [v (binding [*out* sw] (load-string code))
            out (str sw)]
        {:text (str (when (seq out) out) "=> " (pr-str v)) :error? false})
      (catch Throwable t
        (let [out (str sw)]
          {:text (str (when (seq out) (str out "\n")) ";; ERROR: " (.getMessage t))
           :error? true})))))

(defn- run-on-dalvik-thread
  "Run thunk on a fresh large-stack daemon thread whose context classloader is
   the Dalvik loader (so on-device require/compile resolves correctly), then call
   (k text error?). MCP tool handlers are invoked on Reactor threads, which do
   NOT carry the Dalvik classloader — hence this hop."
  [k thunk]
  (.start
   (doto (Thread. nil
                  (fn []
                    (.setContextClassLoader (Thread/currentThread) dalvik-cl)
                    (try
                      (let [{:keys [text error?]} (thunk)]
                        (k text (boolean error?)))
                      (catch Throwable t
                        (k (str ";; Tool error: " (.getMessage t)) true))))
                  "mcp-eval"
                  (* 4 1024 1024))
     (.setDaemon true)))
  nil)

;; ---- MCP SDK interop (trimmed from clojure-mcp-new's core.clj, SDK 1.1.3) ----

;; Jackson-free mapper so this works on Android API 26–32 (see demo.mcp-json).
(defonce ^McpJsonMapper json-mapper (mjson/make-mapper))

;; The SDK's default JSON-schema validator is the Jackson-3 one (needs
;; Class.isRecord(), API 33+). Supply a permissive validator so tool-arg
;; validation never touches Jackson on older devices.
(defonce ^JsonSchemaValidator permissive-validator
  (reify JsonSchemaValidator
    (validate [_ _schema _content]
      (JsonSchemaValidator$ValidationResponse/asValid nil))))

(defn- mono-from-callback
  "Wrap (exchange args fill) as a Reactor Mono the SDK can subscribe to."
  [callback]
  (fn [exchange args]
    (Mono/create
     (reify Consumer
       (accept [_ sink]
         (callback exchange args (fn [result] (.success sink result))))))))

(defn- text-result ^McpSchema$CallToolResult [^String text error?]
  (-> (McpSchema$CallToolResult/builder)
      (.addTextContent text)
      (.isError (boolean error?))
      (.build)))

(defn- async-tool
  "Build an AsyncToolSpecification from {:name :description :schema :tool-fn}.
   :schema is a JSON-schema string. :tool-fn is (fn [exchange args k]) where k
   is (fn [text error?])."
  ^McpServerFeatures$AsyncToolSpecification
  [{:keys [name description schema tool-fn]}]
  (let [tool (-> (McpSchema$Tool/builder)
                 (.name name)
                 (.description description)
                 (.inputSchema json-mapper schema)
                 (.build))
        handler (mono-from-callback
                 (fn [exchange args fill]
                   (tool-fn exchange args
                            (fn [text error?] (fill (text-result text error?))))))]
    (McpServerFeatures$AsyncToolSpecification.
     tool
     (reify BiFunction
       (apply [_ exchange request]
         (handler exchange (.arguments ^McpSchema$CallToolRequest request)))))))

;; ---- tools ------------------------------------------------------------------

(def ^:private eval-tool
  {:name "clojure_eval"
   :description
   (str "Evaluate Clojure code ON the running Android device (this is the device "
        "JVM/ART; the patched Clojure compiles the bytecode to DEX on-device). "
        "State persists across calls — (def ...) then reuse it, (in-ns ...), etc. "
        "Returns captured stdout plus the printed value after `=>`. Examples: "
        "`(+ 1 2)`, `(clojure.core/vm-type)` (=> :dalvik-vm on device), "
        "`(require 'demo.ui)`.")
   :schema (str "{\"type\":\"object\","
                "\"properties\":{\"code\":{\"type\":\"string\","
                "\"description\":\"Clojure code to evaluate on the device.\"}},"
                "\"required\":[\"code\"]}")
   :tool-fn
   (fn [_ args k]
     (let [code (get args "code")]
       (if (seq code)
         (run-on-dalvik-thread k #(eval-string code))
         (k "No code provided." true))))})

(def ^:private ui-show-tool
  {:name "android_ui_show"
   :description
   (str "Reload the demo.ui namespace on the device and swap its Clojure-built "
        "View onto the foreground Activity (calls (demo.ui/show!)). Use after "
        "editing app/src/main/clojure/demo/ui.clj to see the UI live. Returns "
        "=> :shown, or => :no-activity if the app isn't foregrounded.")
   :schema "{\"type\":\"object\",\"properties\":{},\"required\":[]}"
   :tool-fn
   (fn [_ _ k]
     (run-on-dalvik-thread
      k #(eval-string "(do (require 'demo.ui :reload) (demo.ui/show!))")))})

;; Built once; the specs are immutable and reused for every connection.
(defonce ^:private tool-specs
  (delay (into-array McpServerFeatures$AsyncToolSpecification
                     (map async-tool [eval-tool ui-show-tool]))))

;; ---- server / accept loop ---------------------------------------------------

(defn- build-server [^InputStream in ^OutputStream out]
  (-> (McpServer/async (StdioServerTransportProvider. json-mapper in out))
      (.serverInfo "clojure-android-mcp" "0.1.0")
      ;; Set the mapper explicitly: the default goes through ServiceLoader.findFirst()
      ;; (a Java 9 API missing on API <33) and would crash on build.
      (.jsonMapper json-mapper)
      (.jsonSchemaValidator permissive-validator)
      (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                         (.tools true)
                         (.build)))
      (.tools ^"[Lio.modelcontextprotocol.server.McpServerFeatures$AsyncToolSpecification;" @tool-specs)
      (.build)))

(defn- serve-connection
  "Run one MCP session over the socket until the client disconnects (EOF)."
  [^Socket sock]
  (try
    (let [done (promise)
          raw (.getInputStream sock)
          ;; deliver `done` when the transport's reader hits EOF on the socket
          in (proxy [FilterInputStream] [raw]
               (read
                 ([] (let [b (proxy-super read)]
                       (when (neg? b) (deliver done true)) b))
                 ([buf off len] (let [n (proxy-super read buf off len)]
                                  (when (neg? n) (deliver done true)) n))))
          out (.getOutputStream sock)
          server (build-server in out)]
      (log-i "MCP client connected")
      (try
        @done
        (finally
          (try (.subscribe (.closeGracefully server)) (catch Throwable _))
          (try (.close sock) (catch Throwable _))
          (log-i "MCP client disconnected"))))
    ;; Never let a connection error reach the default handler (it would kill the app).
    (catch Throwable t
      (log-e "MCP connection handler error" t)
      (try (.close sock) (catch Throwable _)))))

(defonce ^:private server-socket (atom nil))

(defn stop!
  "Stop the MCP server (closes the listening socket)."
  []
  (when-let [^ServerSocket ss @server-socket]
    (reset! server-socket nil)
    (try (.close ss) (catch Throwable _)))
  :stopped)

(defn start!
  "Start the MCP server on `port` (default 6689), binding 0.0.0.0. Accepts
   connections on a daemon thread, each handled on its own thread so a fresh MCP
   client can connect after one disconnects. Returns a status string."
  ([] (start! mcp-port))
  ([port]
   (when @server-socket (stop!))
   (let [ss (ServerSocket. port)]
     (reset! server-socket ss)
     (.start
      (doto (Thread.
             nil
             (fn []
               (.setContextClassLoader (Thread/currentThread) dalvik-cl)
               (try
                 (loop []
                   (when (and (identical? ss @server-socket) (not (.isClosed ss)))
                     (let [sock (.accept ss)]
                       (.start (doto (Thread. nil #(serve-connection sock)
                                              "mcp-conn" (* 4 1024 1024))
                                 (.setDaemon true)))
                       (recur))))
                 (catch Throwable t
                   (when (identical? ss @server-socket)
                     (log-e "MCP accept loop ended" t)))))
             "mcp-accept"
             (* 4 1024 1024))
        (.setDaemon true)))
     (let [msg (str "MCP server listening on 0.0.0.0:" port
                    "  (adb forward tcp:" port " tcp:" port
                    "; bridge stdio with `nc localhost " port "`)")]
       (log-i msg)
       msg))))

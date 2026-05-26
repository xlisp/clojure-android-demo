(ns demo.ui
  "Example: an Android screen written in Clojure, live-reloadable from CIDER.

  Counterpart to gradle-clojure-android-sample's plain/someactivity2.clj -- but
  that project AOT-compiles a (:gen-class) Activity into the APK at build time.
  This project has *no* build-time Clojure AOT: code is compiled on-device by
  DalvikDynamicClassLoader (d8). The Android framework instantiates Activities by
  name via the app's PathClassLoader, so a class generated on-device can't be a
  manifest-launched Activity. The idiomatic move here is instead:

      build the View tree programmatically and setContentView it onto the
      already-running Activity.

  Usage from Emacs once CIDER is connected:
    1. Open this file, `C-c C-k` (cider-load-buffer) -- compiles it on-device.
    2. (demo.ui/show!)                 ; swap this screen onto the foreground Activity
    3. Edit build-view, C-c C-k, (demo.ui/show!) again -- redesign the UI live.
  Relaunch the app to get the original Eval screen back."
  (:import [android.app Activity]
           [android.graphics Color]
           [android.os Handler Looper]
           [android.view View View$OnClickListener]
           [android.widget LinearLayout TextView EditText Button ScrollView]
           [java.net URL HttpURLConnection]))

;; ---- helpers ----------------------------------------------------------------

(def ^Handler ui-handler (Handler. (Looper/getMainLooper)))

(defn on-ui
  "Run thunk f on the main (UI) thread -- required for any View mutation."
  [f]
  (.post ui-handler f))

(defn dp
  "Density-independent pixels -> px for the given context."
  [ctx d]
  (int (* d (.. ctx getResources getDisplayMetrics density))))

(defn click
  "Wrap a 1-arg fn (the clicked view) as a View.OnClickListener."
  [f]
  (reify View$OnClickListener
    (onClick [_ v] (f v))))

(defn current-activity
  "Foreground Activity, tracked by MyApp's ActivityLifecycleCallbacks."
  ^Activity []
  com.example.clojuredemo.MyApp/currentActivity)

;; ---- the screen -------------------------------------------------------------

(defn build-view
  "Construct and return the demo screen's root View. Pure UI construction --
  takes a Context, returns a View; show! is what attaches it."
  ^View [^Activity activity]
  (let [pad    (dp activity 16)
        clicks (atom 0)
        output (doto (TextView. activity)
                 (.setTextSize 13.0)
                 (.setTextColor (Color/parseColor "#37474F"))
                 (.setText "output:\n"))
        append (fn [line] (on-ui #(.append output (str line "\n"))))
        input  (doto (EditText. activity)
                 (.setHint "(reduce + (range 1 101))"))]
    (doto (LinearLayout. activity)
      (.setOrientation LinearLayout/VERTICAL)
      (.setPadding pad pad pad pad)

      ;; title
      (.addView (doto (TextView. activity)
                  (.setText "Clojure UI demo 🍃")
                  (.setTextSize 22.0)
                  (.setTextColor (Color/parseColor "#2E7D32"))))

      ;; stateful counter button (atom + click handler + UI update)
      (.addView (doto (Button. activity)
                  (.setText "Tap me: 0")
                  (.setOnClickListener
                    (click (fn [^Button b]
                             (.setText b (str "Tap me: " (swap! clicks inc))))))))

      ;; eval whatever is typed -- ties into this project's on-device compiler
      (.addView input)
      (.addView (doto (Button. activity)
                  (.setText "Eval input")
                  (.setOnClickListener
                    (click (fn [_]
                             (let [src (str (.getText input))]
                               (append (str "=> " (try (pr-str (load-string src))
                                                       (catch Throwable t
                                                         (.getMessage t)))))))))))

      ;; async HTTP GET on a background thread, post result back to the TextView
      ;; (mirrors someactivity2.clj's fetch-and-setText, sans http-kit)
      (.addView (doto (Button. activity)
                  (.setText "Fetch example.com")
                  (.setOnClickListener
                    (click (fn [_]
                             (append "fetching…")
                             (.start
                               (Thread.
                                 (fn []
                                   (try
                                     (let [conn ^HttpURLConnection
                                           (.openConnection (URL. "https://example.com"))]
                                       (.setRequestMethod conn "GET")
                                       (let [code (.getResponseCode conn)
                                             body (slurp (.getInputStream conn))]
                                         (append (str "HTTP " code " — " (count body) " bytes"))))
                                     (catch Throwable t
                                       (append (str "fetch error: " (.getMessage t)))))))))))))

      ;; scrolling output pane
      (.addView (doto (ScrollView. activity)
                  (.addView output))))))

(defn show!
  "Swap the demo screen onto the foreground Activity. Call (demo.ui/show!) from
  CIDER. Returns :shown, or :no-activity if the app isn't in the foreground."
  ([] (show! (current-activity)))
  ([^Activity activity]
   (if activity
     (do (.runOnUiThread activity #(.setContentView activity (build-view activity)))
         :shown)
     :no-activity)))

(ns demo.input
  "A minimal input screen written in Clojure: EditText + a submit button that
  echoes what was typed into a TextView (and pops a Toast). Same pattern as
  demo.ui -- build the View tree programmatically and setContentView it onto the
  already-running foreground Activity.

  Usage:
    (require 'demo.input :reload)
    (demo.input/show!)            ; => :shown, or :no-activity if backgrounded
    (demo.input/show! \"prefilled text\")"
  (:import [android.app Activity]
           [android.graphics Color]
           [android.os Handler Looper]
           [android.view Gravity View View$OnClickListener]
           [android.widget LinearLayout TextView EditText Button Toast]))

;; ---- helpers ----------------------------------------------------------------

(def ^Handler ui-handler (Handler. (Looper/getMainLooper)))

(defn on-ui [f] (.post ui-handler f))

(defn dp [ctx d]
  (int (* d (.. ctx getResources getDisplayMetrics density))))

(defn click [f]
  (reify View$OnClickListener
    (onClick [_ v] (f v))))

(defn current-activity ^Activity []
  com.example.clojuredemo.MyApp/currentActivity)

;; ---- the screen -------------------------------------------------------------

(defn build-view
  "Build and return the input screen's root View."
  (^View [^Activity activity] (build-view activity ""))
  (^View [^Activity activity ^String prefill]
   (let [pad    (dp activity 20)
         result (doto (TextView. activity)
                  (.setTextSize 16.0)
                  (.setTextColor (Color/parseColor "#37474F"))
                  (.setPadding 0 (dp activity 16) 0 0)
                  (.setText "result in here"))
         input  (doto (EditText. activity)
                  (.setHint "Input text…")
                  (.setText (or prefill ""))
                  (.setSingleLine true))
         submit (fn [_]
                  (let [s (str (.getText input))]
                    (on-ui #(.setText result (str "input: " s)))
                    (.show (Toast/makeText activity
                                           (str "submit：" s)
                                           Toast/LENGTH_SHORT))))]
     ;; submit on the keyboard's IME action too -- set on `input` itself, not
     ;; inside the LinearLayout doto (doto would thread the layout in here).
     (.setOnEditorActionListener
       input
       (reify android.widget.TextView$OnEditorActionListener
         (onEditorAction [_ _v _action _event] (submit nil) true)))
     (doto (LinearLayout. activity)
       (.setOrientation LinearLayout/VERTICAL)
       (.setPadding pad pad pad pad)
       (.addView (doto (TextView. activity)
                   (.setText "input test ✍️")
                   (.setTextSize 24.0)
                   (.setTextColor (Color/parseColor "#2E7D32"))
                   (.setPadding 0 0 0 (dp activity 16))))
       (.addView input)
       (.addView (doto (Button. activity)
                   (.setText "submit")
                   (.setOnClickListener (click submit))))
       (.addView (doto (Button. activity)
                   (.setText "clean")
                   (.setOnClickListener
                     (click (fn [_]
                              (on-ui (fn []
                                       (.setText input "")
                                       (.setText result "result in here"))))))))
       (.addView result)))))

(defn show!
  "Swap the input screen onto the foreground Activity. Returns :shown, or
  :no-activity if the app isn't in the foreground."
  ([] (show! (current-activity) ""))
  ([prefill] (show! (current-activity) prefill))
  ([^Activity activity prefill]
   (if activity
     (do (.runOnUiThread activity
                         #(.setContentView activity (build-view activity prefill)))
         :shown)
     :no-activity)))

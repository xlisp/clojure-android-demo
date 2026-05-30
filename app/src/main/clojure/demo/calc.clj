(ns demo.calc
  "A calculator screen written in Clojure, injected onto the running Activity.

  Same pattern as demo.ui: build a View tree programmatically and setContentView
  it onto the foreground Activity (no build-time AOT, so we can't register a
  manifest Activity). The expression typed/built in the input is evaluated by a
  small recursive-descent infix parser below (so you type 1+2*3, not Clojure
  prefix form).

  Usage:
    (require 'demo.calc) (demo.calc/show!)
  or via the clojure-android MCP: android_ui_show-style (require + show!)."
  (:import [android.app Activity]
           [android.graphics Color]
           [android.os Handler Looper]
           [android.text InputType]
           [android.view Gravity View View$OnClickListener ViewGroup$LayoutParams]
           [android.widget LinearLayout LinearLayout$LayoutParams
                           TextView EditText Button]))

;; ---- helpers ----------------------------------------------------------------

(def ^Handler ui-handler (Handler. (Looper/getMainLooper)))

(defn dp [ctx d]
  (int (* d (.. ctx getResources getDisplayMetrics density))))

(defn click [f]
  (reify View$OnClickListener
    (onClick [_ v] (f v))))

(defn current-activity ^Activity []
  com.example.clojuredemo.MyApp/currentActivity)

;; ---- infix expression evaluator --------------------------------------------
;; grammar:  expr := term (('+'|'-') term)*
;;           term := factor (('*'|'/') factor)*
;;           factor := number | '(' expr ')' | '-' factor

(defn tokenize [^String s]
  (loop [cs (seq s) acc []]
    (if (empty? cs)
      acc
      (let [c (first cs)]
        (cond
          (Character/isWhitespace ^char c) (recur (rest cs) acc)
          (or (Character/isDigit ^char c) (= c \.))
          (let [num (apply str (take-while #(or (Character/isDigit ^char %) (= % \.)) cs))]
            (recur (drop (count num) cs) (conj acc (Double/parseDouble num))))
          (#{\+ \- \* \/ \( \)} c) (recur (rest cs) (conj acc c))
          :else (throw (ex-info (str "bad char: " c) {})))))))

;; NOTE: top-level defns (not letfn) on purpose. The on-device d8 compiler loads
;; each generated class into its own DexFile, and letfn's mutually-recursive
;; closures reference each other through package-private synthetic fields that
;; become inaccessible across DexFiles. Going through Vars sidesteps that.

(defn- p-peek [st]
  (let [{:keys [tokens pos]} st]
    (when (< @pos (count tokens)) (nth tokens @pos))))

(defn- p-next! [st]
  (let [t (p-peek st)] (swap! (:pos st) inc) t))

(declare p-expr)

(defn- p-factor [st]
  (let [t (p-peek st)]
    (cond
      (= t \() (do (p-next! st)
                   (let [v (p-expr st)]
                     (when (= (p-peek st) \)) (p-next! st))
                     v))
      (= t \-) (do (p-next! st) (- (p-factor st)))
      (number? t) (p-next! st)
      :else (throw (ex-info (str "unexpected: " t) {})))))

(defn- p-term [st]
  (loop [v (p-factor st)]
    (case (p-peek st)
      \* (do (p-next! st) (recur (* v (p-factor st))))
      \/ (do (p-next! st) (recur (/ v (p-factor st))))
      v)))

(defn- p-expr [st]
  (loop [v (p-term st)]
    (case (p-peek st)
      \+ (do (p-next! st) (recur (+ v (p-term st))))
      \- (do (p-next! st) (recur (- v (p-term st))))
      v)))

(defn parse-eval [tokens]
  (let [st {:tokens tokens :pos (atom 0)}
        v  (p-expr st)]
    (when (< @(:pos st) (count tokens))
      (throw (ex-info (str "trailing: " (p-peek st)) {})))
    v))

(defn fmt [v]
  (let [d (double v)]
    (cond
      (Double/isNaN d)      "NaN"
      (Double/isInfinite d) "∞"
      (== d (Math/rint d))  (str (long d))
      :else                 (str d))))

(defn evaluate [^String s]
  (if (or (nil? s) (zero? (count (.trim s))))
    ""
    (try (str "= " (fmt (parse-eval (tokenize s))))
         (catch Throwable t (str "error: " (.getMessage t))))))

;; ---- the screen -------------------------------------------------------------

(defn- match-wrap [_]
  (LinearLayout$LayoutParams. ViewGroup$LayoutParams/MATCH_PARENT
                              ViewGroup$LayoutParams/WRAP_CONTENT))

(defn- weighted ^LinearLayout$LayoutParams []
  (LinearLayout$LayoutParams. 0 ViewGroup$LayoutParams/MATCH_PARENT (float 1)))

(defn build-view
  ^View [^Activity activity]
  (let [pad    (dp activity 12)
        result (doto (TextView. activity)
                 (.setText "= 0")
                 (.setTextSize 22.0)
                 (.setGravity (bit-or Gravity/END Gravity/CENTER_VERTICAL))
                 (.setTextColor (Color/parseColor "#FFEB3B")))
        input  (doto (EditText. activity)
                 (.setHint "1+2*3")
                 (.setTextSize 28.0)
                 (.setGravity (bit-or Gravity/END Gravity/CENTER_VERTICAL))
                 (.setTextColor (Color/parseColor "#FFFFFF"))
                 (.setHintTextColor (Color/parseColor "#90CAF9"))
                 ;; plain text (NOT number type -- that installs a digits-only
                 ;; filter that silently drops + - * / ( ) appended by buttons);
                 ;; buttons drive the input, so suppress the soft keyboard.
                 (.setInputType (bit-or InputType/TYPE_CLASS_TEXT
                                        InputType/TYPE_TEXT_FLAG_NO_SUGGESTIONS))
                 (.setShowSoftInputOnFocus false))
        live   (fn [] (.setText result (evaluate (str (.getText input)))))
        append (fn [^String s]
                 (.append input s)
                 (live))
        clear  (fn [] (.setText input "") (.setText result "= 0"))
        back   (fn [] (let [t (str (.getText input))]
                        (when (pos? (count t))
                          (.setText input (subs t 0 (dec (count t))))
                          (.setSelection input (dec (count t)))
                          (live))))
        equals (fn [] (let [r (evaluate (str (.getText input)))]
                        (.setText result r)))
        btn    (fn ^Button [label on]
                 (doto (Button. activity)
                   (.setText (str label))
                   (.setTextSize 20.0)
                   (.setAllCaps false)
                   (.setLayoutParams (weighted))
                   (.setOnClickListener (click (fn [_] (on))))))
        digit  (fn [d] (btn d #(append (str d))))
        op     (fn [o] (btn o #(append (str o))))
        row    (fn [& bs]
                 (let [r (doto (LinearLayout. activity)
                           (.setOrientation LinearLayout/HORIZONTAL)
                           (.setLayoutParams (match-wrap activity)))]
                   (doseq [b bs] (.addView r b))
                   r))]
    (doto (LinearLayout. activity)
      (.setOrientation LinearLayout/VERTICAL)
      (.setPadding pad pad pad pad)
      (.setBackgroundColor (Color/parseColor "#1565C0"))

      (.addView (doto (TextView. activity)
                  (.setText "Clojure Calculator 🧮")
                  (.setTextSize 20.0)
                  (.setTextColor (Color/parseColor "#FFFFFF"))))

      (.addView input)
      (.addView result)

      (.addView (row (btn "C" clear) (op \() (op \)) (op \/)))
      (.addView (row (digit 7) (digit 8) (digit 9) (op \*)))
      (.addView (row (digit 4) (digit 5) (digit 6) (op \-)))
      (.addView (row (digit 1) (digit 2) (digit 3) (op \+)))
      (.addView (row (digit 0) (op \.) (btn "⌫" back) (btn "=" equals))))))

(defn show!
  "Swap the calculator screen onto the foreground Activity."
  ([] (show! (current-activity)))
  ([^Activity activity]
   (if activity
     (do (.runOnUiThread activity #(.setContentView activity (build-view activity)))
         :shown)
     :no-activity)))

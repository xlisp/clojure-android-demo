(ns demo.kline
  "Eastmoney (东方财富) daily K-line viewer, written in Clojure and injected onto
  the running Activity -- the same no-AOT pattern as demo.ui / demo.calc.

  Port of a Kotlin snippet: okhttp3 does the HTTP GET, org.json parses the
  payload, MPAndroidChart's LineChart draws close + MA5 + MA20, and 周/月/年
  buttons reload the chosen range (7 / 30 / 365 days).

  okhttp3 + MPAndroidChart are ordinary build-time Gradle deps (see app/build.gradle
  and the JitPack repo in settings.gradle): they're DEX'd into the APK and loaded
  by the app PathClassLoader, so on-device-compiled Clojure can reference them.
  java.time.LocalDate is fine -- minSdk is 26.

  Usage:
    (require 'demo.kline) (demo.kline/show!)
  or via the drawer's \"K-line 📈\" entry (require + build-view injection)."
  (:import [android.app Activity]
           [android.graphics Color]
           [android.os Handler Looper]
           [android.text InputType]
           [android.view Gravity View View$OnClickListener ViewGroup$LayoutParams]
           [android.widget LinearLayout LinearLayout$LayoutParams
                           TextView EditText Button ScrollView]
           [java.time LocalDate]
           [java.time.format DateTimeFormatter]
           [java.util ArrayList]
           [org.json JSONObject]
           [okhttp3 OkHttpClient Request Request$Builder Response]
           [com.github.mikephil.charting.charts LineChart]
           [com.github.mikephil.charting.components Legend
                                                    Legend$LegendHorizontalAlignment
                                                    XAxis XAxis$XAxisPosition]
           [com.github.mikephil.charting.data Entry LineData LineDataSet]
           [com.github.mikephil.charting.formatter ValueFormatter]
           [com.github.mikephil.charting.interfaces.datasets ILineDataSet]))

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

;; ---- data layer: port of emKline / ma --------------------------------------

(def ^OkHttpClient http-client (OkHttpClient.))

(defn guess-market
  "Eastmoney secid market prefix: 1 = Shanghai (codes starting 6), 0 = Shenzhen."
  [^String code]
  (if (.startsWith code "6") 1 0))

(defn em-kline
  "Fetch daily K-line bars from push2his.eastmoney.com via okhttp. Returns a
  vector of {:date \"yyyy-MM-dd\" :close <float>}, oldest first. Each raw kline is
  \"date,open,close,high,low,vol,amount,amplitude\" -- index 2 is the close."
  ([] (em-kline "603052" 1 365))
  ([^String code market days]
   (let [fmt (DateTimeFormatter/ofPattern "yyyyMMdd")
         end (.format (LocalDate/now) fmt)
         beg (.format (.minusDays (LocalDate/now) (long days)) fmt)
         url (str "https://push2his.eastmoney.com/api/qt/stock/kline/get"
                  "?secid=" market "." code
                  "&fields1=f1,f2,f3,f4,f5,f6"
                  "&fields2=f51,f52,f53,f54,f55,f56,f57,f58"
                  "&klt=101&fqt=1&beg=" beg "&end=" end)
         req (.. (Request$Builder.) (url ^String url) build)]
     (with-open [resp ^Response (.execute (.newCall http-client req))]
       (let [body   (.. resp body string)
             klines (.. (JSONObject. ^String body)
                        (getJSONObject "data")
                        (getJSONArray "klines"))]
         (mapv (fn [i]
                 (let [p (.split (.getString klines i) ",")]
                   {:date (aget p 0) :close (Float/parseFloat (aget p 2))}))
               (range (.length klines))))))))

(defn ma
  "Rolling mean of period (pandas rolling(period).mean()): the first (period-1)
  entries are nil, the rest are the mean of the trailing `period` values."
  [values period]
  (let [v (vec values)]
    (mapv (fn [i]
            (when (>= i (dec period))
              (/ (reduce + (subvec v (- i (dec period)) (inc i)))
                 (double period))))
          (range (count v)))))

;; ---- MPAndroidChart glue ----------------------------------------------------

(defn entries-of
  "ArrayList<Entry> for `vals`, x = index. nil values (MA warm-up) are skipped,
  keeping the remaining points aligned to the close index."
  ^ArrayList [vals]
  (let [al (ArrayList.)]
    (dotimes [i (count vals)]
      (when-let [v (nth vals i)]
        (.add al (Entry. (float i) (float v)))))
    al))

(defn line-set
  ^LineDataSet [^String label color vals]
  (doto (LineDataSet. (entries-of vals) label)
    (.setColor (int color))
    (.setLineWidth (float 1.6))
    (.setDrawCircles false)
    (.setDrawValues false)
    (.setHighlightEnabled false)))

(defn make-chart
  "A configured LineChart. `dates*` is an atom holding the current date vector,
  read by the x-axis formatter so labels stay in sync after each reload."
  ^LineChart [^Activity activity dates*]
  (let [chart (LineChart. activity)]
    (.setDescription (.getDescription chart) nil)
    (doto chart
      (.setNoDataText "拉取中…")
      (.setBackgroundColor (Color/parseColor "#102027"))
      (.setDrawGridBackground false)
      (.setScaleYEnabled false))
    (doto (.getLegend chart)
      (.setTextColor (Color/parseColor "#ECEFF1"))
      (.setHorizontalAlignment Legend$LegendHorizontalAlignment/CENTER))
    (.setEnabled (.getAxisRight chart) false)
    (.setTextColor (.getAxisLeft chart) (Color/parseColor "#B0BEC5"))
    (doto (.getXAxis chart)
      (.setPosition XAxis$XAxisPosition/BOTTOM)
      (.setTextColor (Color/parseColor "#B0BEC5"))
      (.setDrawGridLines false)
      (.setGranularity (float 1))
      (.setLabelCount 5)
      (.setValueFormatter
        (proxy [ValueFormatter] []
          (getFormattedValue [^float value]
            (let [ds @dates* i (int value)]
              (if (and (>= i 0) (< i (count ds)))
                (subs (nth ds i) 5)              ; "MM-dd"
                ""))))))
    chart))

(defn update-chart!
  "Rebuild the LineData from bars + MAs and push it onto the chart (UI thread)."
  [^LineChart chart bars m5 m20]
  (let [closes (mapv :close bars)
        ld     (LineData.)]
    (.addDataSet ld (line-set "收盘" (Color/parseColor "#ECEFF1") closes))
    (when (some some? m5)
      (.addDataSet ld (line-set "MA5"  (Color/parseColor "#FFEB3B") m5)))
    (when (some some? m20)
      (.addDataSet ld (line-set "MA20" (Color/parseColor "#4FC3F7") m20)))
    (.setData chart ld)
    (.fitScreen chart)
    (.invalidate chart)))

;; ---- table summary ----------------------------------------------------------

(defn- fmt2 [v]
  (if (nil? v) "   -  " (format "%7.2f" (double v))))

(defn summary
  "Last 20 rows: date / close / ma5 / ma20."
  [bars m5 m20]
  (let [n    (count bars)
        from (max 0 (- n 20))]
    (str "date         close     ma5    ma20\n"
         (apply str
                (for [i (range from n)]
                  (let [b (nth bars i)]
                    (str (:date b) " " (fmt2 (:close b))
                         " " (fmt2 (nth m5 i)) " " (fmt2 (nth m20 i)) "\n")))))))

;; ---- the screen -------------------------------------------------------------

(defn build-view ^View [^Activity activity]
  (let [pad    (dp activity 12)
        dates* (atom [])
        status (doto (TextView. activity)
                 (.setTextSize 13.0)
                 (.setTextColor (Color/parseColor "#B0BEC5"))
                 (.setText "选择区间拉取日线"))
        table  (doto (TextView. activity)
                 (.setTextSize 12.0)
                 (.setTypeface android.graphics.Typeface/MONOSPACE)
                 (.setTextColor (Color/parseColor "#CFD8DC"))
                 (.setText ""))
        chart  (make-chart activity dates*)
        _      (.setLayoutParams chart (LinearLayout$LayoutParams.
                                         ViewGroup$LayoutParams/MATCH_PARENT
                                         (dp activity 300)))
        input  (doto (EditText. activity)
                 (.setHint "603052")
                 (.setText "603052")
                 (.setInputType (bit-or InputType/TYPE_CLASS_TEXT
                                        InputType/TYPE_TEXT_FLAG_NO_SUGGESTIONS)))
        fetch  (fn [days label]
                 (let [code (.trim (str (.getText input)))]
                   (.setText status (str "拉取 " code " · " label " …"))
                   (.start
                     (Thread.
                       (fn []
                         (try
                           (let [bars (em-kline code (guess-market code) days)
                                 cs   (mapv :close bars)
                                 m5   (ma cs 5)
                                 m20  (ma cs 20)]
                             (reset! dates* (mapv :date bars))
                             (on-ui
                               (fn []
                                 (.setText status
                                           (str code " · " label " · " (count cs) " 根  收盘 "
                                                (when (seq cs) (format "%.2f" (double (peek cs))))))
                                 (.setText table (summary bars m5 m20))
                                 (update-chart! chart bars m5 m20))))
                           (catch Throwable t
                             (on-ui #(.setText status (str "error: " (.getMessage t)))))))))))
        rbtn   (fn ^Button [label days]
                 (doto (Button. activity)
                   (.setText (str label))
                   (.setAllCaps false)
                   (.setLayoutParams (LinearLayout$LayoutParams.
                                       0 ViewGroup$LayoutParams/WRAP_CONTENT (float 1)))
                   (.setOnClickListener (click (fn [_] (fetch days label))))))
        ranges (doto (LinearLayout. activity)
                 (.setOrientation LinearLayout/HORIZONTAL)
                 (.addView (rbtn "1周" 7))
                 (.addView (rbtn "1月" 30))
                 (.addView (rbtn "1年" 365)))]
    ;; auto-load one month on first show
    (on-ui #(fetch 30 "1月"))
    (doto (LinearLayout. activity)
      (.setOrientation LinearLayout/VERTICAL)
      (.setPadding pad pad pad pad)
      (.addView (doto (TextView. activity)
                  (.setText "K线 / 均线 📈")
                  (.setTextSize 20.0)
                  (.setTextColor (Color/parseColor "#2E7D32"))))
      (.addView input)
      (.addView ranges)
      (.addView status)
      (.addView chart)
      (.addView (doto (ScrollView. activity)
                  (.addView table))))))

(defn show!
  "Swap the K-line screen onto the foreground Activity."
  ([] (show! (current-activity)))
  ([^Activity activity]
   (if activity
     (do (.runOnUiThread activity #(.setContentView activity (build-view activity)))
         :shown)
     :no-activity)))

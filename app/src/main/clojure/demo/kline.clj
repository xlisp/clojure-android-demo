(ns demo.kline
  "Eastmoney (东方财富) daily K-line viewer, written in Clojure and injected onto
  the running Activity -- the same no-AOT pattern as demo.ui / demo.calc.

  Port of a Kotlin snippet that used okhttp3 + org.json. okhttp is *not* a
  dependency of this project, so the HTTP GET goes through java.net
  HttpURLConnection (as in demo.ui's fetch button); JSON parsing uses
  org.json.JSONObject, which ships in the Android framework. java.time.LocalDate
  is fine here -- minSdk is 26.

  Usage:
    (require 'demo.kline) (demo.kline/show!)
  or via the drawer's \"K-line 📈\" entry (require + build-view injection)."
  (:import [android.app Activity]
           [android.graphics Canvas Color Paint Paint$Style]
           [android.os Handler Looper]
           [android.text InputType]
           [android.view View View$OnClickListener ViewGroup$LayoutParams]
           [android.widget LinearLayout LinearLayout$LayoutParams
                           TextView EditText Button ScrollView]
           [java.net URL HttpURLConnection]
           [java.time LocalDate]
           [java.time.format DateTimeFormatter]
           [org.json JSONObject]))

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

(defn guess-market
  "Eastmoney secid market prefix: 1 = Shanghai (codes starting 6), 0 = Shenzhen."
  [^String code]
  (if (.startsWith code "6") 1 0))

(defn em-kline
  "Fetch daily K-line bars from push2his.eastmoney.com. Returns a vector of
  {:date \"yyyyMMdd\" :close <float>}, oldest first. Mirrors the Kotlin emKline:
  each raw kline is \"date,open,close,high,low,vol,amount,amplitude\" -- index 2
  is the close."
  ([] (em-kline "603052" 1 365))
  ([^String code market days]
   (let [fmt  (DateTimeFormatter/ofPattern "yyyyMMdd")
         end  (.format (LocalDate/now) fmt)
         beg  (.format (.minusDays (LocalDate/now) (long days)) fmt)
         qs   (str "secid=" market "." code
                   "&fields1=f1,f2,f3,f4,f5,f6"
                   "&fields2=f51,f52,f53,f54,f55,f56,f57,f58"
                   "&klt=101&fqt=1&beg=" beg "&end=" end)
         url  (URL. (str "https://push2his.eastmoney.com/api/qt/stock/kline/get?" qs))
         conn ^HttpURLConnection (.openConnection url)]
     (.setRequestMethod conn "GET")
     (.setConnectTimeout conn 15000)
     (.setReadTimeout conn 15000)
     ;; eastmoney is picky about a missing UA
     (.setRequestProperty conn "User-Agent" "Mozilla/5.0")
     (let [body   (slurp (.getInputStream conn))
           klines (.. (JSONObject. ^String body)
                      (getJSONObject "data")
                      (getJSONArray "klines"))]
       (mapv (fn [i]
               (let [p (.split (.getString klines i) ",")]
                 {:date (aget p 0) :close (Float/parseFloat (aget p 2))}))
             (range (.length klines)))))))

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

;; ---- chart drawing ----------------------------------------------------------

(defn make-paint ^Paint [color stroke]
  (doto (Paint. Paint/ANTI_ALIAS_FLAG)
    (.setColor (int color))
    (.setStrokeWidth (float stroke))
    (.setStyle Paint$Style/STROKE)))

(defn draw-series
  "Polyline of `series` (may contain nils) scaled into w x h using value range
  [lo hi]. nil gaps break the line."
  [^Canvas c series ^Paint paint w h lo hi]
  (let [n    (count series)
        span (max 1.0 (double (- hi lo)))]
    (loop [i 1]
      (when (< i n)
        (let [a (nth series (dec i))
              b (nth series i)]
          (when (and a b)
            (let [x0 (float (* (/ (double (dec i)) (max 1 (dec n))) w))
                  x1 (float (* (/ (double i)       (max 1 (dec n))) w))
                  y0 (float (- h (* (/ (- (double a) lo) span) h)))
                  y1 (float (- h (* (/ (- (double b) lo) span) h)))]
              (.drawLine c x0 y0 x1 y1 paint))))
        (recur (inc i))))))

(defn render-chart [^Canvas c closes m5 m20]
  (.drawColor c (Color/parseColor "#102027"))
  (let [w     (.getWidth c)
        h     (.getHeight c)
        valid (remove nil? closes)]
    (when (seq valid)
      (let [lo (double (apply min valid))
            hi (double (apply max valid))]
        (draw-series c closes (make-paint (Color/parseColor "#ECEFF1") 2) w h lo hi)
        (draw-series c m5     (make-paint (Color/parseColor "#FFEB3B") 2) w h lo hi)
        (draw-series c m20    (make-paint (Color/parseColor "#4FC3F7") 2) w h lo hi)))))

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
        closes (atom [])
        m5*    (atom [])
        m20*   (atom [])
        status (doto (TextView. activity)
                 (.setTextSize 13.0)
                 (.setTextColor (Color/parseColor "#B0BEC5"))
                 (.setText "输入股票代码，点 Fetch 拉取日线"))
        chart  (proxy [View] [activity]
                 (onDraw [^Canvas c]
                   (render-chart c @closes @m5* @m20*)))
        _      (.setLayoutParams chart (LinearLayout$LayoutParams.
                                         ViewGroup$LayoutParams/MATCH_PARENT
                                         (dp activity 240)))
        table  (doto (TextView. activity)
                 (.setTextSize 12.0)
                 (.setTypeface android.graphics.Typeface/MONOSPACE)
                 (.setTextColor (Color/parseColor "#CFD8DC"))
                 (.setText ""))
        input  (doto (EditText. activity)
                 (.setHint "603052")
                 (.setText "603052")
                 (.setInputType (bit-or InputType/TYPE_CLASS_TEXT
                                        InputType/TYPE_TEXT_FLAG_NO_SUGGESTIONS)))
        fetch  (fn [_]
                 (let [code (.trim (str (.getText input)))]
                   (.setText status (str "fetching " code " …"))
                   (.start
                     (Thread.
                       (fn []
                         (try
                           (let [bars (em-kline code (guess-market code) 365)
                                 cs   (mapv :close bars)
                                 m5   (ma cs 5)
                                 m20  (ma cs 20)]
                             (reset! closes cs)
                             (reset! m5*    m5)
                             (reset! m20*   m20)
                             (on-ui
                               (fn []
                                 (.setText status
                                           (str code "  " (count cs) " 根日线  最新收盘 "
                                                (when (seq cs) (format "%.2f" (double (peek cs))))))
                                 (.setText table (summary bars m5 m20))
                                 (.invalidate chart))))
                           (catch Throwable t
                             (on-ui #(.setText status (str "error: " (.getMessage t)))))))))))
        legend (doto (TextView. activity)
                 (.setTextSize 12.0)
                 (.setText "▬ 收盘  ▬ MA5  ▬ MA20")
                 (.setTextColor (Color/parseColor "#ECEFF1")))]
    (doto (LinearLayout. activity)
      (.setOrientation LinearLayout/VERTICAL)
      (.setPadding pad pad pad pad)

      (.addView (doto (TextView. activity)
                  (.setText "K线 / 均线 📈")
                  (.setTextSize 20.0)
                  (.setTextColor (Color/parseColor "#2E7D32"))))
      (.addView input)
      (.addView (doto (Button. activity)
                  (.setText "Fetch")
                  (.setAllCaps false)
                  (.setOnClickListener (click fetch))))
      (.addView status)
      (.addView legend)
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

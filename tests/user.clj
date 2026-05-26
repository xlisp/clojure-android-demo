  (let [ctx (let [f (.getDeclaredField clojure.lang.DalvikDynamicClassLoader
                                       "applicationContext")]
              (.setAccessible f true)
              (.get f nil))
        h   (android.os.Handler. (android.os.Looper/getMainLooper))]
    (.post h #(.show (android.widget.Toast/makeText
                       ctx "Hello from CIDER 🎉" android.widget.Toast/LENGTH_LONG)))
    :toast-posted)

  (System/getProperty "java.vm.name")   ;; => "Dalvik"


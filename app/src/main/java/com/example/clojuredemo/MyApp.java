package com.example.clojuredemo;

import android.app.Application;
import android.util.Log;

import clojure.lang.DalvikDynamicClassLoader;
import clojure.lang.IFn;
import clojure.lang.RT;

/**
 * Wires the patched Clojure runtime to Dalvik / ART before any Clojure code runs.
 *
 * The patched clojure.lang.RT.makeClassLoader() already reflects up a
 * DalvikDynamicClassLoader whenever java.vm.name == "Dalvik", so dynamic eval
 * works even without the steps below. We still:
 *   1. give the loader the app Context (needed for assets/data_readers.clj), and
 *   2. install a DalvikDynamicClassLoader as the thread context classloader,
 *      so RT.baseLoader()/require also resolve through it.
 */
public class MyApp extends Application {

    public static final String TAG = "ClojureDemo";

    /** Port the remote nREPL server listens on; connect with `adb forward tcp:6688 tcp:6688`. */
    public static final int NREPL_PORT = 6688;

    // (do (require 'nrepl.server)
    //     (binding [*ns* (create-ns 'user)] (refer-clojure))   ; nREPL's default session ns
    //     (nrepl.server/start-server :bind "0.0.0.0" :port 6688))
    // 'user must refer clojure.core -- the embedded RT (unlike clojure.main) doesn't
    // set it up, so without this a fresh CIDER session can't even resolve `+`.
    private static final String NREPL_BOOT =
            "(do (require 'nrepl.server)"
          + "    (binding [*ns* (create-ns 'user)] (clojure.core/refer-clojure))"
          + "    (nrepl.server/start-server :bind \"0.0.0.0\" :port " + NREPL_PORT + "))";

    @Override
    public void onCreate() {
        super.onCreate();

        // 1. Context for assets (data_readers.clj) and any on-disk cache.
        DalvikDynamicClassLoader.setContext(this);

        // 2. Make the Dalvik loader the context classloader before loading Clojure.
        Thread.currentThread().setContextClassLoader(
                new DalvikDynamicClassLoader(getClass().getClassLoader()));

        Log.i(TAG, "DalvikDynamicClassLoader installed; vm.name="
                + System.getProperty("java.vm.name"));

        // 3. Start the remote nREPL server so Emacs CIDER can connect.
        startNreplServer();
    }

    /**
     * Boots nREPL on a background thread (Application.onCreate must not block).
     * The thread is spawned *after* step 2, so it inherits the Dalvik context
     * classloader; it gets an enlarged stack because compiling nREPL's namespaces
     * on-device (d8) -- and later eval'd forms -- can recurse deeply.
     */
    private void startNreplServer() {
        Thread t = new Thread(null, () -> {
            try {
                IFn loadString = RT.var("clojure.core", "load-string");
                loadString.invoke(NREPL_BOOT);
                Log.i(TAG, "nREPL server listening on 0.0.0.0:" + NREPL_PORT
                        + "  (adb forward tcp:" + NREPL_PORT + " tcp:" + NREPL_PORT + ")");
            } catch (Throwable e) {
                Log.e(TAG, "nREPL server failed to start", e);
            }
        }, "nrepl-bootstrap", 4 * 1024 * 1024 /* 4 MB stack */);
        t.setDaemon(true);
        t.start();
    }
}

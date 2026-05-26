package com.example.clojuredemo;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
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

    /** Port the on-device MCP server listens on; see demo/mcp.clj. */
    public static final int MCP_PORT = 6689;

    /**
     * The foreground Activity, tracked below. Lets Clojure code evaluated from the
     * nREPL grab an Activity (e.g. to swap in a Clojure-built View). See demo/ui.clj.
     */
    public static volatile Activity currentActivity;

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

        // 3. Track the foreground Activity so REPL code can reach it.
        registerActivityLifecycleCallbacks(new ActivityTracker());

        // 4. Start the remote nREPL server so Emacs CIDER can connect.
        startNreplServer();

        // 5. Start the on-device MCP server so an MCP client can drive the device.
        startMcpServer();
    }

    /** Records the resumed Activity in {@link #currentActivity}; all else is no-op. */
    private static final class ActivityTracker implements ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity a) { currentActivity = a; }
        @Override public void onActivityPaused(Activity a) {
            if (currentActivity == a) currentActivity = null;
        }
        @Override public void onActivityCreated(Activity a, Bundle b) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        @Override public void onActivityDestroyed(Activity a) {}
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

    /**
     * Boots the on-device MCP server (demo/mcp.clj) on its own background thread,
     * after a short delay so it doesn't compete with nREPL's heavier on-device
     * compile for the first few seconds. Like nREPL it inherits the Dalvik context
     * classloader (the thread is spawned from onCreate, after step 2) and gets a
     * large stack; failures are logged, never fatal. Start it manually instead
     * from CIDER with (require 'demo.mcp) (demo.mcp/start!).
     */
    private void startMcpServer() {
        Thread t = new Thread(null, () -> {
            try {
                Thread.sleep(2000);
                IFn loadString = RT.var("clojure.core", "load-string");
                Object status = loadString.invoke(
                        "(do (require 'demo.mcp) (demo.mcp/start! " + MCP_PORT + "))");
                Log.i(TAG, "MCP: " + status
                        + "  (adb forward tcp:" + MCP_PORT + " tcp:" + MCP_PORT + ")");
            } catch (Throwable e) {
                Log.e(TAG, "MCP server failed to start", e);
            }
        }, "mcp-bootstrap", 4 * 1024 * 1024 /* 4 MB stack */);
        t.setDaemon(true);
        t.start();
    }
}

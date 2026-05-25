package com.example.clojuredemo;

import android.app.Application;
import android.util.Log;

import clojure.lang.DalvikDynamicClassLoader;

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
    }
}

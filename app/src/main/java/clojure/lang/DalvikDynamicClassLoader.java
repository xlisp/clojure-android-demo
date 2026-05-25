/* Modern Android variant (Android 8.0+ / API 26+).
 * Uses d8 instead of the deprecated dx, and InMemoryDexClassLoader so no
 * DEX is ever written to disk.
 *
 * Plugs into the (concrete) clojure.lang.DynamicClassLoader from the main
 * Clojure jar via the defineMissingClass / getDataReadersStream hooks added
 * in 1.13. This file imports android.* and com.android.tools.r8.*, so it
 * MUST NOT be compiled into the main clojure jar — build it as this separate
 * Android module only.
 *
 * EPL 1.0
 */
package clojure.lang;

import android.content.Context;
import android.util.Log;

import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.ByteDataView;
import com.android.tools.r8.DexIndexedConsumer;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.origin.Origin;

import dalvik.system.InMemoryDexClassLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Modern Android dynamic class loader (API 26+):
 *   - uses d8 to translate JVM bytecode to DEX (replacing the deprecated dx)
 *   - uses InMemoryDexClassLoader to load it in memory (no temp files on disk)
 *
 * No cacheDir, no temp .dex files, faster startup.
 */
public class DalvikDynamicClassLoader extends DynamicClassLoader {

    private static final String TAG = "DalvikClojureCompiler";
    private static Context applicationContext;

    public DalvikDynamicClassLoader() {
        super();
    }

    public DalvikDynamicClassLoader(final ClassLoader parent) {
        super(parent);
    }

    @Override
    protected Class<?> defineMissingClass(final String name, final byte[] bytes,
            final Object srcForm) {
        try {
            // 1. Translate JVM bytecode to DEX bytes with d8.
            final AtomicReference<byte[]> dexOut = new AtomicReference<>();

            D8.run(D8Command.builder()
                    .addClassProgramData(bytes, Origin.unknown())
                    .setMinApiLevel(26)
                    .setProgramConsumer(new DexIndexedConsumer() {
                        @Override
                        public void accept(int fileIndex, ByteDataView data,
                                           Set<String> descriptors,
                                           DiagnosticsHandler handler) {
                            dexOut.set(data.copyByteData());
                        }
                        @Override
                        public void finished(DiagnosticsHandler handler) {}
                    })
                    .build());

            byte[] dexBytes = dexOut.get();
            if (dexBytes == null) {
                throw new RuntimeException("d8 produced no DEX output for " + name);
            }

            // 2. Load that DEX in memory and resolve the class we want.
            ByteBuffer buf = ByteBuffer.wrap(dexBytes);
            InMemoryDexClassLoader dexLoader = new InMemoryDexClassLoader(buf, this);
            return dexLoader.loadClass(name);

        } catch (Exception e) {
            Log.e(TAG, "Failed to define class " + name, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public InputStream getDataReadersStream() {
        if (applicationContext != null) {
            try {
                return applicationContext.getAssets().open("data_readers.clj");
            } catch (IOException ignored) {}
        }
        return null;
    }

    /** Call this in Application.onCreate() before any Clojure code is run. */
    public static void setContext(Context context) {
        applicationContext = context;
    }
}

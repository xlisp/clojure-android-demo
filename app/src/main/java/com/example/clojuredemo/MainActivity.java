package com.example.clojuredemo;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import clojure.lang.IFn;
import clojure.lang.RT;
import clojure.lang.Var;

/**
 * Demonstrates the patched Clojure running on Android:
 *   - AOT path: call bundled clojure.core functions directly (no compilation).
 *   - Dynamic path: load-string / eval, which compiles fresh bytecode and runs
 *     it through DalvikDynamicClassLoader (JVM bytecode -> d8 -> DEX -> load).
 *
 * All Clojure work runs off the UI thread (RT init + compilation are slow).
 */
public class MainActivity extends AppCompatActivity {

    private final ExecutorService clojureExec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView output;
    private ScrollView scroller;
    private EditText input;
    private Button evalButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        output = findViewById(R.id.output);
        scroller = findViewById(R.id.scroller);
        input = findViewById(R.id.input);
        evalButton = findViewById(R.id.evalButton);
        evalButton.setEnabled(false);

        evalButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String src = input.getText().toString().trim();
                if (!src.isEmpty()) evalAsync(src);
            }
        });

        append("Booting Clojure runtime…");
        bootstrapAsync();
    }

    /** Force RT static init (loads clojure.core) and run the AOT smoke tests. */
    private void bootstrapAsync() {
        clojureExec.execute(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                long t0 = System.currentTimeMillis();

                // Touching RT triggers <clinit> -> loads clojure.core (the patched RT
                // also picks a DalvikDynamicClassLoader because vm.name == "Dalvik").
                Var clojureVersion = RT.var("clojure.core", "clojure-version");
                Var vmType = RT.var("clojure.core", "vm-type");   // <-- added by the patch
                long bootMs = System.currentTimeMillis() - t0;

                sb.append("Clojure ").append(clojureVersion.invoke()).append('\n');
                sb.append("java.vm.name = ").append(System.getProperty("java.vm.name")).append('\n');
                sb.append("clojure.core/vm-type = ").append(vmType.deref()).append('\n');
                sb.append("boot = ").append(bootMs).append(" ms\n\n");

                // --- AOT path: direct var invocation, no compilation ---
                IFn plus = RT.var("clojure.core", "+");
                IFn apply = RT.var("clojure.core", "apply");
                IFn range = RT.var("clojure.core", "range");
                sb.append("[AOT] (+ 1 2) = ").append(plus.invoke(1, 2)).append('\n');
                sb.append("[AOT] (apply + (range 1 101)) = ")
                  .append(apply.invoke(plus, range.invoke(1, 101))).append('\n');

                // --- Dynamic path: compile + run fresh code via DalvikDynamicClassLoader ---
                sb.append("\n[EVAL] compiling on device via d8…\n");
                Object r1 = evalString("(+ 1 2)");
                sb.append("[EVAL] (+ 1 2) = ").append(r1).append('\n');
                Object r2 = evalString(
                        "(do (defn sq [x] (* x x)) (mapv sq (range 1 6)))");
                sb.append("[EVAL] (mapv sq (range 1 6)) = ").append(r2).append('\n');

                sb.append("\nReady. Type a Clojure expression below and tap Eval.");
            } catch (Throwable t) {
                sb.append("\n!! bootstrap failed:\n").append(stack(t));
            }
            final String msg = sb.toString();
            ui.post(() -> {
                append(msg);
                evalButton.setEnabled(true);
            });
        });
    }

    private void evalAsync(final String src) {
        append("\n> " + src);
        evalButton.setEnabled(false);
        clojureExec.execute(() -> {
            String line;
            try {
                line = "=> " + str(evalString(src));
            } catch (Throwable t) {
                line = "!! " + stack(t);
            }
            final String out = line;
            ui.post(() -> {
                append(out);
                evalButton.setEnabled(true);
            });
        });
    }

    /**
     * Read + eval a string. clojure.core/load-string compiles each form to JVM
     * bytecode; on Dalvik that goes through DalvikDynamicClassLoader.defineMissingClass.
     */
    private static Object evalString(String src) {
        IFn loadString = RT.var("clojure.core", "load-string");
        return loadString.invoke(src);
    }

    private static String str(Object o) {
        IFn prStr = RT.var("clojure.core", "pr-str");
        return (String) prStr.invoke(o);
    }

    private static String stack(Throwable t) {
        android.util.Log.e(MyApp.TAG, "clojure error", t);
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        String full = sw.toString();
        // Keep the UI readable: first ~20 lines of the trace.
        String[] lines = full.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 20); i++) sb.append(lines[i]).append('\n');
        return sb.toString();
    }

    private void append(final String text) {
        output.append(text + "\n");
        scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
    }
}

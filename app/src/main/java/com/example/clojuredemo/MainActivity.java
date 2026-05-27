package com.example.clojuredemo;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import clojure.lang.IFn;
import clojure.lang.RT;
import clojure.lang.Symbol;
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

    private DrawerLayout drawer;
    private ActionBarDrawerToggle toggle;
    private FrameLayout contentContainer;
    private View evalView;

    private TextView output;
    private ScrollView scroller;
    private EditText input;
    private Button evalButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Left navigation drawer + hamburger icon in the action bar.
        drawer = findViewById(R.id.drawer);
        toggle = new ActionBarDrawerToggle(
                this, drawer, R.string.drawer_open, R.string.drawer_close);
        drawer.addDrawerListener(toggle);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toggle.syncState();

        // The Eval screen lives in the swappable content container.
        contentContainer = findViewById(R.id.content_container);
        evalView = getLayoutInflater().inflate(R.layout.content_eval, contentContainer, false);
        contentContainer.addView(evalView);

        output = evalView.findViewById(R.id.output);
        scroller = evalView.findViewById(R.id.scroller);
        input = evalView.findViewById(R.id.input);
        evalButton = evalView.findViewById(R.id.evalButton);
        evalButton.setEnabled(false);

        evalButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String src = input.getText().toString().trim();
                if (!src.isEmpty()) evalAsync(src);
            }
        });

        // Drawer menu entries.
        findViewById(R.id.menu_home).setOnClickListener(v -> showEval());
        findViewById(R.id.menu_clojure_ui).setOnClickListener(v -> showClojurePage("demo.ui"));
        findViewById(R.id.menu_calc).setOnClickListener(v -> showClojurePage("demo.calc"));
        findViewById(R.id.menu_input).setOnClickListener(v -> showClojurePage("demo.input"));

        append("Booting Clojure runtime…");
        bootstrapAsync();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return toggle.onOptionsItemSelected(item) || super.onOptionsItemSelected(item);
    }

    /** Drawer: show the built-in Eval screen. */
    private void showEval() {
        if (evalView.getParent() != contentContainer) {
            contentContainer.removeAllViews();
            contentContainer.addView(evalView);
        }
        drawer.closeDrawers();
    }

    /**
     * Drawer: enter a Clojure-authored page. Requires the namespace (compiled
     * on-device from the .clj packaged in the APK), then injects its
     * (ns/build-view this) View into the content container. Falls back to the
     * Eval screen on failure. First load of an unseen ns triggers d8/DEX
     * compilation and can take a while, so it runs off the UI thread.
     */
    private void showClojurePage(final String ns) {
        drawer.closeDrawers();
        clojureExec.execute(() -> {
            try {
                RT.var("clojure.core", "require").invoke(Symbol.intern(ns));
                ui.post(() -> {
                    try {
                        View page = (View) RT.var(ns, "build-view").invoke(this);
                        contentContainer.removeAllViews();
                        contentContainer.addView(page);
                    } catch (Throwable t) {
                        showEval();
                        append("\n!! " + ns + "/build-view failed:\n" + stack(t));
                    }
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    showEval();
                    append("\n!! require " + ns + " failed:\n" + stack(t));
                });
            }
        });
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

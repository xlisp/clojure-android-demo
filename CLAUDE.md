# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android app that runs **patched Clojure 1.13** on device, including **on-device
dynamic eval**: JVM bytecode emitted by the Clojure compiler is translated to DEX
*at runtime* (d8 + `InMemoryDexClassLoader`) and loaded. The patched Clojure jar
itself is built in a **sibling repo** (`../clojure`); this repo is the runnable
Android demo that consumes it.

## Commands

```bash
# Build / install / run (Android SDK path is in local.properties; a device/emulator must be attached)
./gradlew :app:assembleDebug
./gradlew :app:installDebug
adb shell am start -n com.example.clojuredemo/.MainActivity

# Logs — two tags matter:
adb logcat -s ClojureDemo          # MyApp / MainActivity (boot, nREPL status, errors)
adb logcat -s DalvikClojureCompiler # the on-device d8 class loader

# Remote REPL: connect Emacs CIDER to the on-device nREPL (port 6688)
adb forward tcp:6688 tcp:6688
#   then: M-x cider-connect → localhost → 6688

# Rebuild the patched Clojure jar (only when changing the runtime itself)
cd ../clojure && ./build-jar.sh   # copy the resulting jar into app/libs/
```

There is **no test suite and no Clojure build step**. Verification in this repo is
done by installing, launching, watching `logcat -s ClojureDemo`, and
`adb exec-out screencap -p > /tmp/x.png` to confirm UI/behaviour on a real device.

## Architecture

**The runtime is bundled, not compiled here.** `app/libs/` holds pre-built AOT jars:
the patched `clojure-1.13.0-master-SNAPSHOT.jar` plus `spec.alpha` and
`core.specs.alpha`. Gradle compiles **only Java**. Every piece of Clojure is either
(a) AOT'd in the bundled jar (`clojure.core` etc.), or (b) compiled **on-device** at
runtime. There is no `compileClojure` task — do not look for one.

**The on-device compiler hook.** `app/src/main/java/clojure/lang/DalvikDynamicClassLoader.java`
lives in the `clojure.lang` package on purpose: it extends the patched jar's
`DynamicClassLoader` and overrides `defineMissingClass` to run d8/r8 (JVM bytecode →
DEX) and load via `InMemoryDexClassLoader`. It imports `android.*` and
`com.android.tools.r8.*`, so it **must stay in this app module, never in the clojure
jar**. The patched `RT.makeClassLoader()` auto-selects it whenever
`java.vm.name == "Dalvik"`; the whole Android path is inert on a normal JVM.

**Boot wiring** (`com.example.clojuredemo`):
- `MyApp.onCreate` — sets the loader's app `Context`, installs a
  `DalvikDynamicClassLoader` as the thread context classloader (so `require`
  resolves through it), starts the nREPL server, and registers an
  `ActivityLifecycleCallbacks` that records the foreground Activity in
  `MyApp.currentActivity` (so REPL/Clojure code can reach an Activity).
- `MainActivity` — touches `RT` to trigger `clojure.core` load + runs AOT/eval smoke
  tests on a single-thread executor (never on the UI thread; init + on-device
  compilation are slow). Hosts a left navigation drawer that swaps the content
  container between the built-in Eval screen (`content_eval.xml`) and the
  Clojure-authored page.

**Remote nREPL** — `MyApp` boots a plain nREPL server on **port 6688** (`MyApp.NREPL_PORT`)
by `load-string`-ing `nrepl.server/start-server` on a large-stack daemon thread.
nREPL ships pure `.clj` and is compiled on-device like everything else, so the
**first CIDER connect takes ~30s**. It's plain nREPL (no `cider-nrepl` middleware —
too heavy to compile on-device). `nrepl:nrepl` (clojure excluded) is in
`app/build.gradle`; `INTERNET` permission (for the ServerSocket) is in the manifest.

**Writing Android UI in Clojure** (see `app/src/main/clojure/demo/ui.clj`). You
**cannot** define a manifest-launched Activity in Clojure here: that needs build-time
AOT (which this project lacks), and the framework instantiates Activities by name via
the app `PathClassLoader`, which can't see classes generated on-device. The idiomatic
pattern instead is: build the `View` tree programmatically in Clojure and
`setContentView`/inject it onto the **already-running** Activity. Two entry points:
1. **Live from CIDER** — `C-c C-k` to compile the buffer on-device, then `(demo.ui/show!)`; edit + reload to redesign live.
2. **In-app** — the drawer's "Clojure UI demo" item calls `(require 'demo.ui)` then injects `demo.ui/build-view` into the content container.

For (2) to work without CIDER, `app/build.gradle` adds
`sourceSets.main.resources.srcDirs += ['src/main/clojure']`, which **packages the
`.clj` into the APK as a classpath resource** so `require` can find and compile it
on-device. **Any new on-device-loadable namespace must live under `src/main/clojure`**
to be `require`-able in-app.

## Constraints (load-bearing — don't "fix" these)

- `minSdk 26` — d8 + `InMemoryDexClassLoader` require API ≥ 26.
- `multiDexEnabled true` (Clojure + r8 exceed 64K methods); `minifyEnabled false`
  (Clojure relies on runtime reflection — shrinking breaks it).
- `app/build.gradle` packaging keeps `**/*.clj` (`pickFirsts`) and excludes
  `data_readers.clj` from the merge; `assets/data_readers.clj` is read by the patched
  `load-data-readers` via `DalvikDynamicClassLoader.getDataReadersStream()`.
- Two fixes required for on-device eval live in `../clojure`, not here: a real
  `InputStream` path in `load-data-readers`, and a `Reflector.<clinit>` guard for
  Android's missing `AccessibleObject.canAccess`. See `../clojure/android-support/`.

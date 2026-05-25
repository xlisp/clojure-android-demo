# clojure-android-demo

A minimal Android app that runs the **patched Clojure 1.13** (the
`DynamicClassLoader` hook + `RT.VM_TYPE` + Dalvik loader changes) on a device,
including on-device `eval` — JVM bytecode is translated to DEX at runtime by
`clojure.lang.DalvikDynamicClassLoader` (d8 + `InMemoryDexClassLoader`).

![](./clj_demo3.png)

## What it shows

On launch (`MainActivity`):

- **AOT path** — calls bundled `clojure.core` functions directly, no compilation:
  `(+ 1 2)`, `(apply + (range 1 101))`.
- **`vm-type`** — prints `clojure.core/vm-type`, the Var added by the patch
  (`:dalvik-vm` on a device, `:java-vm` on the JVM).
- **Dynamic path** — `load-string` compiles fresh code and runs it through the
  Dalvik loader: `(+ 1 2)`, `(do (defn sq [x] (* x x)) (mapv sq (range 1 6)))`.
- A text box + **Eval** button = a tiny on-device REPL.

## Prerequisites / how it was built

1. Build the patched jar in the sibling Clojure repo:
   ```bash
   cd ../clojure && ./build-jar.sh
   ```
   It produces `clojure-1.13.0-master-SNAPSHOT.jar`, already copied here into
   `app/libs/` along with its two AOT-time deps (`spec.alpha`,
   `core.specs.alpha`).
2. `app/src/main/java/clojure/lang/DalvikDynamicClassLoader.java` is the d8 loader
   copied from `../clojure/android-support/` (it imports `android.*` / r8, so it
   lives in the app, not the main jar).

## Build & run

```bash
# Android SDK path is in local.properties (edit if yours differs).
./gradlew :app:assembleDebug              # build the APK
./gradlew :app:installDebug               # install on a connected device/emulator
# then launch "Clojure 1.13 Demo", or:
adb shell am start -n com.example.clojuredemo/.MainActivity
adb logcat -s ClojureDemo                 # watch the loader log
```

Or just open this folder in Android Studio and Run.

## Versions / config

| | |
|---|---|
| AGP | 8.5.0 |
| Gradle | 8.11.1 |
| compileSdk / targetSdk | 34 |
| minSdk | **26** (d8 + `InMemoryDexClassLoader` requires API ≥ 26) |
| multiDex | enabled (Clojure + r8 exceed 64K methods) |
| minify | **off** (Clojure relies on runtime reflection) |
| on-device translator | `com.android.tools:r8:8.2.47` |

## Notes & gotchas

- **First boot is slow** (~1–2 s) — loading the AOT'd `clojure.core` and, for
  eval, running r8/d8 on the device. All Clojure work is on a background thread
  to avoid ANR.
- **`largeHeap="true"`** is set; Clojure's runtime is memory-hungry.
- The eval path bundles r8 (~MBs) into the APK so d8 can run on the device. If
  on-device d8 misbehaves on a given device, the AOT path still works and the
  eval errors are shown in the output pane (not swallowed).
- `app/src/main/assets/data_readers.clj` is read by the patched
  `load-data-readers` via `DalvikDynamicClassLoader.getDataReadersStream()`.
- To use the **legacy dx** loader instead of d8, swap in
  `../clojure/android-support/DalvikDynamicClassLoader_dx_legacy.java.alt` and
  change the r8 dependency to `com.android.tools:dx:1.16`.

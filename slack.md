  :rocket: *Old project, revived: Clojure 1.13 now runs on Android!*

  I ported *Clojure 1.13* to run on Android (Dalvik / ART) — and not just loading
  it: it supports *on-device dynamic eval* (JVM bytecode → d8 → DEX, compiled at
  runtime). So you get a real REPL-style "evaluate Clojure live" experience right
  on the phone :smile:

  *Why it's neat*
  • Tracks the latest Clojure (1.13) — not stuck on the old 1.7 branch
  • The changes are *fully transparent and zero-overhead on the JVM* — the Android
    path only kicks in when `java.vm.name == "Dalvik"`
  • Verified on an Android API 36 emulator: boot → AOT calls → on-device eval, all green

  *Two repos*
  • Patched Clojure :point_right: https://github.com/xlisp/clojure-android
  • Runnable Android demo :point_right: https://github.com/xlisp/clojure-android-demo

  Clone it and give it a spin — stars / issues / PRs welcome :tada:

  — Steve (founder, Hulu Note)

  ---
  Short version (for a general channel / reshares)

  :tada: *You can write Android apps in Clojure now* — and on the latest *Clojure 1.13*.

  I patched Clojure 1.13 to support Android (Dalvik/ART), including on-device
  dynamic eval. Zero impact on the JVM; the Android path only activates on-device.

  • Patched Clojure: https://github.com/xlisp/clojure-android
  • Android demo: https://github.com/xlisp/clojure-android-demo

  Give it a try :blush:

  ---
  Notes:
  - Same factual basis as the Chinese version — the on-device eval and API 36 emulator run are things we actually built and verified, so it's safe to
  state.
  - Slack :rocket: / :tada: shortcodes auto-render; drop any emoji your workspace doesn't have.
  - Attaching the emulator screenshot (clj_demo3.png) makes it land much harder than text alone.


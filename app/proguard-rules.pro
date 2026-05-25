# Minification is disabled for the demo (see app/build.gradle). If you turn it
# on, Clojure's reflection-heavy runtime needs broad keep rules, e.g.:
-keep class clojure.** { *; }
-keep class com.android.tools.r8.** { *; }
-dontwarn clojure.**
-dontwarn com.android.tools.r8.**

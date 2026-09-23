# Only applied with -PvoiceTestRelease. The separate instrumentation APK calls
# Kotlin methods unused by the app; retain that ABI for the acceptance build.
# Normal production Release must not inherit this test-only retention rule.
-keep class kotlin.** { *; }

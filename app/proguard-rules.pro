# HAND-OWNED (quick-log). Dropbox Java SDK: optional HTTP back-ends and server-only
# classes it references but the app never uses. Without these R8 stops on missing classes.
-dontwarn okio.**
-dontwarn okhttp3.**
-dontwarn com.squareup.okhttp.**
-dontwarn com.google.appengine.**
-dontwarn javax.servlet.**
-dontwarn org.joda.convert.**

# HAND-OWNED (quick-log): this file has no `-assumenosideeffects` rule for android.util.Log
# (and no default Android proguard file is applied - app/build.gradle's release buildType lists
# only this file), so R8 does NOT strip Log.i calls in release. Verified with
# `unzip -p build/outputs/mapping/release/mapping.txt` after `assembleRelease` and grepping for
# the QuestaReminder-tagged call sites (see README §8). Do not add an assumenosideeffects rule for
# Log here without keeping Log.i reachable, or the reminder diagnostics in release go silent.

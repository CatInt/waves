# R8 rules for the Android release build (isMinifyEnabled + isShrinkResources).
# Media3, WorkManager, play-services-auth, Coil, kotlinx.coroutines and kotlinx.serialization ship their own
# consumer rules; this file only covers what they don't.

# --- kotlinx.serialization: keep serializers of the app's @Serializable classes (sync JSON, iTunes/Drive APIs).
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class io.waves.** {
    static ** Companion;
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class io.waves.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.waves.**$$serializer { *; }

# --- Ktor: the Android engine is found via ServiceLoader; JVM-only references are never hit on Android.
-keep class io.ktor.client.engine.android.** { *; }
-keepnames class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**

# youtubedl-android doesn't ship consumer rules. Jackson reads the GitHub release JSON for the yt-dlp update,
# and commons-compress unpacks the bundled Python. Both are reached through the kept library classes.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.compress.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.compress.**

# Keep line numbers for readable crash stack traces.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

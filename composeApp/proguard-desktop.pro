# ProGuard rules for the desktop release build (createReleaseDistributable / packageReleaseDmg).
# Compose Multiplatform's default rules (Compose, Skiko, coroutines) are applied on top of these.
# Obfuscation is off (the Compose default); this only shrinks and optimizes.

# --- JavaFX Media (player/DesktopAudioPlayer): toolkit and media pipeline are loaded reflectively and from
# native code (JNI callbacks), so keep the whole toolkit.
-keep class javafx.** { *; }
-keep class com.sun.javafx.** { *; }
-keep class com.sun.glass.** { *; }
-keep class com.sun.prism.** { *; }
-keep class com.sun.media.** { *; }
-keep class com.sun.scenario.** { *; }
-keep class com.sun.marlin.** { *; }
-keep class com.sun.pisces.** { *; }
-keep class com.sun.openpisces.** { *; }
-dontwarn javafx.**
-dontwarn com.sun.javafx.**
-dontwarn com.sun.glass.**
-dontwarn com.sun.prism.**
-dontwarn com.sun.media.**
-dontwarn com.sun.webkit.**
-dontwarn jdk.internal.**

# --- SQLite JDBC driver (SQLDelight's JdbcSqliteDriver): JNI + DriverManager/ServiceLoader.
-keep class org.sqlite.** { *; }
-dontwarn org.sqlite.**

# --- JNA (player/MacNowPlaying): its native stub calls back into these classes by name, and it reflects on
# Callback interfaces.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Callback { *; }
-dontwarn com.sun.jna.**

# --- Ktor: engines and plugins are discovered via ServiceLoader.
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# --- kotlinx.serialization: keep generated serializers and companion lookups.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-dontnote io.ktor.**
-dontnote io.waves.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
    *** Companion;
    static **$* *;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class **$$serializer { *; }
-dontwarn kotlinx.serialization.**

# --- Kotlin / coroutines odds and ends.
-dontwarn kotlinx.coroutines.debug.**
-dontwarn kotlin.concurrent.atomics.**
-dontwarn org.jetbrains.annotations.**

# --- ServiceLoader registrations (META-INF/services) that ProGuard can't trace.
# Dispatchers.Main on desktop (otherwise "Module with the Main dispatcher had failed to initialize").
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.CoroutineExceptionHandler { *; }
# Coil's Ktor network fetcher.
-keep class * implements coil3.util.FetcherServiceLoaderTarget { *; }
-keep class * implements coil3.util.DecoderServiceLoaderTarget { *; }
-dontwarn coil3.**
-dontwarn okio.**

# --- App code is small: keep it whole so nothing reached via serialization, SQLDelight adapters or
# JavaFX callbacks is merged away (and so release builds can be smoke-tested against stable names).
-keep class io.waves.** { *; }

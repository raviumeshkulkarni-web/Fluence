# ============================================================
# ProGuard / R8 rules for Groq Voice Typer
# ============================================================

# --- OkHttp ---
# OkHttp platform adapter uses reflection for TLS/SSL
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep OkHttp's public API and internal platform classes
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.** { *; }

# --- Kotlin Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# --- AndroidX Security / Tink ---
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# --- Compose ---
# Keep Compose runtime stability metadata
-keep class androidx.compose.runtime.** { *; }

# --- App classes ---
# Keep the IME service (referenced by manifest)
-keep class com.groq.voicetyper.VoiceInputIME { *; }
-keep class com.groq.voicetyper.MainActivity { *; }

# Keep SecurityUtils since it's accessed across processes
-keep class com.groq.voicetyper.SecurityUtils { *; }

# --- General ---
# Keep annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod

# =============================================================================
# react-native-sync-provider — ProGuard/R8 rules
# Shipped as consumer-rules so the host app inherits them automatically.
# =============================================================================

# --- Nitro / DoNotStrip ------------------------------------------------------
-keep class com.margelo.nitro.syncprovider.** { *; }
-keep @com.facebook.proguard.annotations.DoNotStrip class * { *; }
-keepclassmembers class * {
    @com.facebook.proguard.annotations.DoNotStrip *;
}

# --- AndroidX Keep / @Keep ---------------------------------------------------
-keep,allowobfuscation,allowshrinking @androidx.annotation.Keep class *
-keepclassmembers,allowobfuscation,allowshrinking class * {
    @androidx.annotation.Keep *;
}

# --- Room --------------------------------------------------------------------
-keep class androidx.room.** { *; }
-keep class androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public abstract <methods>;
}

# Keep generated Room implementations.
-keep class **_Impl { *; }
-keep class **_Impl$* { *; }
-dontwarn androidx.room.paging.**

# --- WorkManager -------------------------------------------------------------
# WorkManager instantiates Worker subclasses by reflection (no-arg constructor).
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}

# --- OkHttp / Okio -----------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# --- Kotlin Coroutines -------------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# --- Kotlin Metadata ---------------------------------------------------------
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class kotlin.Metadata { *; }

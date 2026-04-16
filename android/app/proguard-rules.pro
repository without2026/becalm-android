# ProGuard rules for BeCalm Android
# Applied to release builds via build.gradle.kts: proguardFiles(...)

# ---- Retrofit ----
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ---- Moshi ----
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
# Keep generated Moshi adapter classes
-keep class **JsonAdapter { *; }
-keepnames class **JsonAdapter

# ---- BeCalm DTOs — Moshi serialization ----
-keep class com.becalm.android.data.remote.dto.** { *; }
-keepclassmembers class com.becalm.android.data.remote.dto.** {
    <init>(...);
    <fields>;
}

# ---- Hilt / Dagger ----
-dontwarn com.google.dagger.**
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}
-keep @dagger.hilt.android.HiltAndroidApp class *
-keep @dagger.hilt.InstallIn class *

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.**

# ---- WorkManager / HiltWorker ----
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep @androidx.hilt.work.HiltWorker class * { *; }

# ---- Jetpack Compose ----
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ---- DataStore ----
-keep class androidx.datastore.** { *; }

# ---- Kotlin coroutines / reflection ----
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }
-keepattributes LineNumberTable,SourceFile

# ---- General Android lifecycle ----
-keep class androidx.lifecycle.** { *; }
-keep class * extends androidx.lifecycle.ViewModel { *; }

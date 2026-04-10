# ErrorProne annotations (used by Tink, not needed at runtime)
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Moshi
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepnames class * { @com.squareup.moshi.FromJson *; @com.squareup.moshi.ToJson *; }
# KotlinJsonAdapterFactory использует kotlin-reflect — сохраняем аннотации и внутренности
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.jvm.internal.** { *; }
-dontwarn kotlin.reflect.jvm.internal.**
# Все DTO и API классы — Moshi разбирает их через KotlinJsonAdapterFactory
-keep class com.secure.messenger.data.remote.api.** { *; }
-keep class com.secure.messenger.data.remote.api.dto.** { *; }
# SignalingMessage — рефлексивный адаптер с Map<String,Any?>
-keep class com.secure.messenger.data.remote.websocket.SignalingMessage { *; }

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Hilt
-keep class dagger.hilt.** { *; }
-keepclasseswithmembers class * { @dagger.hilt.android.AndroidEntryPoint *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

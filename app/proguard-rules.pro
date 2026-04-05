# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Moshi
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepnames class * { @com.squareup.moshi.FromJson *; @com.squareup.moshi.ToJson *; }

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

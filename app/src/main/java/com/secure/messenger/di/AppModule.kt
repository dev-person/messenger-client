package com.secure.messenger.di

import android.content.Context
import androidx.room.Room
import com.secure.messenger.BuildConfig
import com.secure.messenger.data.local.database.AppDatabase
import com.secure.messenger.data.remote.api.MessengerApi
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ── Moshi ─────────────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(Unit::class.java, UnitAdapter)   // handles ApiResponse<Unit> (endpoints that return no data)
        .addLast(KotlinJsonAdapterFactory())
        .build()

    // ── OkHttp ────────────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideOkHttpClient(tokenProvider: AuthTokenProvider): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = tokenProvider.token
                val request = if (token != null) {
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                    })
                }
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Ping каждые 25 сек держит соединение живым через NAT/файрвол.
            // Если pong не пришёл — OkHttp закрывает сокет и мы получаем onFailure → переподключение.
            .pingInterval(25, TimeUnit.SECONDS)
            .build()

    // ── Retrofit ──────────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideMessengerApi(retrofit: Retrofit): MessengerApi =
        retrofit.create(MessengerApi::class.java)

    // ── Room ──────────────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "messenger.db")
            // TODO: Replace fallbackToDestructiveMigration() with real migrations before production release.
            //  This destroys all data on schema change — acceptable only during early development.
            .fallbackToDestructiveMigration()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    super.onOpen(db)
                    // Защита от SQLiteBlobTooBigException: удаляем сообщения с
                    // encryptedContent > 1.5 МБ — Android Cursor не может прочитать
                    // строки больше своего window (~2 МБ) и приложение крашится
                    // при открытии чата. Такие сообщения могли остаться после
                    // отправки крупных анимаций до фикса MAX_RAW_BYTES.
                    runCatching {
                        db.execSQL("DELETE FROM messages WHERE LENGTH(encryptedContent) > 1500000")
                    }
                }
            })
            .build()

    @Provides fun provideChatDao(db: AppDatabase) = db.chatDao()
    @Provides fun provideMessageDao(db: AppDatabase) = db.messageDao()
    @Provides fun provideUserDao(db: AppDatabase) = db.userDao()
}

/**
 * Moshi adapter for kotlin.Unit.
 *
 * Retrofit endpoints that return no meaningful data use ApiResponse<Unit>.
 * The server sends {"success":true,"data":null} — Moshi must be taught to
 * skip the "data" field value and produce Unit, since it has no built-in
 * knowledge of the Kotlin Unit type.
 *
 * Note: @FromJson/@ToJson annotations cannot be used here because a Kotlin
 * function returning Unit compiles to JVM void, which Moshi rejects. Instead
 * we subclass JsonAdapter<Unit> directly.
 */
object UnitAdapter : JsonAdapter<Unit>() {
    override fun fromJson(reader: JsonReader): Unit? {
        reader.skipValue()
        return null
    }

    override fun toJson(writer: JsonWriter, value: Unit?) {
        writer.nullValue()
    }
}

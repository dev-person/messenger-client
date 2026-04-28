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
            // Retry на 503 с exponential backoff — сервер под nginx'ом с
            // limit_req иногда возвращает 503 при всплесках (например при
            // массовой отправке альбома из 15 фото). 6 повторов, суммарно
            // до ~15 секунд ожидания.
            .addInterceptor { chain ->
                val req = chain.request()
                var response = chain.proceed(req)
                var attempt = 0
                val delays = longArrayOf(300, 700, 1500, 3000, 4500, 6000)
                while (response.code == 503 && attempt < delays.size) {
                    response.close()
                    Thread.sleep(delays[attempt])
                    attempt++
                    response = chain.proceed(req)
                }
                response
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
            // Реальные миграции — когда у юзера уже накоплена история, ронять её
            // на каждый ALTER TABLE недопустимо. Если миграции не хватит — fallback,
            // чтобы не крашиться в проде.
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
            )
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
    @Provides fun provideGroupSenderKeyDao(db: AppDatabase) = db.groupSenderKeyDao()
    @Provides fun provideChatMemberDao(db: AppDatabase) = db.chatMemberDao()
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

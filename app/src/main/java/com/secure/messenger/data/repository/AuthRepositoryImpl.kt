package com.secure.messenger.data.repository

import android.content.Context
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessaging
import timber.log.Timber
import com.secure.messenger.data.local.dao.UserDao
import com.secure.messenger.data.local.database.AppDatabase
import com.secure.messenger.data.local.entities.UserEntity
import com.secure.messenger.data.remote.api.MessengerApi
import com.secure.messenger.di.AuthTokenProvider
import com.secure.messenger.domain.model.User
import com.secure.messenger.domain.repository.AuthRepository
import com.secure.messenger.service.MessagingService
import com.secure.messenger.utils.LocalKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.HttpException
import javax.inject.Inject

/** Бросается когда сервер вернул 429 — слишком частые запросы OTP. */
class OtpCooldownException(val retryAfterSeconds: Int) :
    Exception("Подождите $retryAfterSeconds секунд перед повторной отправкой")

class AuthRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: MessengerApi,
    private val userDao: UserDao,
    private val db: AppDatabase,
    private val tokenProvider: AuthTokenProvider,
    private val localKeyStore: LocalKeyStore,
) : AuthRepository {

    private val _currentUser = MutableStateFlow<User?>(null)
    override val currentUser: Flow<User?> = _currentUser.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // При запуске приложения (если токен уже есть) подгружаем профиль с сервера
        if (tokenProvider.hasToken()) {
            scope.launch {
                runCatching { api.getMe().data?.toDomain() }
                    .getOrNull()
                    ?.let { user ->
                        userDao.upsert(UserEntity.fromDomain(user))
                        _currentUser.value = user
                    }
            }
        }
    }

    override suspend fun requestOtp(phone: String): Result<Unit> {
        return try {
            api.requestOtp(mapOf("phone" to phone))
            Result.success(Unit)
        } catch (e: HttpException) {
            if (e.code() == 429) {
                val retryAfter = try {
                    val body = e.response()?.errorBody()?.string() ?: ""
                    JSONObject(body).optInt("retry_after", 300)
                } catch (_: Exception) { 300 }
                Result.failure(OtpCooldownException(retryAfter))
            } else {
                Result.failure(e)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun verifyOtp(phone: String, code: String): Result<User> = runCatching {
        val response = api.verifyOtp(mapOf("phone" to phone, "code" to code)).data
            ?: error("Server returned null")

        tokenProvider.saveToken(response.token)

        // Запускаем WebSocket-сервис сразу после получения токена
        context.startService(Intent(context, MessagingService::class.java))

        // Регистрируем FCM device token на сервере — чтобы push-уведомления
        // приходили на это устройство когда приложение в фоне или убито
        FirebaseMessaging.getInstance().token.addOnSuccessListener { fcmToken ->
            scope.launch {
                runCatching { api.registerFcmToken(mapOf("token" to fcmToken)) }
                    .onFailure { Timber.e(it, "Не удалось зарегистрировать FCM токен") }
            }
        }

        val user = response.user.toDomain()
        userDao.upsert(UserEntity.fromDomain(user))
        _currentUser.value = user
        user
    }

    override suspend fun updateProfile(
        displayName: String,
        username: String,
        bio: String?,
    ): Result<User> = runCatching {
        val dto = api.updateProfile(
            buildMap {
                put("displayName", displayName)
                put("username", username)
                if (bio != null) put("bio", bio)
                // Also publish our public key so contacts can encrypt to us
                localKeyStore.getPublicKey()?.let { put("publicKey", it) }
            }
        ).data ?: error("Server returned null")
        val user = dto.toDomain()
        userDao.upsert(UserEntity.fromDomain(user))
        _currentUser.value = user
        user
    }

    override suspend fun uploadAvatar(imageBytes: ByteArray, extension: String): Result<User> = runCatching {
        val mimeType = if (extension.equals("png", ignoreCase = true)) "image/png" else "image/jpeg"
        val requestBody = imageBytes.toRequestBody(mimeType.toMediaType())
        val part = MultipartBody.Part.createFormData("avatar", "avatar.$extension", requestBody)

        val dto = api.uploadAvatar(part).data ?: error("Сервер вернул null")
        val user = dto.toDomain()
        userDao.upsert(UserEntity.fromDomain(user))
        _currentUser.value = user
        user
    }

    override suspend fun logout() {
        // Останавливаем WebSocket-сервис
        context.stopService(Intent(context, MessagingService::class.java))
        // Очищаем авторизацию и ключи — быстрые синхронные операции
        tokenProvider.clearToken()
        localKeyStore.clear()
        _currentUser.value = null
        // Очищаем БД в фоновом потоке — тяжёлая операция,
        // нельзя вызывать на main-потоке (иначе краш/ANR)
        scope.launch { db.clearAllTables() }
    }

    override fun isLoggedIn(): Boolean = tokenProvider.hasToken()
}

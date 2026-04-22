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
import com.secure.messenger.domain.repository.VerifyOtpResult
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

/**
 * Ошибка проверки OTP-кода. errorCode — машинный код от сервера для логики
 * (invalid_code / expired / too_many_attempts), message — готовый русский
 * текст для UI, который отдал сервер.
 */
class OtpVerifyException(
    val errorCode: String,
    message: String,
) : Exception(message)

/** Ошибка при операциях с паролем шифрования. */
class PasswordException(
    val errorCode: String,
    message: String,
) : Exception(message)

/**
 * Парсит JSON-тело ошибки от сервера. Сервер возвращает структуру:
 * {"success": false, "error": "Русский текст", "errorCode": "...optional..."}.
 * Возвращает (errorCode, errorMessage). Если не удалось распарсить — пустые
 * строки, в этом случае вызывающий показывает свой fallback.
 */
private fun parseErrorBody(errorBody: String?): Pair<String, String> {
    if (errorBody.isNullOrBlank()) return "" to ""
    return runCatching {
        val obj = JSONObject(errorBody)
        val code = obj.optString("errorCode", "")
        val msg = obj.optString("error", "")
        code to msg
    }.getOrDefault("" to "")
}

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
            val body = e.response()?.errorBody()?.string()
            val (_, serverMsg) = parseErrorBody(body)
            if (e.code() == 429) {
                val retryAfter = runCatching {
                    JSONObject(body ?: "").optInt("retry_after", 300)
                }.getOrDefault(300)
                Result.failure(OtpCooldownException(retryAfter))
            } else {
                // Если сервер прислал русский текст ошибки — пробрасываем его,
                // иначе ставим понятный fallback вместо «HTTP 500 Server Error».
                val msg = serverMsg.ifBlank { "Не удалось отправить код. Проверьте интернет и повторите" }
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Нет связи с сервером. Проверьте интернет"))
        }
    }

    override suspend fun verifyOtp(phone: String, code: String): Result<VerifyOtpResult> {
        return try {
            val deviceName = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
            val response = api.verifyOtp(mapOf("phone" to phone, "code" to code, "deviceName" to deviceName)).data
                ?: return Result.failure(Exception("Сервер вернул пустой ответ"))

            tokenProvider.saveToken(response.token, response.sessionId)

            // Запускаем WebSocket-сервис сразу после получения токена
            context.startService(Intent(context, MessagingService::class.java))

            // Регистрируем FCM device token на сервере
            FirebaseMessaging.getInstance().token.addOnSuccessListener { fcmToken ->
                scope.launch {
                    runCatching { api.registerFcmToken(mapOf("token" to fcmToken)) }
                        .onFailure { Timber.e(it, "Не удалось зарегистрировать FCM токен") }
                }
            }

            val user = response.user.toDomain()
            userDao.upsert(UserEntity.fromDomain(user))
            _currentUser.value = user
            Result.success(VerifyOtpResult(user = user, hasPassword = response.hasPassword))
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string()
            val (errorCode, serverMsg) = parseErrorBody(body)
            val msg = serverMsg.ifBlank { "Не удалось проверить код. Повторите попытку" }
            Result.failure(OtpVerifyException(errorCode = errorCode, message = msg))
        } catch (e: Exception) {
            Result.failure(Exception("Нет связи с сервером. Проверьте интернет"))
        }
    }

    override suspend fun setPassword(password: String, oldPassword: String?): Result<Unit> {
        return try {
            val body = buildMap {
                put("password", password)
                if (oldPassword != null) put("oldPassword", oldPassword)
            }
            api.setPassword(body)
            Result.success(Unit)
        } catch (e: HttpException) {
            val (errorCode, serverMsg) = parseErrorBody(e.response()?.errorBody()?.string())
            val msg = serverMsg.ifBlank { "Не удалось установить пароль" }
            Result.failure(PasswordException(errorCode = errorCode, message = msg))
        } catch (e: Exception) {
            Result.failure(Exception("Нет связи с сервером"))
        }
    }

    override suspend fun verifyPassword(password: String): Result<Unit> {
        return try {
            api.verifyPassword(mapOf("password" to password))
            Result.success(Unit)
        } catch (e: HttpException) {
            val (errorCode, serverMsg) = parseErrorBody(e.response()?.errorBody()?.string())
            val msg = serverMsg.ifBlank { "Неверный пароль" }
            Result.failure(PasswordException(errorCode = errorCode, message = msg))
        } catch (e: Exception) {
            Result.failure(Exception("Нет связи с сервером"))
        }
    }

    override suspend fun deletePassword(otpCode: String): Result<Unit> {
        return try {
            api.deletePassword(mapOf("code" to otpCode))
            Result.success(Unit)
        } catch (e: HttpException) {
            val (_, serverMsg) = parseErrorBody(e.response()?.errorBody()?.string())
            Result.failure(Exception(serverMsg.ifBlank { "Не удалось удалить ключ" }))
        } catch (e: Exception) {
            Result.failure(Exception("Нет связи с сервером"))
        }
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
        // Очищаем только токен авторизации — ключи шифрования сохраняем,
        // чтобы при повторном входе на этом же устройстве не запрашивать пароль
        // и не терять доступ к расшифрованным сообщениям.
        tokenProvider.clearToken()
        _currentUser.value = null
        // Очищаем БД в фоновом потоке — тяжёлая операция,
        // нельзя вызывать на main-потоке (иначе краш/ANR)
        scope.launch { db.clearAllTables() }
    }

    override fun isLoggedIn(): Boolean = tokenProvider.hasToken()

    override suspend fun getLegacyKeys(): Result<String> = runCatching {
        api.getLegacyKeys().data?.legacyKeys ?: ""
    }

    override suspend fun putLegacyKeys(blob: String): Result<Unit> = runCatching {
        api.setLegacyKeys(mapOf("legacyKeys" to blob))
        Unit
    }
}

package com.secure.messenger.data.repository

import com.secure.messenger.data.local.dao.UserDao
import com.secure.messenger.data.local.entities.UserEntity
import com.secure.messenger.data.remote.api.MessengerApi
import com.secure.messenger.di.AuthTokenProvider
import com.secure.messenger.domain.model.User
import com.secure.messenger.domain.repository.AuthRepository
import com.secure.messenger.utils.LocalKeyStore
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
import javax.inject.Inject

class AuthRepositoryImpl @Inject constructor(
    private val api: MessengerApi,
    private val userDao: UserDao,
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

    override suspend fun requestOtp(phone: String): Result<Unit> = runCatching {
        api.requestOtp(mapOf("phone" to phone))
    }

    override suspend fun verifyOtp(phone: String, code: String): Result<User> = runCatching {
        val response = api.verifyOtp(mapOf("phone" to phone, "code" to code)).data
            ?: error("Server returned null")

        tokenProvider.saveToken(response.token)

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
        tokenProvider.clearToken()
        localKeyStore.clear()
        _currentUser.value = null
    }

    override fun isLoggedIn(): Boolean = tokenProvider.hasToken()
}

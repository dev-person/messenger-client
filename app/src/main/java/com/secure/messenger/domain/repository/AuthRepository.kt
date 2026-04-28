package com.secure.messenger.domain.repository

import com.secure.messenger.domain.model.User
import kotlinx.coroutines.flow.Flow

/** Результат OTP-верификации: пользователь + флаг наличия пароля шифрования. */
data class VerifyOtpResult(val user: User, val hasPassword: Boolean)

interface AuthRepository {
    val currentUser: Flow<User?>

    suspend fun requestOtp(phone: String): Result<Unit>
    suspend fun verifyOtp(phone: String, code: String): Result<VerifyOtpResult>
    suspend fun setPassword(password: String, oldPassword: String? = null): Result<Unit>
    suspend fun verifyPassword(password: String): Result<Unit>
    suspend fun deletePassword(otpCode: String): Result<Unit>
    suspend fun updateProfile(displayName: String, username: String, bio: String?): Result<User>

    /**
     * Публикует НОВЫЙ публичный ключ на сервере, не меняя профиль. Используется
     * во время атомарной смены пароля: нужно опубликовать новый pubKey ДО того
     * как локально перезаписать keypair, чтобы при отказе сервера откатиться.
     * Без [publicKey] метод не имеет смысла — для публикации текущего pubKey
     * есть [updateProfile].
     */
    suspend fun publishPublicKey(publicKey: String): Result<Unit>

    suspend fun uploadAvatar(imageBytes: ByteArray, extension: String): Result<User>
    suspend fun logout()
    fun isLoggedIn(): Boolean

    /**
     * Загружает с сервера зашифрованный blob с legacy-ключами (старые
     * приватные X25519). Сервер для клиента — чёрный ящик, видит только blob,
     * пароль у него отсутствует. Возвращает пустую строку если blob пустой.
     */
    suspend fun getLegacyKeys(): Result<String>

    /** Заливает blob с legacy-ключами на сервер целиком (перезаписывая старый). */
    suspend fun putLegacyKeys(blob: String): Result<Unit>
}

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
    suspend fun uploadAvatar(imageBytes: ByteArray, extension: String): Result<User>
    suspend fun logout()
    fun isLoggedIn(): Boolean
}

package com.secure.messenger.domain.repository

import com.secure.messenger.domain.model.User
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val currentUser: Flow<User?>

    suspend fun requestOtp(phone: String): Result<Unit>
    suspend fun verifyOtp(phone: String, code: String): Result<User>
    suspend fun updateProfile(displayName: String, username: String, bio: String?): Result<User>
    suspend fun uploadAvatar(imageBytes: ByteArray, extension: String): Result<User>
    suspend fun logout()
    fun isLoggedIn(): Boolean
}

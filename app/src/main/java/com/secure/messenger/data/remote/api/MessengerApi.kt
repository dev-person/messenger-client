package com.secure.messenger.data.remote.api

import com.secure.messenger.data.remote.api.dto.ChatDto
import com.secure.messenger.data.remote.api.dto.MessageDto
import com.secure.messenger.data.remote.api.dto.UserDto
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.HTTP
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface MessengerApi {

    // ── Auth ──────────────────────────────────────────────────────────────────

    @POST("auth/otp/request")
    suspend fun requestOtp(@Body body: Map<String, String>): ApiResponse<Unit>

    @POST("auth/otp/verify")
    suspend fun verifyOtp(@Body body: Map<String, String>): ApiResponse<AuthResponseDto>

    @PUT("auth/password")
    suspend fun setPassword(@Body body: Map<String, String>): ApiResponse<Unit>

    @POST("auth/password/verify")
    suspend fun verifyPassword(@Body body: Map<String, String>): ApiResponse<Unit>

    @GET("auth/password/status")
    suspend fun getPasswordStatus(): ApiResponse<PasswordStatusDto>

    @HTTP(method = "DELETE", path = "auth/password", hasBody = true)
    suspend fun deletePassword(@Body body: Map<String, String>): ApiResponse<Unit>

    // Sessions
    @GET("auth/sessions")
    suspend fun getSessions(): ApiResponse<List<SessionDto>>

    @DELETE("auth/sessions/{sessionId}")
    suspend fun terminateSession(@Path("sessionId") sessionId: String): ApiResponse<Unit>

    @DELETE("auth/sessions")
    suspend fun terminateOtherSessions(): ApiResponse<TerminateResult>

    // ── Users ─────────────────────────────────────────────────────────────────

    @GET("users/me")
    suspend fun getMe(): ApiResponse<UserDto>

    @PATCH("users/me")
    suspend fun updateProfile(@Body body: Map<String, String?>): ApiResponse<UserDto>

    @Multipart
    @POST("users/me/avatar")
    suspend fun uploadAvatar(@Part avatar: MultipartBody.Part): ApiResponse<UserDto>

    @PUT("users/me/fcm-token")
    suspend fun registerFcmToken(@Body body: Map<String, String>): ApiResponse<Unit>

    @PUT("users/me/app-version")
    suspend fun registerAppVersion(@Body body: AppVersionRequest): ApiResponse<Unit>

    @GET("users/{userId}")
    suspend fun getUserById(@Path("userId") userId: String): ApiResponse<UserDto>

    @GET("users/search")
    suspend fun searchUsers(@Query("q") query: String): ApiResponse<List<UserDto>>

    @POST("users/lookup-phones")
    suspend fun lookupPhones(@Body body: Map<String, List<String>>): ApiResponse<List<UserDto>>

    @POST("users/lookup-hashes")
    suspend fun lookupByHash(@Body body: Map<String, List<String>>): ApiResponse<List<UserDto>>

    // ── Contacts ──────────────────────────────────────────────────────────────

    @POST("contacts/{userId}")
    suspend fun addContact(@Path("userId") userId: String): ApiResponse<Unit>

    @DELETE("contacts/{userId}")
    suspend fun removeContact(@Path("userId") userId: String): ApiResponse<Unit>

    // ── Chats ─────────────────────────────────────────────────────────────────

    @GET("chats")
    suspend fun getChats(): ApiResponse<List<ChatDto>>

    @POST("chats/direct")
    suspend fun getOrCreateDirectChat(@Body body: Map<String, String>): ApiResponse<ChatDto>

    @POST("chats/group")
    suspend fun createGroupChat(@Body body: Map<String, Any>): ApiResponse<ChatDto>

    // ── Messages ──────────────────────────────────────────────────────────────

    @GET("chats/{chatId}/messages")
    suspend fun getMessages(
        @Path("chatId") chatId: String,
        @Query("before") before: Long? = null,
        @Query("limit") limit: Int = 50,
    ): ApiResponse<List<MessageDto>>

    @POST("chats/{chatId}/messages")
    suspend fun sendMessage(
        @Path("chatId") chatId: String,
        @Body body: SendMessageRequest,
    ): ApiResponse<MessageDto>

    @DELETE("messages/{messageId}")
    suspend fun deleteMessage(@Path("messageId") messageId: String): ApiResponse<Unit>

    @PATCH("messages/{messageId}")
    suspend fun editMessage(
        @Path("messageId") messageId: String,
        @Body body: Map<String, String>,
    ): ApiResponse<MessageDto>

    @POST("chats/{chatId}/read")
    suspend fun markAsRead(@Path("chatId") chatId: String): ApiResponse<Unit>

    // ── Calls ─────────────────────────────────────────────────────────────────

    @GET("calls")
    suspend fun getCallHistory(@Query("chatId") chatId: String): ApiResponse<List<CallDto>>

    // ── App — конфигурация клиента ───────────────────────────────────────────

    @GET("app/ice-servers")
    suspend fun getIceServers(): ApiResponse<List<IceServerDto>>

    @GET("app/update")
    suspend fun getUpdateInfo(): ApiResponse<UpdateInfoDto>

    @GET("app/support")
    suspend fun getSupportInfo(): ApiResponse<SupportInfoDto>
}

data class SendMessageRequest(
    val messageId: String,
    val encryptedContent: String,
    val type: String = "TEXT",
    val replyToId: String? = null,
)

/** Версия APK у клиента — отправляется при каждом старте для админ-панели. */
data class AppVersionRequest(
    val versionCode: Int,
    val versionName: String,
)

data class AuthResponseDto(val token: String, val sessionId: String = "", val user: UserDto, val hasPassword: Boolean = false)
data class SessionDto(
    val id: String,
    val deviceName: String,
    val ip: String,
    val location: String = "",
    val isCurrent: Boolean,
    val createdAt: String,
    val lastSeenAt: String,
)
data class TerminateResult(val terminated: Int)
data class PasswordStatusDto(val hasPassword: Boolean)
data class CallDto(val id: String, val chatId: String, val type: String, val state: String, val startedAt: Long?, val durationSeconds: Int)

// Конфигурация ICE-сервера (STUN/TURN) для WebRTC
data class IceServerDto(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

// Информация об обновлении приложения
data class UpdateInfoDto(
    val versionCode: Int,
    val versionName: String?,
    val downloadUrl: String?,
    val changelog: String?,
)

// Информация о поддержке автора
data class SupportInfoDto(
    val title: String?,
    val message: String?,
    val links: String?,  // JSON-строка с массивом ссылок
)

data class ApiResponse<T>(
    val success: Boolean,
    val data: T?,
    val error: String?,
)

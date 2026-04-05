package com.secure.messenger.data.remote.api.dto

import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.ChatType
import com.secure.messenger.domain.model.Message
import com.secure.messenger.domain.model.MessageStatus
import com.secure.messenger.domain.model.MessageType
import com.secure.messenger.domain.model.User
import java.time.Instant

// Сервер (Go) сериализует time.Time в строку RFC3339/ISO-8601.
// Эта функция преобразует строку в миллисекунды Unix.
private fun String.toEpochMillis(): Long = try {
    Instant.parse(this).toEpochMilli()
} catch (_: Exception) {
    0L
}

data class UserDto(
    val id: String,
    val phone: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String?,
    val isOnline: Boolean,
    val lastSeen: String,   // ISO-8601 от сервера, например "2024-01-15T10:30:00Z"
    val publicKey: String,
) {
    fun toDomain(isContact: Boolean = false) = User(
        id = id,
        phone = phone,
        username = username,
        displayName = displayName,
        avatarUrl = avatarUrl,
        bio = bio,
        isOnline = isOnline,
        lastSeen = lastSeen.toEpochMillis(),
        publicKey = publicKey,
        isContact = isContact,
    )
}

data class MessageDto(
    val id: String,
    val chatId: String,
    val senderId: String,
    val encryptedContent: String,
    val type: String,
    val status: String,
    val timestamp: String,  // ISO-8601 от сервера
    val replyToId: String?,
    val mediaUrl: String?,
    val isEdited: Boolean,
) {
    /** Caller must supply the decrypted content after decryption. */
    fun toDomain(decryptedContent: String) = Message(
        id = id,
        chatId = chatId,
        senderId = senderId,
        content = decryptedContent,
        encryptedContent = encryptedContent,
        type = MessageType.valueOf(type),
        status = MessageStatus.valueOf(status),
        timestamp = timestamp.toEpochMillis(),
        replyToId = replyToId,
        mediaUrl = mediaUrl,
        isEdited = isEdited,
    )
}

data class ChatDto(
    val id: String,
    val type: String,
    val title: String,
    val avatarUrl: String?,
    val unreadCount: Int,
    // Go сериализует nil-слайс как null — делаем nullable чтобы Moshi не крашился
    val members: List<UserDto>?,
    val lastMessage: MessageDto?,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val updatedAt: String,  // ISO-8601 от сервера
)

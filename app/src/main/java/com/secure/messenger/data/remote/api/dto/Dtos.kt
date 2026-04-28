package com.secure.messenger.data.remote.api.dto

import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.ChatRole
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
    // Заполняется сервером только когда User отдаётся как член группы
    // (см. Chat.members) — omitempty, иначе null.
    val role: String? = null,
    // Версия клиентского приложения участника. 0 — неизвестно. Используется
    // клиентом чтобы предупредить об устаревших участниках в группе.
    val appVersionCode: Int = 0,
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
        groupRole = ChatRole.parse(role),
        appVersionCode = appVersionCode,
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
    // Для групповых сообщений — epoch sender-ключа, которым зашифровано.
    // Для DIRECT = null (шифруется старой X25519-pair схемой).
    val groupEpoch: Int? = null,
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
    // Активный epoch sender-ключей группы. Для DIRECT сервер вернёт 0.
    val currentEpoch: Int = 0,
    // Роль текущего пользователя в этом чате. Пустая строка / null для DIRECT.
    val myRole: String? = null,
)

// ── Группы (1.0.68) ──────────────────────────────────────────────────────────

/**
 * POST /chats/group. Тело запроса на создание группы. Типизированный DTO
 * нужен потому что Moshi-конвертер Retrofit не умеет сериализовать
 * `Map<String, Any>` (type variable в параметре тела — Retrofit ругается
 * «Parameter type must not include a type variable or wildcard»).
 */
data class CreateGroupChatDto(
    val title: String,
    val memberIds: List<String>,
    val avatarUrl: String? = null,
)

/** Ответ POST /chats/{chatId}/avatar. Сервер возвращает новый публичный URL. */
data class GroupAvatarUploadDto(val avatarUrl: String)

/** PATCH /chats/{chatId}. Частичное обновление: null-поле не трогается. */
data class UpdateGroupInfoDto(
    val title: String? = null,
    val avatarUrl: String? = null,
)

/** POST /chats/{chatId}/members. */
data class AddMemberDto(val userId: String)

/** PATCH /chats/{chatId}/members/{userId}/role. Разрешены ADMIN / MEMBER. */
data class ChangeRoleDto(val role: String)

/** POST /chats/{chatId}/transfer-ownership. Только CREATOR. */
data class TransferOwnershipDto(val userId: String)

/**
 * Запись из batch'а sender-ключей. При POST — ownerId опущен (сервер
 * берёт из JWT); при GET — заполнен.
 */
data class SenderKeyEntryDto(
    val ownerId: String? = null,
    val recipientId: String,
    val epoch: Int,
    val encryptedKey: String,
)

/** POST /chats/{chatId}/sender-keys. */
data class UploadSenderKeysDto(
    val epoch: Int,
    val entries: List<SenderKeyEntryDto>,
)

/** Ответ на add/remove member — новый epoch, чтобы клиент ротировал ключи. */
data class EpochResponseDto(val epoch: Int)

/**
 * Ответ на POST /leave:
 *  - groupDeleted=true → группа удалена (последний вышел);
 *  - newCreatorId пустой → creator не передавался (уходил ADMIN/MEMBER);
 *  - newCreatorId заполнен → каскадная передача CREATOR.
 */
data class LeaveResponseDto(
    val epoch: Int = 0,
    val newCreatorId: String? = null,
    val groupDeleted: Boolean = false,
)

// ── Групповые звонки (1.0.71+) ────────────────────────────────────────────────

/** POST /chats/{chatId}/calls — старт группового звонка. */
data class StartGroupCallDto(
    val type: String, // AUDIO / VIDEO
    /** Кого приглашать full-screen ringing'ом. null/пусто = вся группа. */
    val inviteUserIds: List<String>? = null,
)

data class GroupCallParticipantDto(
    val userId: String,
    val joinedAt: String,
    val leftAt: String? = null,
)

/**
 * Ответ Start/Join/Leave/GetActive. Сервер возвращает null для GetActive если
 * сейчас нет активного звонка в чате.
 */
data class GroupCallDto(
    val id: String,
    val chatId: String,
    val startedBy: String,
    val type: String,
    val state: String,
    val maxParticipants: Int,
    val startedAt: String,
    val endedAt: String? = null,
    val createdAt: String,
    val participants: List<GroupCallParticipantDto> = emptyList(),
)

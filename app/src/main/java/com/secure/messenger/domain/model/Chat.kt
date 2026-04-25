package com.secure.messenger.domain.model

data class Chat(
    val id: String,
    val type: ChatType,
    val title: String,
    val avatarUrl: String?,
    val lastMessage: Message?,
    val unreadCount: Int,
    val members: List<User>,
    val otherUserId: String? = null,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val updatedAt: Long,
    // Онлайн-статус собеседника (для DIRECT-чатов). Подгружается через JOIN
    // с таблицей users в ChatDao.observeAllWithLastMessage. Для GROUP всегда false.
    val isOtherOnline: Boolean = false,
    // ── Группы (1.0.68) ──────────────────────────────────────────────────
    // Роль текущего пользователя в этом чате; null для DIRECT.
    val myRole: ChatRole? = null,
    // Активный epoch sender-ключей группы. Для DIRECT всегда 0.
    val currentEpoch: Int = 0,
)

enum class ChatType { DIRECT, GROUP }

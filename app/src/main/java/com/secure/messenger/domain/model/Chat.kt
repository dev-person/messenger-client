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
)

enum class ChatType { DIRECT, GROUP }

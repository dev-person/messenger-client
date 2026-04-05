package com.secure.messenger.domain.model

data class Chat(
    val id: String,
    val type: ChatType,
    val title: String,
    val avatarUrl: String?,
    val lastMessage: Message?,
    val unreadCount: Int,
    val members: List<User>,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val updatedAt: Long,
)

enum class ChatType { DIRECT, GROUP }

package com.secure.messenger.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val type: String,       // "DIRECT" | "GROUP"
    val title: String,
    val avatarUrl: String?,
    val unreadCount: Int,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val updatedAt: Long,
    // For direct chats: the other user's ID (to load members separately)
    val otherUserId: String?,
)

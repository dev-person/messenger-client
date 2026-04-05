package com.secure.messenger.domain.model

data class Message(
    val id: String,
    val chatId: String,
    val senderId: String,
    val content: String,           // Decrypted content shown in UI
    val encryptedContent: String,  // Stored encrypted form
    val type: MessageType,
    val status: MessageStatus,
    val timestamp: Long,
    val replyToId: String? = null,
    val mediaUrl: String? = null,
    val isEdited: Boolean = false,
)

enum class MessageType { TEXT, IMAGE, VIDEO, AUDIO, FILE, SYSTEM }

enum class MessageStatus { SENDING, SENT, DELIVERED, READ, FAILED }

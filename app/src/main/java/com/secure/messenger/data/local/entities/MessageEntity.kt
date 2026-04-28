package com.secure.messenger.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.secure.messenger.domain.model.Message
import com.secure.messenger.domain.model.MessageStatus
import com.secure.messenger.domain.model.MessageType

@Entity(
    tableName = "messages",
    // Составной (chatId, timestamp DESC) — критичен для observeAllWithLastMessage
    // и для observeMessages: оба делают «last N сообщений в чате» через
    // ORDER BY timestamp DESC LIMIT N. Без составного индекса SQLite ловит
    // chatId по одному индексу, но потом сортирует in-memory — на 20 чатов
    // × 1000 сообщений это 200мс задержки на каждый emit Flow, что и
    // выглядит как «блокировка скролла» при возврате на список.
    indices = [Index(value = ["chatId", "timestamp"]), Index("timestamp")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val senderId: String,
    val encryptedContent: String,  // Always stored encrypted
    val decryptedContent: String,  // Cached decrypted (in-memory cache — cleared on DB open)
    val type: String,
    val status: String,
    val timestamp: Long,
    val replyToId: String?,
    val mediaUrl: String?,
    val isEdited: Boolean,
) {
    fun toDomain() = Message(
        id = id, chatId = chatId, senderId = senderId,
        content = decryptedContent,
        // encryptedContent в UI не используется (он только для сетевой отправки).
        // Для IMAGE он весит ~800KB base64 — не тянем его через Flow эмиссии,
        // иначе List<Message> держит двойную копию тяжёлых данных.
        encryptedContent = "",
        type = MessageType.valueOf(type),
        status = MessageStatus.valueOf(status),
        timestamp = timestamp,
        replyToId = replyToId,
        mediaUrl = mediaUrl,
        isEdited = isEdited,
    )

    companion object {
        fun fromDomain(msg: Message) = MessageEntity(
            id = msg.id, chatId = msg.chatId, senderId = msg.senderId,
            encryptedContent = msg.encryptedContent,
            decryptedContent = msg.content,
            type = msg.type.name,
            status = msg.status.name,
            timestamp = msg.timestamp,
            replyToId = msg.replyToId,
            mediaUrl = msg.mediaUrl,
            isEdited = msg.isEdited,
        )
    }
}

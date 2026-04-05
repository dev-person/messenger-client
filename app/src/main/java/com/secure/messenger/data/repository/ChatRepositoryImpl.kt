package com.secure.messenger.data.repository

import android.util.Base64
import com.secure.messenger.data.local.dao.ChatDao
import com.secure.messenger.data.local.dao.MessageDao
import com.secure.messenger.data.local.dao.UserDao
import com.secure.messenger.data.local.entities.ChatEntity
import com.secure.messenger.data.local.entities.ChatWithLastMessage
import com.secure.messenger.data.local.entities.MessageEntity
import com.secure.messenger.data.local.entities.UserEntity
import com.secure.messenger.data.remote.api.MessengerApi
import com.secure.messenger.data.remote.api.SendMessageRequest
import com.secure.messenger.data.remote.websocket.SignalingClient
import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.ChatType
import com.secure.messenger.domain.model.Message
import com.secure.messenger.domain.model.MessageStatus
import com.secure.messenger.domain.model.MessageType
import com.secure.messenger.domain.repository.ChatRepository
import com.secure.messenger.utils.CryptoManager
import com.secure.messenger.utils.LocalKeyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class ChatRepositoryImpl @Inject constructor(
    private val api: MessengerApi,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val userDao: UserDao,
    private val cryptoManager: CryptoManager,
    private val localKeyStore: LocalKeyStore,
    private val signalingClient: SignalingClient,
) : ChatRepository {

    override fun observeChats(): Flow<List<Chat>> =
        chatDao.observeAllWithLastMessage().map { rows -> rows.map { it.toChat() } }

    override fun observeMessages(chatId: String): Flow<List<Message>> =
        messageDao.observeMessages(chatId).map { it.map { e -> e.toDomain() } }

    override suspend fun getOrCreateDirectChat(userId: String): Result<Chat> = runCatching {
        val dto = api.getOrCreateDirectChat(mapOf("userId" to userId)).data
            ?: error("Server returned null chat")

        // Сохраняем участников чата в локальный DB — нужно для шифрования сообщений
        dto.members?.forEach { memberDto ->
            val existing = userDao.getById(memberDto.id)
            userDao.upsert(UserEntity(
                id = memberDto.id, phone = memberDto.phone, username = memberDto.username,
                displayName = memberDto.displayName, avatarUrl = memberDto.avatarUrl,
                bio = memberDto.bio, isOnline = memberDto.isOnline,
                lastSeen = Instant.parse(memberDto.lastSeen).toEpochMilli(),
                publicKey = memberDto.publicKey,
                isContact = existing?.isContact ?: false,
            ))
        }

        // Для прямых чатов сервер возвращает пустой title — подставляем имя собеседника
        val title = if (dto.title.isEmpty() && dto.type == "DIRECT") {
            dto.members?.firstOrNull { it.id == userId }?.displayName
                ?: userDao.getById(userId)?.displayName
                ?: ""
        } else {
            dto.title
        }

        val entity = ChatEntity(
            id = dto.id, type = dto.type, title = title, avatarUrl = dto.avatarUrl,
            unreadCount = dto.unreadCount, isPinned = dto.isPinned, isMuted = dto.isMuted,
            updatedAt = Instant.parse(dto.updatedAt).toEpochMilli(), otherUserId = userId,
        )
        chatDao.upsert(entity)
        entity.toChat()
    }

    override suspend fun createGroupChat(title: String, memberIds: List<String>): Result<Chat> = runCatching {
        val dto = api.createGroupChat(mapOf("title" to title, "memberIds" to memberIds)).data
            ?: error("Server returned null chat")
        val entity = ChatEntity(
            id = dto.id, type = dto.type, title = dto.title, avatarUrl = dto.avatarUrl,
            unreadCount = 0, isPinned = false, isMuted = false,
            updatedAt = Instant.parse(dto.updatedAt).toEpochMilli(), otherUserId = null,
        )
        chatDao.upsert(entity)
        entity.toChat()
    }

    override suspend fun sendMessage(chatId: String, content: String): Result<Message> = runCatching {
        val chatEntity = chatDao.getById(chatId) ?: error("Chat not found: $chatId")
        val messageId = UUID.randomUUID().toString()

        // Encrypt the message with the recipient's public key
        val encryptedContent = encryptForChat(chatEntity, content, messageId)

        // Optimistically insert into local DB as SENDING
        val optimisticEntity = MessageEntity(
            id = messageId, chatId = chatId, senderId = "me", // replaced on server response
            encryptedContent = encryptedContent, decryptedContent = content,
            type = MessageType.TEXT.name, status = MessageStatus.SENDING.name,
            timestamp = System.currentTimeMillis(), replyToId = null, mediaUrl = null, isEdited = false,
        )
        messageDao.upsert(optimisticEntity)

        // Send to server
        val request = SendMessageRequest(messageId = messageId, encryptedContent = encryptedContent)
        val dto = api.sendMessage(chatId, request).data ?: error("Send failed")

        // Update with confirmed server entity
        val confirmedEntity = optimisticEntity.copy(
            id = dto.id,
            senderId = dto.senderId,
            status = MessageStatus.SENT.name,
            timestamp = Instant.parse(dto.timestamp).toEpochMilli(),
        )
        messageDao.upsert(confirmedEntity)

        // Also push via WebSocket for real-time delivery to recipient
        signalingClient.sendChatMessage(chatId, encryptedContent, dto.id)

        confirmedEntity.toDomain()
    }

    override suspend fun deleteMessage(messageId: String): Result<Unit> = runCatching {
        api.deleteMessage(messageId)
        messageDao.deleteById(messageId)
    }

    override suspend fun editMessage(messageId: String, newContent: String): Result<Message> = runCatching {
        val existing = messageDao.getById(messageId) ?: error("Message not found")
        val chatEntity = chatDao.getById(existing.chatId) ?: error("Chat not found")
        val encryptedContent = encryptForChat(chatEntity, newContent, messageId)

        api.editMessage(messageId, mapOf("encryptedContent" to encryptedContent))
        val updated = existing.copy(encryptedContent = encryptedContent, decryptedContent = newContent, isEdited = true)
        messageDao.update(updated)
        updated.toDomain()
    }

    override suspend fun fetchMessages(chatId: String): Result<Unit> = runCatching {
        val chat = chatDao.getById(chatId) ?: error("Chat not found: $chatId")
        val dtos = api.getMessages(chatId).data.orEmpty()
        if (dtos.isEmpty()) return@runCatching

        val myPrivateKeyBase64 = localKeyStore.getPrivateKey() ?: error("No local private key")
        val myPrivateKeyBytes = Base64.decode(myPrivateKeyBase64, Base64.NO_WRAP)

        val sharedSecret: ByteArray? = if (chat.type == "DIRECT") {
            val otherUserId = chat.otherUserId ?: error("otherUserId missing for chat $chatId")
            val otherUser = userDao.getById(otherUserId) ?: error("Other user not found: $otherUserId")
            val theirPublicKeyBytes = Base64.decode(otherUser.publicKey, Base64.NO_WRAP)
            cryptoManager.computeSharedSecret(myPrivateKeyBytes, theirPublicKeyBytes)
        } else null

        val entities = dtos.map { dto ->
            val decrypted = if (sharedSecret != null) {
                cryptoManager.decryptMessage(dto.encryptedContent, sharedSecret, dto.id)
                    ?: "[Не удалось расшифровать]"
            } else {
                "[Групповые чаты не поддерживаются]"
            }
            MessageEntity(
                id = dto.id, chatId = chatId, senderId = dto.senderId,
                encryptedContent = dto.encryptedContent, decryptedContent = decrypted,
                type = dto.type, status = dto.status,
                timestamp = Instant.parse(dto.timestamp).toEpochMilli(),
                replyToId = dto.replyToId, mediaUrl = dto.mediaUrl, isEdited = dto.isEdited,
            )
        }
        messageDao.upsertAll(entities)
    }

    override suspend fun syncChats(myUserId: String): Result<Unit> = runCatching {
        val dtos = api.getChats().data.orEmpty()
        for (dto in dtos) {
            // Save members to userDao — needed for encryption and display
            dto.members?.forEach { memberDto ->
                val existing = userDao.getById(memberDto.id)
                userDao.upsert(UserEntity(
                    id = memberDto.id, phone = memberDto.phone, username = memberDto.username,
                    displayName = memberDto.displayName, avatarUrl = memberDto.avatarUrl,
                    bio = memberDto.bio, isOnline = memberDto.isOnline,
                    lastSeen = Instant.parse(memberDto.lastSeen).toEpochMilli(),
                    publicKey = memberDto.publicKey,
                    isContact = existing?.isContact ?: false,
                ))
            }

            val otherUserId = if (dto.type == "DIRECT") {
                dto.members?.firstOrNull { it.id != myUserId }?.id
                    ?: chatDao.getById(dto.id)?.otherUserId
            } else null

            val title = if (dto.type == "DIRECT" && dto.title.isEmpty()) {
                dto.members?.firstOrNull { it.id != myUserId }?.displayName
                    ?: chatDao.getById(dto.id)?.title
                    ?: ""
            } else {
                dto.title
            }

            chatDao.upsert(ChatEntity(
                id = dto.id, type = dto.type, title = title, avatarUrl = dto.avatarUrl,
                unreadCount = dto.unreadCount, isPinned = dto.isPinned, isMuted = dto.isMuted,
                updatedAt = Instant.parse(dto.updatedAt).toEpochMilli(),
                otherUserId = otherUserId,
            ))
        }
    }

    override suspend fun markAsRead(chatId: String): Result<Unit> = runCatching {
        chatDao.clearUnread(chatId)
        api.markAsRead(chatId)
    }

    override suspend fun pinChat(chatId: String): Result<Unit> = runCatching {
        val chat = chatDao.getById(chatId) ?: return@runCatching
        chatDao.setPinned(chatId, !chat.isPinned)
    }

    override suspend fun muteChat(chatId: String, mutedUntil: Long?): Result<Unit> = runCatching {
        chatDao.setMuted(chatId, mutedUntil != null)
    }

    // ── Encryption helper ─────────────────────────────────────────────────────

    private suspend fun encryptForChat(chat: ChatEntity, content: String, messageId: String): String {
        val myPrivateKeyBase64 = localKeyStore.getPrivateKey() ?: error("No local private key")
        val myPrivateKeyBytes = Base64.decode(myPrivateKeyBase64, Base64.NO_WRAP)

        val otherUserId = chat.otherUserId ?: error("Group chat encryption not supported yet")
        val otherUser = userDao.getById(otherUserId) ?: error("Recipient not found: $otherUserId")
        val theirPublicKeyBytes = Base64.decode(otherUser.publicKey, Base64.NO_WRAP)

        val sharedSecret = cryptoManager.computeSharedSecret(myPrivateKeyBytes, theirPublicKeyBytes)
        return cryptoManager.encryptMessage(content, sharedSecret, messageId)
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private fun ChatWithLastMessage.toChat() = chat.toChat(
        lastMessage = lastMsgId?.let {
            Message(
                id = it,
                chatId = chat.id,
                senderId = lastMsgSenderId ?: "",
                content = lastMsgContent ?: "",
                encryptedContent = "",
                type = runCatching { MessageType.valueOf(lastMsgType ?: "") }.getOrDefault(MessageType.TEXT),
                status = runCatching { MessageStatus.valueOf(lastMsgStatus ?: "") }.getOrDefault(MessageStatus.SENT),
                timestamp = lastMsgTimestamp ?: 0L,
                isEdited = lastMsgIsEdited ?: false,
            )
        }
    )

    private fun ChatEntity.toChat(lastMessage: Message? = null) = Chat(
        id = id,
        type = ChatType.valueOf(type),
        title = title,
        avatarUrl = avatarUrl,
        lastMessage = lastMessage,
        unreadCount = unreadCount,
        members = emptyList(),
        isPinned = isPinned,
        isMuted = isMuted,
        updatedAt = updatedAt,
    )
}

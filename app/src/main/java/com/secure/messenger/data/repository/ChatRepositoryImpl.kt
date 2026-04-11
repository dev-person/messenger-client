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
import com.secure.messenger.domain.repository.AuthRepository
import com.secure.messenger.domain.repository.ChatRepository
import com.secure.messenger.utils.CryptoManager
import com.secure.messenger.utils.LocalKeyStore
import dagger.Lazy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
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
    // Lazy чтобы избежать циклической зависимости (AuthRepositoryImpl может зависеть от ChatRepository)
    private val authRepository: Lazy<AuthRepository>,
) : ChatRepository {

    /**
     * Возвращает реальный UUID текущего пользователя для оптимистичной вставки сообщений.
     * Без него senderId был "me", и MessageBubble сначала рисовал сообщение слева
     * (как входящее), а после ответа сервера резко перепрыгивал направо.
     * Fallback на "me" — если не удалось получить (новые пользователи без профиля).
     */
    private suspend fun currentUserIdSync(): String =
        runCatching { authRepository.get().currentUser.first()?.id }.getOrNull() ?: "me"

    override fun observeChats(): Flow<List<Chat>> =
        chatDao.observeAllWithLastMessage().map { rows -> rows.map { it.toChat() } }

    override fun observeMessages(chatId: String, limit: Int): Flow<List<Message>> =
        messageDao.observeMessages(chatId, limit).map { it.map { e -> e.toDomain() } }

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
        val otherMember = dto.members?.firstOrNull { it.id == userId }
        val title = if (dto.title.isEmpty() && dto.type == "DIRECT") {
            otherMember?.displayName
                ?: userDao.getById(userId)?.displayName
                ?: ""
        } else {
            dto.title
        }
        val avatarUrl = dto.avatarUrl ?: otherMember?.avatarUrl

        val entity = ChatEntity(
            id = dto.id, type = dto.type, title = title, avatarUrl = avatarUrl,
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

    override suspend fun sendMessage(
        chatId: String,
        content: String,
        replyToId: String?,
    ): Result<Message> = runCatching {
        val chatEntity = chatDao.getById(chatId) ?: error("Chat not found: $chatId")
        val messageId = UUID.randomUUID().toString()
        val myUserId = currentUserIdSync()

        // Шифруем сообщение публичным ключом получателя
        val encryptedContent = encryptForChat(chatEntity, content, messageId)

        // Оптимистичная вставка в локальную БД со статусом SENDING.
        // senderId сразу ставим реальным — иначе MessageBubble нарисует сообщение
        // как входящее (слева) до ответа сервера, а потом перепрыгнет вправо.
        val optimisticEntity = MessageEntity(
            id = messageId, chatId = chatId, senderId = myUserId,
            encryptedContent = encryptedContent, decryptedContent = content,
            type = MessageType.TEXT.name, status = MessageStatus.SENDING.name,
            timestamp = System.currentTimeMillis(),
            replyToId = replyToId, mediaUrl = null, isEdited = false,
        )
        messageDao.upsert(optimisticEntity)

        // Отправляем на сервер
        val request = SendMessageRequest(
            messageId = messageId,
            encryptedContent = encryptedContent,
            replyToId = replyToId,
        )
        val dto = api.sendMessage(chatId, request).data ?: error("Send failed")

        // Обновляем подтверждённым ответом сервера
        val confirmedEntity = optimisticEntity.copy(
            id = dto.id,
            senderId = dto.senderId,
            status = MessageStatus.SENT.name,
            timestamp = Instant.parse(dto.timestamp).toEpochMilli(),
        )
        messageDao.upsert(confirmedEntity)

        // Дополнительно отправляем через WebSocket для доставки в реальном времени
        signalingClient.sendChatMessage(chatId, encryptedContent, dto.id)

        confirmedEntity.toDomain()
    }

    /**
     * Отправляет голосовое сообщение. Аудио-байты + waveform упаковываются в JSON
     * и шифруются обычным текстовым пайплайном — сервер ничего не знает о голосовых
     * сообщениях, для него это просто encryptedContent с типом AUDIO.
     * Файлы на сервере не хранятся.
     */
    override suspend fun sendVoiceMessage(
        chatId: String,
        audioBytes: ByteArray,
        durationSeconds: Int,
        waveform: IntArray,
    ): Result<Message> = runCatching {
        val chatEntity = chatDao.getById(chatId) ?: error("Chat not found: $chatId")
        val messageId = UUID.randomUUID().toString()
        val myUserId = currentUserIdSync()

        val payload = com.secure.messenger.utils.VoiceCodec.encode(audioBytes, durationSeconds, waveform)
        val encryptedContent = encryptForChat(chatEntity, payload, messageId)

        val optimisticEntity = MessageEntity(
            id = messageId, chatId = chatId, senderId = myUserId,
            encryptedContent = encryptedContent,
            decryptedContent = payload, // локально храним JSON в decryptedContent
            type = MessageType.AUDIO.name,
            status = MessageStatus.SENDING.name,
            timestamp = System.currentTimeMillis(),
            replyToId = null, mediaUrl = null, isEdited = false,
        )
        messageDao.upsert(optimisticEntity)

        val request = SendMessageRequest(
            messageId = messageId,
            encryptedContent = encryptedContent,
            type = MessageType.AUDIO.name,
        )
        val dto = api.sendMessage(chatId, request).data ?: error("Send failed")

        val confirmedEntity = optimisticEntity.copy(
            id = dto.id,
            senderId = dto.senderId,
            status = MessageStatus.SENT.name,
            timestamp = Instant.parse(dto.timestamp).toEpochMilli(),
        )
        messageDao.upsert(confirmedEntity)

        // Доставка в реальном времени через WebSocket
        signalingClient.sendChatMessage(chatId, encryptedContent, dto.id)

        confirmedEntity.toDomain()
    }

    /**
     * Отправляет картинку. Картинка уже сжата (см. ImageCodec.loadAndCompress),
     * упаковывается в JSON и идёт через тот же шифрованный пайп что и текст.
     */
    override suspend fun sendImageMessage(
        chatId: String,
        imageData: com.secure.messenger.utils.ImageCodec.ImageData,
    ): Result<Message> = runCatching {
        val chatEntity = chatDao.getById(chatId) ?: error("Chat not found: $chatId")
        val messageId = UUID.randomUUID().toString()
        val myUserId = currentUserIdSync()

        val payload = com.secure.messenger.utils.ImageCodec.encode(imageData)
        val encryptedContent = encryptForChat(chatEntity, payload, messageId)

        val optimisticEntity = MessageEntity(
            id = messageId, chatId = chatId, senderId = myUserId,
            encryptedContent = encryptedContent,
            decryptedContent = payload, // локально храним JSON
            type = MessageType.IMAGE.name,
            status = MessageStatus.SENDING.name,
            timestamp = System.currentTimeMillis(),
            replyToId = null, mediaUrl = null, isEdited = false,
        )
        messageDao.upsert(optimisticEntity)

        val request = SendMessageRequest(
            messageId = messageId,
            encryptedContent = encryptedContent,
            type = MessageType.IMAGE.name,
        )
        val dto = api.sendMessage(chatId, request).data ?: error("Send failed")

        val confirmedEntity = optimisticEntity.copy(
            id = dto.id,
            senderId = dto.senderId,
            status = MessageStatus.SENT.name,
            timestamp = Instant.parse(dto.timestamp).toEpochMilli(),
        )
        messageDao.upsert(confirmedEntity)

        signalingClient.sendChatMessage(chatId, encryptedContent, dto.id)

        confirmedEntity.toDomain()
    }

    override suspend fun deleteMessage(messageId: String): Result<Unit> = runCatching {
        // Удаляем на сервере; 404 = уже удалено — всё равно чистим локально
        val response = runCatching { api.deleteMessage(messageId) }
        messageDao.deleteById(messageId)
        // Пробрасываем ошибку только если это не 404
        response.onFailure { e ->
            val is404 = e is retrofit2.HttpException && e.code() == 404
            if (!is404) throw e
        }
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
        Timber.d("fetchMessages: НАЧАЛО для chatId=$chatId")
        val chat = chatDao.getById(chatId) ?: error("Chat not found: $chatId")
        val response = api.getMessages(chatId)
        Timber.d("fetchMessages: API ответ: success=${response.success}, data.size=${response.data?.size}, error=${response.error}")
        val dtos = response.data.orEmpty()

        // ── Синхронизация удалений: удаляем локальные сообщения, которых нет на сервере ──
        // Делаем ЭТО ПЕРВЫМ — до расшифровки, которая может упасть.
        val serverIds = dtos.map { it.id }.toSet()
        Timber.d("fetchMessages: chatId=$chatId, сервер вернул ${serverIds.size} сообщений")

        if (dtos.isEmpty()) {
            // Сервер вернул пустой список — удаляем все локальные сообщения этого чата
            val allLocal = messageDao.getAllMessageIds(chatId)
            Timber.d("fetchMessages: сервер пуст, удаляем ${allLocal.size} локальных сообщений")
            for (id in allLocal) {
                messageDao.deleteById(id)
            }
            return@runCatching
        }

        // Удаляем только среди сообщений с timestamp >= самого старого из серверного ответа,
        // чтобы не затронуть более старые сообщения вне текущей страницы.
        val oldestServerTimestamp = dtos.minOf { Instant.parse(it.timestamp).toEpochMilli() }
        val localIds = messageDao.getMessageIdsSince(chatId, oldestServerTimestamp)
        val toDelete = localIds.filter { it !in serverIds }
        Timber.d("fetchMessages: localIds=${localIds.size}, toDelete=${toDelete.size}, oldestTs=$oldestServerTimestamp")
        for (id in toDelete) {
            Timber.d("fetchMessages: удаляем локальное сообщение $id (нет на сервере)")
            messageDao.deleteById(id)
        }

        // ── Расшифровка и upsert ────────────────────────────────────────────────
        val myPrivateKeyBase64 = localKeyStore.getPrivateKey() ?: error("No local private key")
        val myPrivateKeyBytes = Base64.decode(myPrivateKeyBase64, Base64.NO_WRAP)

        val sharedSecret: ByteArray? = if (chat.type == "DIRECT") {
            val otherUserId = chat.otherUserId ?: error("otherUserId missing for chat $chatId")
            val otherUser = run {
                val fresh = runCatching { api.getUserById(otherUserId).data }.getOrNull()
                if (fresh != null) {
                    val entity = userDao.getById(otherUserId)
                    if (entity == null || entity.publicKey != fresh.publicKey) {
                        userDao.upsert(UserEntity(
                            id = fresh.id, phone = fresh.phone, username = fresh.username,
                            displayName = fresh.displayName, avatarUrl = fresh.avatarUrl,
                            bio = fresh.bio, isOnline = fresh.isOnline,
                            lastSeen = java.time.Instant.parse(fresh.lastSeen).toEpochMilli(),
                            publicKey = fresh.publicKey,
                            isContact = entity?.isContact ?: false,
                        ))
                    }
                    userDao.getById(otherUserId) ?: error("Other user not found: $otherUserId")
                } else {
                    userDao.getById(otherUserId) ?: error("Other user not found: $otherUserId")
                }
            }
            val theirPublicKeyBytes = Base64.decode(otherUser.publicKey, Base64.NO_WRAP)
            cryptoManager.computeSharedSecret(myPrivateKeyBytes, theirPublicKeyBytes)
        } else null

        val entities = dtos.map { dto ->
            val decrypted = if (dto.type == "SYSTEM") {
                dto.encryptedContent // системные сообщения — plain text
            } else if (sharedSecret != null) {
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
        upsertWithoutStatusDowngrade(entities)
    }

    override suspend fun fetchOlderMessages(chatId: String, beforeTimestamp: Long): Result<Boolean> = runCatching {
        val chat = chatDao.getById(chatId) ?: error("Chat not found: $chatId")
        val dtos = api.getMessages(chatId, before = beforeTimestamp, limit = 30).data.orEmpty()
        if (dtos.isEmpty()) return@runCatching false // нет больше сообщений

        val myPrivateKeyBase64 = localKeyStore.getPrivateKey() ?: error("No local private key")
        val myPrivateKeyBytes = Base64.decode(myPrivateKeyBase64, Base64.NO_WRAP)

        val sharedSecret: ByteArray? = if (chat.type == "DIRECT") {
            val otherUserId = chat.otherUserId ?: error("otherUserId missing")
            val otherUser = userDao.getById(otherUserId) ?: error("Other user not found")
            val theirPublicKeyBytes = Base64.decode(otherUser.publicKey, Base64.NO_WRAP)
            cryptoManager.computeSharedSecret(myPrivateKeyBytes, theirPublicKeyBytes)
        } else null

        val entities = dtos.map { dto ->
            val decrypted = if (dto.type == "SYSTEM") {
                dto.encryptedContent
            } else if (sharedSecret != null) {
                cryptoManager.decryptMessage(dto.encryptedContent, sharedSecret, dto.id)
                    ?: "[Не удалось расшифровать]"
            } else "[Групповые чаты не поддерживаются]"
            MessageEntity(
                id = dto.id, chatId = chatId, senderId = dto.senderId,
                encryptedContent = dto.encryptedContent, decryptedContent = decrypted,
                type = dto.type, status = dto.status,
                timestamp = Instant.parse(dto.timestamp).toEpochMilli(),
                replyToId = dto.replyToId, mediaUrl = dto.mediaUrl, isEdited = dto.isEdited,
            )
        }
        upsertWithoutStatusDowngrade(entities)
        true // есть ещё сообщения
    }

    override suspend fun syncChats(myUserId: String): Result<Unit> = runCatching {
        val dtos = api.getChats().data.orEmpty()
        for (dto in dtos) {
            // Сохраняем участников в локальную БД — нужно для шифрования и отображения
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

            val otherMember = if (dto.type == "DIRECT") {
                dto.members?.firstOrNull { it.id != myUserId }
            } else null

            val title = if (dto.type == "DIRECT" && dto.title.isEmpty()) {
                otherMember?.displayName
                    ?: chatDao.getById(dto.id)?.title
                    ?: ""
            } else {
                dto.title
            }

            // Для прямых чатов: если у чата нет аватарки — берём аватар собеседника
            val avatarUrl = dto.avatarUrl ?: otherMember?.avatarUrl

            chatDao.upsert(ChatEntity(
                id = dto.id, type = dto.type, title = title, avatarUrl = avatarUrl,
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

    override suspend fun deleteChat(chatId: String): Result<Unit> = runCatching {
        chatDao.deleteById(chatId)
    }

    // ── Защита от понижения статуса при синхронизации ────────────────────────

    /**
     * Вставляет сообщения, не понижая статус.
     * Порядок: SENDING(0) < SENT(1) < DELIVERED(2) < READ(3).
     */
    private suspend fun upsertWithoutStatusDowngrade(messages: List<MessageEntity>) {
        for (msg in messages) {
            val existing = messageDao.getById(msg.id)
            if (existing != null) {
                val localPriority = statusPriority(existing.status)
                val remotePriority = statusPriority(msg.status)
                val finalStatus = if (localPriority > remotePriority) existing.status else msg.status
                messageDao.upsert(msg.copy(status = finalStatus))
            } else {
                messageDao.upsert(msg)
            }
        }
    }

    private fun statusPriority(status: String): Int = when (status) {
        "SENDING"   -> 0
        "SENT"      -> 1
        "DELIVERED" -> 2
        "READ"      -> 3
        else        -> 0
    }

    // ── Шифрование ─────────────────────────────────────────────────────────

    private suspend fun encryptForChat(chat: ChatEntity, content: String, messageId: String): String {
        val myPrivateKeyBase64 = localKeyStore.getPrivateKey() ?: error("No local private key")
        val myPrivateKeyBytes = Base64.decode(myPrivateKeyBase64, Base64.NO_WRAP)

        val otherUserId = chat.otherUserId ?: error("Group chat encryption not supported yet")
        val otherUser = userDao.getById(otherUserId) ?: error("Recipient not found: $otherUserId")
        val theirPublicKeyBytes = Base64.decode(otherUser.publicKey, Base64.NO_WRAP)

        val sharedSecret = cryptoManager.computeSharedSecret(myPrivateKeyBytes, theirPublicKeyBytes)
        return cryptoManager.encryptMessage(content, sharedSecret, messageId)
    }

    // ── Маппинг сущностей ──────────────────────────────────────────────────

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
        otherUserId = otherUserId,
        isPinned = isPinned,
        isMuted = isMuted,
        updatedAt = updatedAt,
    )
}

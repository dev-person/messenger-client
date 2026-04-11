package com.secure.messenger.service

import android.util.Base64
import com.secure.messenger.data.local.dao.ChatDao
import com.secure.messenger.data.local.dao.MessageDao
import com.secure.messenger.data.local.dao.UserDao
import com.secure.messenger.data.local.entities.ChatEntity
import com.secure.messenger.data.local.entities.MessageEntity
import com.secure.messenger.data.local.entities.UserEntity
import com.secure.messenger.data.remote.api.MessengerApi
import com.secure.messenger.data.remote.websocket.SignalingMessage
import com.secure.messenger.domain.model.MessageStatus
import com.secure.messenger.domain.model.MessageType
import com.secure.messenger.utils.CryptoManager
import com.secure.messenger.utils.LocalKeyStore
import com.squareup.moshi.Moshi
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Обрабатывает входящие чат-сообщения, приходящие в реальном времени через WebSocket.
 *
 * Ответственность:
 *  1. Разобрать JSON-конверт WebSocket.
 *  2. Убедиться что отправитель есть в локальной БД (загрузить с сервера если нет).
 *  3. Убедиться что чат есть в локальной БД (создать минимальную запись если нет).
 *  4. Расшифровать сообщение через X25519 ECDH + AES-256-GCM.
 *  5. Сохранить расшифрованное сообщение в Room.
 */
@Singleton
class IncomingMessageHandler @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val userDao: UserDao,
    private val api: MessengerApi,
    private val cryptoManager: CryptoManager,
    private val localKeyStore: LocalKeyStore,
    private val moshi: Moshi,
) {
    private val adapter = moshi.adapter(SignalingMessage::class.java)

    /**
     * Разбирает, расшифровывает и сохраняет входящее WebSocket-сообщение.
     * Возвращает Pair(имя_отправителя, расшифрованный_текст) для уведомления, или null при ошибке.
     */
    suspend fun handle(json: String): Pair<String, String>? {
        try {
            val msg = adapter.fromJson(json) ?: return null
            val payload = msg.payload ?: return null

            val chatId = payload["chatId"] as? String ?: return null
            // REST использует "id"; клиентский WS использует "messageId"
            val messageId = (payload["id"] ?: payload["messageId"]) as? String ?: return null
            val encryptedContent = payload["encryptedContent"] as? String ?: return null
            val senderId = payload["senderId"] as? String ?: return null
            val timestampStr = payload["timestamp"] as? String

            val msgType = (payload["type"] as? String) ?: "TEXT"

            // Пропускаем дубликаты (WS + REST могут доставить одно и то же сообщение)
            if (messageDao.getById(messageId) != null) return null

            // Системные сообщения (звонки и т.д.) — сохраняем как есть, без расшифровки.
            // senderId на сервере хранится как реальный UUID (например, звонящего),
            // потому что messages.sender_id NOT NULL с FK на users. На клиенте
            // системные распознаются по type=SYSTEM, конкретный senderId не важен.
            if (msgType == "SYSTEM") {
                val timestamp = (payload["timestamp"] as? String)?.let {
                    try { Instant.parse(it).toEpochMilli() } catch (_: Exception) { System.currentTimeMillis() }
                } ?: System.currentTimeMillis()
                if (chatDao.getById(chatId) != null) {
                    chatDao.incrementUnread(chatId, timestamp)
                }
                messageDao.upsert(MessageEntity(
                    id = messageId, chatId = chatId, senderId = senderId,
                    encryptedContent = encryptedContent, decryptedContent = encryptedContent,
                    type = MessageType.SYSTEM.name, status = MessageStatus.DELIVERED.name,
                    timestamp = timestamp,
                    replyToId = null, mediaUrl = null, isEdited = false,
                ))
                return null // системные сообщения не показываем в notification
            }

            // Убеждаемся, что отправитель есть в локальной БД (нужен публичный ключ для расшифровки)
            val sender = userDao.getById(senderId) ?: run {
                val dto = api.getUserById(senderId).data ?: run {
                    Timber.w("IncomingMessageHandler: sender $senderId not found on server")
                    return null
                }
                UserEntity(
                    id = dto.id, phone = dto.phone, username = dto.username,
                    displayName = dto.displayName, avatarUrl = dto.avatarUrl,
                    bio = dto.bio, isOnline = dto.isOnline,
                    lastSeen = Instant.parse(dto.lastSeen).toEpochMilli(),
                    publicKey = dto.publicKey, isContact = false,
                ).also { userDao.upsert(it) }
            }

            // Убеждаемся, что чат существует в локальной БД для отображения в UI
            if (chatDao.getById(chatId) == null) {
                chatDao.upsert(ChatEntity(
                    id = chatId, type = "DIRECT",
                    title = sender.displayName,
                    avatarUrl = sender.avatarUrl,
                    unreadCount = 1,
                    isPinned = false, isMuted = false,
                    updatedAt = System.currentTimeMillis(),
                    otherUserId = senderId,
                ))
            } else {
                // Инкрементируем счётчик непрочитанных и обновляем timestamp
                chatDao.incrementUnread(chatId, System.currentTimeMillis())
            }

            // Расшифровываем
            val myPrivateKeyBase64 = localKeyStore.getPrivateKey() ?: run {
                Timber.w("IncomingMessageHandler: no local private key")
                return null
            }
            val myPrivateKeyBytes = Base64.decode(myPrivateKeyBase64, Base64.NO_WRAP)
            var senderPublicKeyBytes = Base64.decode(sender.publicKey, Base64.NO_WRAP)
            var sharedSecret = cryptoManager.computeSharedSecret(myPrivateKeyBytes, senderPublicKeyBytes)
            var decryptedContent = cryptoManager.decryptMessage(encryptedContent, sharedSecret, messageId)

            if (decryptedContent == null) {
                // Расшифровка не удалась — отправитель мог обновить ключевую пару (перелогин).
                // Запрашиваем актуальный публичный ключ с сервера и пробуем ещё раз.
                val freshDto = runCatching { api.getUserById(senderId).data }.getOrNull()
                if (freshDto != null && freshDto.publicKey != sender.publicKey) {
                    Timber.d("IncomingMessageHandler: retrying decrypt with fresh public key for $senderId")
                    val freshPubBytes = Base64.decode(freshDto.publicKey, Base64.NO_WRAP)
                    val freshSecret = cryptoManager.computeSharedSecret(myPrivateKeyBytes, freshPubBytes)
                    decryptedContent = cryptoManager.decryptMessage(encryptedContent, freshSecret, messageId)
                    if (decryptedContent != null) {
                        // Сохраняем обновлённый ключ чтобы следующие сообщения расшифровывались сразу
                        userDao.upsert(sender.copy(publicKey = freshDto.publicKey))
                    }
                }
                if (decryptedContent == null) decryptedContent = "[Не удалось расшифровать]"
            }

            val timestamp = if (timestampStr != null) {
                try { Instant.parse(timestampStr).toEpochMilli() } catch (_: Exception) { System.currentTimeMillis() }
            } else System.currentTimeMillis()

            // Сохраняем в БД
            val safeType = runCatching { MessageType.valueOf(msgType) }.getOrDefault(MessageType.TEXT).name
            // Извлекаем replyToId из payload (для цитат)
            val replyToId = payload["replyToId"] as? String
            messageDao.upsert(MessageEntity(
                id = messageId, chatId = chatId, senderId = senderId,
                encryptedContent = encryptedContent, decryptedContent = decryptedContent,
                type = safeType, status = MessageStatus.DELIVERED.name,
                timestamp = timestamp,
                replyToId = replyToId, mediaUrl = null, isEdited = false,
            ))

            // В уведомлении не показываем base64 — для медиа ставим читаемую плашку
            val notificationText = when (safeType) {
                MessageType.AUDIO.name -> "Голосовое сообщение"
                MessageType.IMAGE.name -> "Фото"
                else -> decryptedContent.take(120)
            }
            return Pair(sender.displayName, notificationText)

        } catch (e: Exception) {
            Timber.e(e, "IncomingMessageHandler: failed to process message")
            return null
        }
    }

    /** Удаляет сообщение, удалённое другим пользователем. */
    suspend fun handleDelete(messageId: String) {
        messageDao.deleteById(messageId)
    }

    /** Расшифровывает и применяет редактирование от другого пользователя. */
    suspend fun handleEdit(messageId: String, chatId: String, encryptedContent: String) {
        val chat = chatDao.getById(chatId) ?: return
        val otherUserId = chat.otherUserId ?: return
        val otherUser = userDao.getById(otherUserId) ?: return

        val myPrivateKeyBase64 = localKeyStore.getPrivateKey() ?: return
        val myPrivateKeyBytes = Base64.decode(myPrivateKeyBase64, Base64.NO_WRAP)
        val theirPubKeyBytes = Base64.decode(otherUser.publicKey, Base64.NO_WRAP)
        val sharedSecret = cryptoManager.computeSharedSecret(myPrivateKeyBytes, theirPubKeyBytes)
        val decrypted = cryptoManager.decryptMessage(encryptedContent, sharedSecret, messageId)
            ?: "[Не удалось расшифровать]"

        messageDao.updateContent(messageId, encryptedContent, decrypted)
    }

    /** Обновляет онлайн-статус пользователя в локальной БД. */
    suspend fun handleUserStatus(userId: String, isOnline: Boolean) {
        userDao.updateOnlineStatus(userId, isOnline, System.currentTimeMillis())
    }

    /** Помечает все сообщения от других пользователей в чате как READ, когда собеседник открывает чат. */
    suspend fun handleMessagesRead(chatId: String, readerId: String) {
        messageDao.markChatMessagesRead(chatId, readerId)
    }

    /**
     * Преобразует ID пользователя в отображаемое имя.
     * Сначала проверяет локальную БД; если нет — загружает с сервера.
     * При неудаче возвращает сырой userId.
     */
    suspend fun getDisplayName(userId: String): String {
        userDao.getById(userId)?.let { user ->
            return user.displayName.ifEmpty { user.username }.ifEmpty { userId }
        }
        return runCatching { api.getUserById(userId).data }
            .getOrNull()
            ?.also { dto ->
                userDao.upsert(UserEntity(
                    id = dto.id, phone = dto.phone, username = dto.username,
                    displayName = dto.displayName, avatarUrl = dto.avatarUrl,
                    bio = dto.bio, isOnline = dto.isOnline,
                    lastSeen = java.time.Instant.parse(dto.lastSeen).toEpochMilli(),
                    publicKey = dto.publicKey, isContact = false,
                ))
            }
            ?.let { dto -> dto.displayName.ifEmpty { dto.username }.ifEmpty { userId } }
            ?: userId
    }
}

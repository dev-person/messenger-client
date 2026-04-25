package com.secure.messenger.service

import android.util.Base64
import com.secure.messenger.data.local.dao.ChatDao
import com.secure.messenger.data.local.dao.GroupSenderKeyDao
import com.secure.messenger.data.local.dao.MessageDao
import com.secure.messenger.data.local.dao.UserDao
import com.secure.messenger.data.local.entities.ChatEntity
import com.secure.messenger.data.local.entities.MessageEntity
import com.secure.messenger.data.local.entities.UserEntity
import com.secure.messenger.data.remote.api.MessengerApi
import com.secure.messenger.data.remote.websocket.SignalingMessage
import com.secure.messenger.domain.model.MessageStatus
import com.secure.messenger.domain.model.MessageType
import com.secure.messenger.domain.repository.AuthRepository
import com.secure.messenger.domain.repository.ChatRepository
import com.secure.messenger.utils.CryptoManager
import com.secure.messenger.utils.GroupCryptoManager
import com.secure.messenger.utils.LocalKeyStore
import com.squareup.moshi.Moshi
import dagger.Lazy
import kotlinx.coroutines.flow.first
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
    private val groupSenderKeyDao: GroupSenderKeyDao,
    private val api: MessengerApi,
    private val cryptoManager: CryptoManager,
    private val groupCryptoManager: GroupCryptoManager,
    private val localKeyStore: LocalKeyStore,
    private val moshi: Moshi,
    // Lazy чтобы избежать циклической зависимости (AuthRepository не должна
    // тащить за собой весь handler в свой граф инициализации).
    private val authRepository: Lazy<AuthRepository>,
    // Lazy — ChatRepository -> SignalingClient -> MessagingService -> IncomingMessageHandler
    // нужен чтобы тянуть свежие sender-ключи при ротации.
    private val chatRepository: Lazy<ChatRepository>,
) {
    private val adapter = moshi.adapter(SignalingMessage::class.java)

    /** Возвращает UUID текущего пользователя (или null если не авторизован). */
    private suspend fun currentUserIdSync(): String? =
        runCatching { authRepository.get().currentUser.first()?.id }.getOrNull()

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
            // senderId на сервере хранится как UUID звонящего: для случая «пропущенный
            // звонок» это caller. Если caller — это я, то такое сообщение пришло
            // ко мне как звонящему (я же сам звонил, не пропускал). UI ChatScreen
            // переписывает текст («Звонок без ответа»), а здесь — НЕ инкрементим
            // unread-счётчик: иначе у звонящего висел бы +1 непрочитанный за свой
            // же исходящий неудачный вызов.
            if (msgType == "SYSTEM") {
                val timestamp = (payload["timestamp"] as? String)?.let {
                    try { Instant.parse(it).toEpochMilli() } catch (_: Exception) { System.currentTimeMillis() }
                } ?: System.currentTimeMillis()
                val myId = currentUserIdSync()
                if (chatDao.getById(chatId) != null && senderId != myId) {
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
            val existingChat = chatDao.getById(chatId)
            if (existingChat == null) {
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
                // Если чат был скрыт пользователем — разспрятываем при новом сообщении.
                // Это даёт «второй шанс»: если отправитель снова пишет — чат снова виден.
                if (existingChat.isHidden) {
                    chatDao.unhideById(chatId)
                }
                // Инкрементируем счётчик непрочитанных и обновляем timestamp
                chatDao.incrementUnread(chatId, System.currentTimeMillis())
            }

            // Групповые сообщения (1.0.68) — отдельная ветка дешифровки через sender key.
            val chatForDecrypt = chatDao.getById(chatId)
            val isGroup = chatForDecrypt?.type == "GROUP"

            // Расшифровываем — пробуем текущий приватный ключ + все legacy
            val myPrivateKeyBase64 = localKeyStore.getPrivateKey() ?: run {
                Timber.w("IncomingMessageHandler: no local private key")
                return null
            }
            val myPrivateKeyBytes = Base64.decode(myPrivateKeyBase64, Base64.NO_WRAP)

            var decryptedContent: String? = if (isGroup) {
                decryptGroupMessage(chatId, senderId, messageId, encryptedContent)
            } else {
                val senderPublicKeyBytes = Base64.decode(sender.publicKey, Base64.NO_WRAP)
                val secrets = mutableListOf(
                    cryptoManager.computeSharedSecret(myPrivateKeyBytes, senderPublicKeyBytes)
                )
                for (legacy in localKeyStore.getLegacyPrivateKeys()) {
                    if (legacy == myPrivateKeyBase64) continue
                    runCatching {
                        val bytes = Base64.decode(legacy, Base64.NO_WRAP)
                        secrets.add(cryptoManager.computeSharedSecret(bytes, senderPublicKeyBytes))
                    }
                }
                cryptoManager.decryptMessageWithAnyKey(encryptedContent, secrets, messageId)
            }

            if (!isGroup && decryptedContent == null) {
                // Расшифровка не удалась — отправитель мог обновить ключевую пару (перелогин).
                // Запрашиваем актуальный публичный ключ с сервера и пробуем ещё раз.
                val freshDto = runCatching { api.getUserById(senderId).data }.getOrNull()
                if (freshDto != null && freshDto.publicKey != sender.publicKey) {
                    Timber.d("IncomingMessageHandler: retrying decrypt with fresh public key for $senderId")
                    val freshPubBytes = Base64.decode(freshDto.publicKey, Base64.NO_WRAP)
                    val freshSecrets = mutableListOf(
                        cryptoManager.computeSharedSecret(myPrivateKeyBytes, freshPubBytes)
                    )
                    for (legacy in localKeyStore.getLegacyPrivateKeys()) {
                        if (legacy == myPrivateKeyBase64) continue
                        runCatching {
                            val bytes = Base64.decode(legacy, Base64.NO_WRAP)
                            freshSecrets.add(cryptoManager.computeSharedSecret(bytes, freshPubBytes))
                        }
                    }
                    decryptedContent = cryptoManager.decryptMessageWithAnyKey(
                        encryptedContent, freshSecrets, messageId,
                    )
                    if (decryptedContent != null) {
                        // Сохраняем обновлённый ключ чтобы следующие сообщения расшифровывались сразу
                        userDao.upsert(sender.copy(publicKey = freshDto.publicKey))
                    }
                }
                if (decryptedContent == null) decryptedContent = "[Не удалось расшифровать]"
            }
            // Для group-случая ветка выше не исполняется — fallback отдельно.
            if (decryptedContent == null) decryptedContent = "[Не удалось расшифровать]"

            val timestamp = if (timestampStr != null) {
                try { Instant.parse(timestampStr).toEpochMilli() } catch (_: Exception) { System.currentTimeMillis() }
            } else System.currentTimeMillis()

            // Сохраняем в БД
            val safeType = runCatching { MessageType.valueOf(msgType) }.getOrDefault(MessageType.TEXT).name
            // Извлекаем replyToId из payload (для цитат)
            val replyToId = payload["replyToId"] as? String
            // Для медиа-типов не кладём encryptedContent локально — он дублирует
            // тяжёлый base64-блоб в decryptedContent и раздувает строку Room
            // свыше лимита CursorWindow (2МБ).
            val isHeavy = safeType in setOf(
                MessageType.IMAGE.name, MessageType.AUDIO.name, MessageType.VIDEO.name,
            )
            messageDao.upsert(MessageEntity(
                id = messageId, chatId = chatId, senderId = senderId,
                encryptedContent = if (isHeavy) "" else encryptedContent,
                decryptedContent = decryptedContent,
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

    /**
     * Обрабатывает событие user_updated от сервера: чат-партнёр поменял свой
     * профиль (имя/аватар/bio). Обновляем UserEntity в локальной БД, плюс
     * синхронно подтягиваем avatarUrl у direct-чатов с этим юзером — иначе
     * в списке чатов аватар продолжит показываться старый (он копируется в
     * ChatEntity при syncChats и не обновляется автоматически).
     */
    suspend fun handleUserUpdated(userId: String, payload: Map<String, Any?>) {
        val existing = userDao.getById(userId)
        val phone       = payload["phone"]       as? String ?: existing?.phone ?: ""
        val username    = payload["username"]    as? String ?: existing?.username ?: ""
        val displayName = payload["displayName"] as? String ?: existing?.displayName ?: ""
        val avatarUrl   = payload["avatarUrl"]   as? String
        val bio         = payload["bio"]         as? String
        val isOnline    = payload["isOnline"]    as? Boolean ?: existing?.isOnline ?: false
        val publicKey   = payload["publicKey"]   as? String ?: existing?.publicKey ?: ""
        val lastSeenStr = payload["lastSeen"]    as? String
        val lastSeen    = lastSeenStr?.let {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        } ?: existing?.lastSeen ?: System.currentTimeMillis()

        userDao.upsert(UserEntity(
            id = userId,
            phone = phone,
            username = username,
            displayName = displayName,
            avatarUrl = avatarUrl,
            bio = bio,
            isOnline = isOnline,
            lastSeen = lastSeen,
            publicKey = publicKey,
            isContact = existing?.isContact ?: false,
        ))

        // Обновляем avatarUrl и title у direct-чата с этим юзером — chat list
        // рендерит аватар из ChatEntity, а не из UserEntity, поэтому без этого
        // апдейта в списке чатов аватар оставался бы старый.
        val chat = chatDao.getByOtherUserId(userId)
        if (chat != null) {
            val newTitle = if (chat.title.isEmpty() || chat.title == existing?.displayName) {
                displayName
            } else chat.title
            chatDao.upsert(chat.copy(title = newTitle, avatarUrl = avatarUrl ?: chat.avatarUrl))
        }
    }

    /** Помечает все сообщения от других пользователей в чате как READ, когда собеседник открывает чат. */
    suspend fun handleMessagesRead(chatId: String, readerId: String) {
        messageDao.markChatMessagesRead(chatId, readerId)
    }

    // ── Групповые сообщения (1.0.68) ─────────────────────────────────────────

    /**
     * Расшифровывает групповое сообщение. Алгоритм:
     *  1. Ищем sender key владельца [senderId] в локальной БД — сначала тот
     *     epoch, что сейчас у чата, потом самый свежий из имеющихся.
     *  2. Пытаемся расшифровать — если удалось, возвращаем текст.
     *  3. Если ключа нет или он не подошёл — тянем с сервера свежие sender
     *     keys через [ChatRepository.refreshSenderKeys] и повторяем попытку.
     *  4. Если и после этого не получилось — вернём null, вызывающий
     *     поставит плашку «[Не удалось расшифровать]».
     */
    private suspend fun decryptGroupMessage(
        chatId: String,
        senderId: String,
        messageId: String,
        encryptedContent: String,
    ): String? {
        // Пробуем все имеющиеся sender keys этого отправителя по всем
        // epoch'ам — не только текущему. Сценарий: после logout/login
        // в БД могли остаться/появиться ключи из разных epoch'ов, и
        // конкретное сообщение зашифровано под одним из них. Сужать
        // поиск нельзя — иначе сообщения старых epoch'ов превращаются
        // в плашку «не удалось расшифровать».
        suspend fun tryWithCurrentDb(): String? {
            for (key in groupSenderKeyDao.listByOwner(chatId, senderId)) {
                val bytes = runCatching {
                    Base64.decode(key.senderKey, Base64.NO_WRAP)
                }.getOrNull() ?: continue
                val text = groupCryptoManager.decryptGroupMessage(encryptedContent, bytes, messageId)
                if (text != null) return text
            }
            return null
        }

        tryWithCurrentDb()?.let { return it }

        // Промах → тянем свежие ключи (без фильтра по epoch) и повторяем.
        runCatching {
            chatRepository.get().refreshSenderKeys(chatId)
        }.onFailure { e ->
            Timber.w(e, "decryptGroupMessage: refreshSenderKeys failed for chat=$chatId")
        }
        return tryWithCurrentDb()
    }

    // ── События групп (1.0.68) ───────────────────────────────────────────────

    suspend fun handleGroupInfoUpdated(chatId: String, title: String?, avatarUrl: String?) {
        val chat = chatDao.getById(chatId) ?: return
        chatDao.upsert(chat.copy(
            title = title ?: chat.title,
            avatarUrl = avatarUrl ?: chat.avatarUrl,
            updatedAt = System.currentTimeMillis(),
        ))
    }

    /**
     * Новый участник добавлен в группу.
     *
     *  - Если это я — полный sync чата + забираю чужие sender-ключи + генерю
     *    свой ключ и раздаю остальным (иначе я бы не смог отправлять).
     *  - Если это кто-то другой — я обновляю свой epoch и отправляю ему свой
     *    sender-ключ (чтобы новый участник мог читать мои сообщения). Ротация
     *    своего ключа не требуется (дизайн 1.0.68 §1.2).
     */
    suspend fun handleGroupMemberAdded(chatId: String, userId: String, epoch: Int) {
        val chat = chatDao.getById(chatId)
        val myId = currentUserIdSync() ?: return
        if (chat != null) {
            chatDao.upsert(chat.copy(currentEpoch = epoch))
        }

        if (userId == myId) {
            runCatching {
                // 1. Тянем актуальные данные чата (title, members, epoch).
                chatRepository.get().syncChats(myId)
                // 2. Забираем чужие sender keys (ownerId=другие, recipientId=я),
                //    чтобы потом расшифровывать их входящие сообщения.
                chatRepository.get().refreshSenderKeys(chatId)
                // 3. Генерим свой sender key и раздаём всем остальным участникам —
                //    без этого шага encryptForChat падал с «Нет sender key
                //    для группы (epoch=N)» при попытке отправить сообщение.
                val otherMembers = chatRepository.get().getGroupMembers(chatId)
                    .filter { it.id != myId }
                if (otherMembers.isNotEmpty()) {
                    chatRepository.get().uploadMySenderKey(chatId, epoch, otherMembers)
                }
            }
        } else {
            // Другого участника добавили — я существующий член. Мне нужно
            // поделиться своим sender-ключом с новым участником, чтобы он
            // мог расшифровать мои будущие сообщения. Ключ не ротируем —
            // заливаем один и тот же, но под новым epoch чтобы encryptForChat
            // находил его при отправке (ищет по currentEpoch).
            runCatching {
                chatRepository.get().uploadMySenderKey(
                    chatId = chatId,
                    epoch = epoch,
                    recipients = chatRepository.get().getGroupMembers(chatId)
                        .filter { it.id != myId },
                )
            }
        }
    }

    suspend fun handleGroupMemberRemoved(chatId: String, userId: String, epoch: Int) {
        val chat = chatDao.getById(chatId) ?: return
        val myId = currentUserIdSync()
        if (userId == myId) {
            // Меня кикнули — чистим чат и ключи локально.
            groupSenderKeyDao.deleteByChat(chatId)
            chatDao.deleteById(chatId)
            return
        }
        chatDao.upsert(chat.copy(currentEpoch = epoch))
        groupSenderKeyDao.deleteByOwner(chatId, userId)
    }

    suspend fun handleGroupRoleChanged(chatId: String, userId: String, role: String) {
        val chat = chatDao.getById(chatId) ?: return
        val myId = currentUserIdSync()
        if (userId == myId) {
            // Моя роль поменялась — обновляем myRole в чате.
            chatDao.upsert(chat.copy(myRole = role))
        }
        // Для других участников роль отображается только в GroupInfoScreen —
        // там при открытии тянется getGroupMembers() c сервера, так что
        // локально дополнительно ничего делать не нужно.
    }

    suspend fun handleGroupDeleted(chatId: String) {
        groupSenderKeyDao.deleteByChat(chatId)
        chatDao.deleteById(chatId)
    }

    /**
     * Прилетел новый sender key от владельца [ownerId] под [epoch] — стягиваем
     * его и добавляем в локальную БД. Без этого сообщения владельца в этом
     * epoch'е расшифровать невозможно.
     */
    suspend fun handleGroupSenderKeyReady(chatId: String, ownerId: String, epoch: Int) {
        runCatching {
            chatRepository.get().refreshSenderKeys(chatId, epoch)
        }.onFailure { e ->
            Timber.w(e, "handleGroupSenderKeyReady: refresh failed chat=$chatId owner=$ownerId epoch=$epoch")
        }
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

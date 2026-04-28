package com.secure.messenger.data.repository

import android.content.Context
import android.util.Base64
import com.secure.messenger.data.local.MutedChatsPrefs
import com.secure.messenger.data.local.dao.ChatDao
import com.secure.messenger.data.local.dao.ChatMemberDao
import com.secure.messenger.data.local.dao.GroupSenderKeyDao
import com.secure.messenger.data.local.dao.MessageDao
import com.secure.messenger.data.local.dao.UserDao
import com.secure.messenger.data.local.entities.ChatEntity
import com.secure.messenger.data.local.entities.ChatMemberEntity
import com.secure.messenger.data.local.entities.ChatWithLastMessage
import com.secure.messenger.data.local.entities.GroupSenderKeyEntity
import com.secure.messenger.data.local.entities.MessageEntity
import com.secure.messenger.data.local.entities.UserEntity
import com.secure.messenger.data.remote.api.MessengerApi
import com.secure.messenger.data.remote.api.SendMessageRequest
import com.secure.messenger.data.remote.api.dto.AddMemberDto
import com.secure.messenger.data.remote.api.dto.ChangeRoleDto
import com.secure.messenger.data.remote.api.dto.SenderKeyEntryDto
import com.secure.messenger.data.remote.api.dto.UpdateGroupInfoDto
import com.secure.messenger.data.remote.api.dto.UploadSenderKeysDto
import com.secure.messenger.data.remote.websocket.SignalingClient
import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.ChatRole
import com.secure.messenger.domain.model.ChatType
import com.secure.messenger.domain.model.Message
import com.secure.messenger.domain.model.MessageStatus
import com.secure.messenger.domain.model.MessageType
import com.secure.messenger.domain.model.User
import com.secure.messenger.domain.repository.AuthRepository
import com.secure.messenger.domain.repository.ChatRepository
import com.secure.messenger.utils.CryptoManager
import com.secure.messenger.utils.GroupCryptoManager
import com.secure.messenger.utils.LocalKeyStore
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class ChatRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: MessengerApi,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val userDao: UserDao,
    private val chatMemberDao: ChatMemberDao,
    private val groupSenderKeyDao: GroupSenderKeyDao,
    private val cryptoManager: CryptoManager,
    private val groupCryptoManager: GroupCryptoManager,
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
            com.secure.messenger.data.local.KeyChangeTracker
                .observePublicKey(context, memberDto.id, memberDto.publicKey)
            userDao.upsert(UserEntity(
                id = memberDto.id, phone = memberDto.phone, username = memberDto.username,
                displayName = memberDto.displayName, avatarUrl = memberDto.avatarUrl,
                bio = memberDto.bio, isOnline = memberDto.isOnline,
                lastSeen = Instant.parse(memberDto.lastSeen).toEpochMilli(),
                publicKey = memberDto.publicKey,
                isContact = existing?.isContact ?: false,
            ))
        }
        // chat_members для DIRECT нужен чтобы getGroupMembers() и любые
        // прочие наблюдатели работали единообразно (хоть DIRECT там и не
        // открывают экран «участники», для общности кеша).
        cacheChatMembers(dto.id, dto.members.orEmpty())

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

        // isMuted — локальный флаг, сервер не управляет им. Сохраняем
        // существующее значение, чтобы повторное открытие direct-чата не
        // сбрасывало мьют пользователя.
        val existingChat = chatDao.getById(dto.id)
        val entity = ChatEntity(
            id = dto.id, type = dto.type, title = title, avatarUrl = avatarUrl,
            unreadCount = dto.unreadCount, isPinned = dto.isPinned,
            isMuted = existingChat?.isMuted ?: dto.isMuted,
            updatedAt = Instant.parse(dto.updatedAt).toEpochMilli(), otherUserId = userId,
            myRole = "", currentEpoch = 0,
        )
        chatDao.upsert(entity)
        entity.toChat()
    }

    override suspend fun createGroupChat(title: String, memberIds: List<String>): Result<Chat> = runCatching {
        val dto = api.createGroupChat(
            com.secure.messenger.data.remote.api.dto.CreateGroupChatDto(
                title = title,
                memberIds = memberIds,
            )
        ).data ?: error("Server returned null chat")
        val myUserId = currentUserIdSync()

        val entity = ChatEntity(
            id = dto.id, type = dto.type, title = dto.title, avatarUrl = dto.avatarUrl,
            unreadCount = 0, isPinned = false, isMuted = false,
            updatedAt = Instant.parse(dto.updatedAt).toEpochMilli(), otherUserId = null,
            myRole = dto.myRole ?: ChatRole.CREATOR.name,
            currentEpoch = dto.currentEpoch,
        )
        chatDao.upsert(entity)
        // ChatEntity создан — теперь FK chat_members(chatId)→chats(id) удовлетворён.
        // Кешируем состав одним вызовом (upsert юзеров + replaceForChat).
        cacheChatMembers(dto.id, dto.members.orEmpty())

        // Генерируем свой sender key для группы и заливаем его на сервер
        // в зашифрованном виде для каждого участника. Если не получилось —
        // чат всё равно создан, пользователь сможет запустить ротацию позже
        // (через любое add/remove-событие), а пока увидит группу в списке.
        runCatching {
            initializeSenderKey(
                chatId = dto.id,
                epoch = dto.currentEpoch,
                myUserId = myUserId,
                recipients = dto.members.orEmpty()
                    .filter { it.id != myUserId }
                    .map { it.toDomain() },
            )
        }.onFailure { e ->
            Timber.w(e, "createGroupChat: не смогли разослать sender key, группа создана без ключей")
        }

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
        val payload = encryptForChat(chatEntity, content, messageId, MessageType.TEXT.name)

        // Оптимистичная вставка в локальную БД со статусом SENDING.
        // senderId сразу ставим реальным — иначе MessageBubble нарисует сообщение
        // как входящее (слева) до ответа сервера, а потом перепрыгнет вправо.
        val optimisticEntity = MessageEntity(
            id = messageId, chatId = chatId, senderId = myUserId,
            encryptedContent = payload.content, decryptedContent = content,
            type = MessageType.TEXT.name, status = MessageStatus.SENDING.name,
            timestamp = System.currentTimeMillis(),
            replyToId = replyToId, mediaUrl = null, isEdited = false,
        )
        messageDao.upsert(optimisticEntity)
        // Поднимаем чат в списке наверх — свой Touch локально, серверный
        // Touch уже выполняется в MessageHandler.Send.
        chatDao.touchUpdatedAt(chatId, optimisticEntity.timestamp)

        // Отправляем на сервер
        val request = SendMessageRequest(
            messageId = messageId,
            encryptedContent = payload.content,
            replyToId = replyToId,
            groupEpoch = payload.usedEpoch,
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
        signalingClient.sendChatMessage(chatId, payload.content, dto.id)

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

        val voicePayload = com.secure.messenger.utils.VoiceCodec.encode(audioBytes, durationSeconds, waveform)
        val encrypted = encryptForChat(chatEntity, voicePayload, messageId, MessageType.AUDIO.name)

        val optimisticEntity = MessageEntity(
            id = messageId, chatId = chatId, senderId = myUserId,
            // Не храним encryptedContent для аудио — raw-байты дублируются и
            // раздувают строку Room (см. sendImageMessage).
            encryptedContent = "",
            decryptedContent = voicePayload,
            type = MessageType.AUDIO.name,
            status = MessageStatus.SENDING.name,
            timestamp = System.currentTimeMillis(),
            replyToId = null, mediaUrl = null, isEdited = false,
        )
        messageDao.upsert(optimisticEntity)
        chatDao.touchUpdatedAt(chatId, optimisticEntity.timestamp)

        val request = SendMessageRequest(
            messageId = messageId,
            encryptedContent = encrypted.content,
            type = MessageType.AUDIO.name,
            groupEpoch = encrypted.usedEpoch,
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
        signalingClient.sendChatMessage(chatId, encrypted.content, dto.id)

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

        val imagePayload = com.secure.messenger.utils.ImageCodec.encode(imageData)
        val encrypted = encryptForChat(chatEntity, imagePayload, messageId, MessageType.IMAGE.name)

        val optimisticEntity = MessageEntity(
            id = messageId, chatId = chatId, senderId = myUserId,
            // encryptedContent локально НЕ храним для медиа — оно весит как и
            // decryptedContent ~800KB base64. Вдвоём они раздувают строку
            // Room до >2МБ и вызывают SQLiteBlobTooBigException при чтении.
            encryptedContent = "",
            decryptedContent = imagePayload,
            type = MessageType.IMAGE.name,
            status = MessageStatus.SENDING.name,
            timestamp = System.currentTimeMillis(),
            replyToId = null, mediaUrl = null, isEdited = false,
        )
        messageDao.upsert(optimisticEntity)
        chatDao.touchUpdatedAt(chatId, optimisticEntity.timestamp)

        val request = SendMessageRequest(
            messageId = messageId,
            encryptedContent = encrypted.content,
            type = MessageType.IMAGE.name,
            groupEpoch = encrypted.usedEpoch,
        )
        try {
            val dto = api.sendMessage(chatId, request).data ?: error("Send failed")
            val confirmedEntity = optimisticEntity.copy(
                id = dto.id,
                senderId = dto.senderId,
                status = MessageStatus.SENT.name,
                timestamp = Instant.parse(dto.timestamp).toEpochMilli(),
            )
            messageDao.upsert(confirmedEntity)
            signalingClient.sendChatMessage(chatId, encrypted.content, dto.id)
            confirmedEntity.toDomain()
        } catch (e: Exception) {
            messageDao.upsert(optimisticEntity.copy(status = MessageStatus.FAILED.name))
            throw e
        }
    }

    override suspend fun deleteMessage(messageId: String): Result<Unit> = runCatching {
        // Удаляем на сервере; 404 = уже удалено, 5xx = сервер перегружен — в
        // обоих случаях чистим локально, у пользователя всё равно сообщение
        // пропадёт, и кидать ошибку в UI нет смысла.
        val response = runCatching { api.deleteMessage(messageId) }
        messageDao.deleteById(messageId)
        response.onFailure { e ->
            val code = (e as? retrofit2.HttpException)?.code()
            val ignorable = code != null && (code == 404 || code in 500..599)
            if (!ignorable) throw e
        }
    }

    override suspend fun editMessage(messageId: String, newContent: String): Result<Message> = runCatching {
        val existing = messageDao.getById(messageId) ?: error("Message not found")
        val chatEntity = chatDao.getById(existing.chatId) ?: error("Chat not found")
        val encrypted = encryptForChat(chatEntity, newContent, messageId, existing.type)

        api.editMessage(messageId, mapOf("encryptedContent" to encrypted.content))
        val updated = existing.copy(encryptedContent = encrypted.content, decryptedContent = newContent, isEdited = true)
        messageDao.update(updated)
        updated.toDomain()
    }

    override suspend fun fetchMessages(chatId: String): Result<Boolean> = runCatching {
        Timber.d("fetchMessages: НАЧАЛО для chatId=$chatId")
        val chat = chatDao.getById(chatId) ?: error("Chat not found: $chatId")
        val pageLimit = INITIAL_FETCH_LIMIT
        val response = api.getMessages(chatId, limit = pageLimit)
        Timber.d("fetchMessages: API ответ: success=${response.success}, data.size=${response.data?.size}, error=${response.error}")

        // КРИТИЧНО: если сервер не вернул валидные данные (success=false, data=null,
        // 5xx через retrofit-обёртку), НЕ трогаем локальный кеш. Раньше fetchMessages
        // при любом «пустом» ответе сносил все сообщения чата — это давало
        // эффект «чаты пропадают из кеша» при временной сетевой проблеме.
        if (!response.success || response.data == null) {
            Timber.w("fetchMessages: server returned no valid data (success=${response.success}, error=${response.error}) — keeping local cache intact")
            return@runCatching false
        }
        val dtos = response.data

        // ── Синхронизация удалений: удаляем локальные сообщения, которых нет на сервере ──
        // Делаем ЭТО ПЕРВЫМ — до расшифровки, которая может упасть.
        val serverIds = dtos.map { it.id }.toSet()
        Timber.d("fetchMessages: chatId=$chatId, сервер вернул ${serverIds.size} сообщений")

        if (dtos.isEmpty()) {
            // Сервер ответил «нет сообщений» (success=true, data=[]). Это
            // легитимный ответ для пустого чата. Чтобы не стереть случайно
            // настоящую историю при race condition (например сервер ещё не
            // успел проиндексировать), удаляем локальные сообщения ТОЛЬКО
            // если их меньше порога. Если их больше — это похоже на сбой,
            // лучше оставить кеш и подождать следующего syncа.
            val allLocal = messageDao.getAllMessageIds(chatId)
            if (allLocal.size > 5) {
                Timber.w("fetchMessages: server returned empty but local has ${allLocal.size} messages — keeping cache (looks like server-side anomaly)")
                return@runCatching false
            }
            Timber.d("fetchMessages: сервер пуст и локально ${allLocal.size} сообщений, чистим")
            for (id in allLocal) {
                messageDao.deleteById(id)
            }
            return@runCatching false
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

        // Для группы: bulk-prefetch sender-ключей, если хотя бы для одного
        // (sender, epoch) пары из ответа сервера локально нет ключа.
        // Раньше условие было «у sender'а нет ВООБЩЕ никаких ключей» —
        // и пропускало случай «есть ключ epoch=N-1, но пришло сообщение
        // под epoch=N» (race при добавлении/удалении участника).
        if (chat.type == "GROUP") {
            val pairs = dtos.asSequence()
                .filter { it.type != "SYSTEM" }
                .map { it.senderId to (it.groupEpoch ?: 0) }
                .distinct()
            val needRefresh = pairs.any { (senderId, epoch) ->
                groupSenderKeyDao.get(chatId, senderId, epoch) == null
            }
            if (needRefresh) {
                runCatching { refreshSenderKeys(chatId) }
                    .onFailure { Timber.w(it, "fetchMessages: refreshSenderKeys for chat=$chatId failed") }
            }
        }

        val sharedSecrets: List<ByteArray> = if (chat.type == "DIRECT") {
            val otherUserId = chat.otherUserId ?: error("otherUserId missing for chat $chatId")
            val otherUser = run {
                val fresh = runCatching { api.getUserById(otherUserId).data }.getOrNull()
                if (fresh != null) {
                    val entity = userDao.getById(otherUserId)
                    if (entity == null || entity.publicKey != fresh.publicKey) {
                        com.secure.messenger.data.local.KeyChangeTracker
                            .observePublicKey(context, fresh.id, fresh.publicKey)
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
            buildSharedSecrets(myPrivateKeyBase64, otherUser.publicKey)
        } else emptyList()

        val entities = dtos.map { dto ->
            val existing = messageDao.getById(dto.id)
            val decrypted = decryptMessageEntry(chat, dto, sharedSecrets, existing)
            // Для медиа-типов не кладём encryptedContent локально — это
            // base64 сырых байт, который уже дублирован в decryptedContent.
            // Хранить его повторно — рецепт CursorWindow-переполнения.
            val isHeavy = dto.type in setOf("IMAGE", "AUDIO", "VIDEO")
            MessageEntity(
                id = dto.id, chatId = chatId, senderId = dto.senderId,
                encryptedContent = if (isHeavy) "" else dto.encryptedContent,
                decryptedContent = decrypted,
                type = dto.type, status = dto.status,
                timestamp = Instant.parse(dto.timestamp).toEpochMilli(),
                replyToId = dto.replyToId, mediaUrl = dto.mediaUrl, isEdited = dto.isEdited,
            )
        }
        upsertWithoutStatusDowngrade(entities)
        // Если сервер отдал меньше страницы — старее уже нет, незачем
        // дёргать пагинацию. UI спрячет лоадер, не дожидаясь второго запроса.
        dtos.size >= pageLimit
    }

    override suspend fun fetchOlderMessages(chatId: String, beforeTimestamp: Long): Result<Boolean> = runCatching {
        val chat = chatDao.getById(chatId) ?: error("Chat not found: $chatId")
        val pageLimit = 30
        val response = api.getMessages(chatId, before = beforeTimestamp, limit = pageLimit)
        // Та же защита что и в fetchMessages: при невалидном ответе кеш не
        // трогаем — иначе короткий 5xx из nginx стёр бы локальную историю
        // в диапазоне.
        if (!response.success || response.data == null) {
            Timber.w("fetchOlderMessages: invalid response (success=${response.success}, error=${response.error})")
            return@runCatching false
        }
        val dtos = response.data
        if (dtos.isEmpty()) return@runCatching false // нет больше сообщений

        // Синхронизация удалений в этом диапазоне: если на той стороне
        // удалили старое сообщение, локально оно тоже должно пропасть.
        // Диапазон — между самым старым из ответа сервера (включительно)
        // и beforeTimestamp (исключительно). Сообщения, которые лежат в
        // этом окне локально но отсутствуют в server-snapshot, удаляем.
        val serverIds = dtos.map { it.id }.toSet()
        val oldestServerTs = dtos.minOf { Instant.parse(it.timestamp).toEpochMilli() }
        val localInRange = messageDao.getMessageIdsSince(chatId, oldestServerTs)
            .filter { id ->
                val msg = messageDao.getById(id) ?: return@filter false
                msg.timestamp < beforeTimestamp
            }
        val toDelete = localInRange.filter { it !in serverIds }
        for (id in toDelete) {
            Timber.d("fetchOlderMessages: deleting locally $id (removed on server)")
            messageDao.deleteById(id)
        }

        val myPrivateKeyBase64 = localKeyStore.getPrivateKey() ?: error("No local private key")

        // Та же защита что в fetchMessages — bulk refresh sender-keys если
        // встретилось сообщение под epoch'ом, для которого ключа нет.
        if (chat.type == "GROUP") {
            val pairs = dtos.asSequence()
                .filter { it.type != "SYSTEM" }
                .map { it.senderId to (it.groupEpoch ?: 0) }
                .distinct()
            val needRefresh = pairs.any { (senderId, epoch) ->
                groupSenderKeyDao.get(chatId, senderId, epoch) == null
            }
            if (needRefresh) {
                runCatching { refreshSenderKeys(chatId) }
                    .onFailure { Timber.w(it, "fetchOlderMessages: refreshSenderKeys for chat=$chatId failed") }
            }
        }

        val sharedSecrets: List<ByteArray> = if (chat.type == "DIRECT") {
            val otherUserId = chat.otherUserId ?: error("otherUserId missing")
            val otherUser = userDao.getById(otherUserId) ?: error("Other user not found")
            buildSharedSecrets(myPrivateKeyBase64, otherUser.publicKey)
        } else emptyList()

        val entities = dtos.map { dto ->
            val existing = messageDao.getById(dto.id)
            val decrypted = decryptMessageEntry(chat, dto, sharedSecrets, existing)
            // Для медиа-типов не кладём encryptedContent локально — это
            // base64 сырых байт, который уже дублирован в decryptedContent.
            // Хранить его повторно — рецепт CursorWindow-переполнения.
            val isHeavy = dto.type in setOf("IMAGE", "AUDIO", "VIDEO")
            MessageEntity(
                id = dto.id, chatId = chatId, senderId = dto.senderId,
                encryptedContent = if (isHeavy) "" else dto.encryptedContent,
                decryptedContent = decrypted,
                type = dto.type, status = dto.status,
                timestamp = Instant.parse(dto.timestamp).toEpochMilli(),
                replyToId = dto.replyToId, mediaUrl = dto.mediaUrl, isEdited = dto.isEdited,
            )
        }
        upsertWithoutStatusDowngrade(entities)
        true // есть ещё сообщения
    }

    /**
     * Единый entry-point расшифровки сообщения из серверного fetch'а.
     * Для DIRECT — X25519 ECDH, для GROUP — sender key. Если decrypt не
     * удался, возвращает предыдущий валидный текст из БД (если есть) —
     * не затираем корректный текст плашкой при ре-фетчах.
     */
    private suspend fun decryptMessageEntry(
        chat: ChatEntity,
        dto: com.secure.messenger.data.remote.api.dto.MessageDto,
        directSharedSecrets: List<ByteArray>,
        existing: MessageEntity?,
    ): String {
        if (dto.type == "SYSTEM") return dto.encryptedContent
        val aad = aadFor(chat.id, dto.senderId, dto.id, dto.type)
        if (chat.type == "GROUP") {
            val decrypted = tryDecryptGroupMessage(
                chatId = chat.id,
                senderId = dto.senderId,
                messageId = dto.id,
                encryptedContent = dto.encryptedContent,
                aad = aad,
            )
            if (decrypted != null) return decrypted
            val prev = existing?.decryptedContent
            if (!prev.isNullOrEmpty() && prev != "[Не удалось расшифровать]" && prev != "[Групповые чаты не поддерживаются]") {
                return prev
            }
            // Финальный fail — реальная проблема, не trace а warn.
            // Если такие записи накапливаются в логах — это sign масcового
            // регресса (race на epoch, потеря ключей и т.п.).
            Timber.w(
                "decrypt FAIL group chat=${chat.id} sender=${dto.senderId} " +
                "msg=${dto.id} epoch=${dto.groupEpoch} type=${dto.type}",
            )
            return "[Не удалось расшифровать]"
        }
        return decryptOrKeepExisting(dto, directSharedSecrets, existing, aad)
    }

    /**
     * Расшифровывает сообщение от сервера с защитой от потери уже расшифрованного
     * текста. Если новый decrypt падает (например, собеседник сменил ключевую
     * пару с момента отправки), а у нас в локальной БД уже есть успешно
     * расшифрованный текст этого же сообщения — возвращаем СУЩЕСТВУЮЩИЙ. Так
     * мы не затираем правильный исторический текст плашкой «не удалось».
     *
     * Без этой защиты при ре-фетче (открытие чата, syncChats, refresh)
     * сообщения, успешно расшифрованные в прошлом, превращались в плашку
     * «[Не удалось расшифровать]» — потому что для них больше нет валидного
     * sharedSecret-а.
     */
    private fun decryptOrKeepExisting(
        dto: com.secure.messenger.data.remote.api.dto.MessageDto,
        sharedSecrets: List<ByteArray>,
        existing: MessageEntity?,
        aad: ByteArray? = null,
    ): String {
        if (dto.type == "SYSTEM") return dto.encryptedContent
        if (sharedSecrets.isNotEmpty()) {
            val fresh = cryptoManager.decryptMessageWithAnyKey(
                dto.encryptedContent, sharedSecrets, dto.id, aad,
            )
            if (fresh != null) return fresh
        }
        // Decrypt не удался — пытаемся сохранить уже существующий текст,
        // если он есть и не является fail-плашкой.
        val prev = existing?.decryptedContent
        if (!prev.isNullOrEmpty() && prev != "[Не удалось расшифровать]" && prev != "[Групповые чаты не поддерживаются]") {
            return prev
        }
        // Финальный fail — реальная проблема. См. п. 3 в анализе безопасности
        // (плохо синхронизированный publicKey собеседника, потерянные legacy
        // ключи, и т.п.). Логируем чтобы видеть массовые проблемы.
        Timber.w(
            "decrypt FAIL direct sender=${dto.senderId} msg=${dto.id} " +
            "type=${dto.type} secrets=${sharedSecrets.size}",
        )
        return if (sharedSecrets.isEmpty()) "[Групповые чаты не поддерживаются]" else "[Не удалось расшифровать]"
    }

    /**
     * Строит список ECDH shared-секретов для расшифровки: сначала с текущим
     * приватным ключом, затем с каждым legacy-ключом. Сообщение могло быть
     * зашифровано при любой из прошлых пар ключей (до установки пароля,
     * или с разными паролями в прошлом) — пробуем все по очереди.
     */
    private fun buildSharedSecrets(
        myPrivateKeyBase64: String,
        theirPublicKeyBase64: String,
    ): List<ByteArray> {
        // У бота (и некоторых служебных юзеров) publicKey пустой или битый.
        // X25519 требует ровно 32 байта — без проверки computeSharedSecret
        // бросает, fetchMessages обрывается, и ВСЕ SYSTEM-сообщения бота
        // не попадают в БД. Возвращаем пустой список — SYSTEM проходит без
        // дешифровки, а регулярные сообщения в таком чате и так невозможны.
        val theirPublicKeyBytes = runCatching {
            Base64.decode(theirPublicKeyBase64, Base64.NO_WRAP)
        }.getOrNull()
        if (theirPublicKeyBytes == null || theirPublicKeyBytes.size != 32) {
            return emptyList()
        }
        val result = mutableListOf<ByteArray>()
        runCatching {
            val currentPriv = Base64.decode(myPrivateKeyBase64, Base64.NO_WRAP)
            result.add(cryptoManager.computeSharedSecret(currentPriv, theirPublicKeyBytes))
        }
        for (legacyBase64 in localKeyStore.getLegacyPrivateKeys()) {
            if (legacyBase64 == myPrivateKeyBase64) continue
            runCatching {
                val bytes = Base64.decode(legacyBase64, Base64.NO_WRAP)
                result.add(cryptoManager.computeSharedSecret(bytes, theirPublicKeyBytes))
            }
        }
        return result
    }

    override suspend fun syncChats(myUserId: String): Result<Unit> = runCatching {
        val dtos = api.getChats().data.orEmpty()
        for (dto in dtos) {
            // Сохраняем участников в локальную БД — нужно для шифрования и отображения.
            // isOnline у участников приходит с сервера актуальным — это второй
            // источник правды, помимо WebSocket-событий user_status (на случай
            // если событие было пропущено из-за реконнекта).
            // Дополнительно: KeyChangeTracker отслеживает смену publicKey
            // (для предупреждения «код безопасности изменился»).
            dto.members?.forEach { memberDto ->
                com.secure.messenger.data.local.KeyChangeTracker
                    .observePublicKey(context, memberDto.id, memberDto.publicKey)
            }

            val existingChat = chatDao.getById(dto.id)

            val otherUserId = if (dto.type == "DIRECT") {
                dto.members?.firstOrNull { it.id != myUserId }?.id
                    ?: existingChat?.otherUserId
            } else null

            val otherMember = if (dto.type == "DIRECT") {
                dto.members?.firstOrNull { it.id != myUserId }
            } else null

            val title = if (dto.type == "DIRECT" && dto.title.isEmpty()) {
                otherMember?.displayName
                    ?: existingChat?.title
                    ?: ""
            } else {
                dto.title
            }

            // Для прямых чатов: если у чата нет аватарки — берём аватар собеседника
            val avatarUrl = dto.avatarUrl ?: otherMember?.avatarUrl

            chatDao.upsert(ChatEntity(
                id = dto.id, type = dto.type, title = title, avatarUrl = avatarUrl,
                unreadCount = dto.unreadCount, isPinned = dto.isPinned,
                // isMuted — локальный флаг, сервер про него не знает (всегда отдаёт
                // false). Если перезаписывать тут — после любого syncChats юзер
                // «размьютит» чат сам того не заметив. Поэтому сохраняем локальное
                // значение как и isHidden.
                isMuted = existingChat?.isMuted ?: dto.isMuted,
                updatedAt = Instant.parse(dto.updatedAt).toEpochMilli(),
                otherUserId = otherUserId,
                // ВАЖНО: сохраняем флаг скрытости. Без этого синхронизация с сервера
                // «оживляла» удалённые юзером чаты при следующем запуске приложения.
                isHidden = existingChat?.isHidden ?: false,
                // Роль и epoch — только для групп, для DIRECT сервер даёт пустую строку / 0.
                myRole = dto.myRole ?: "",
                currentEpoch = dto.currentEpoch,
            ))
            // ChatEntity готов — теперь FK chat_members(chatId) удовлетворён.
            // Кешируем состав (upsert юзеров + replaceForChat).
            cacheChatMembers(dto.id, dto.members.orEmpty())
        }

        // После logout/login локальная таблица group_sender_keys пуста. Без
        // префетча первое открытие группы превращается в долгий ре-fetch
        // ключей внутри расшифровки каждого сообщения. Делаем явный proactive
        // pull для всех групп — так история (текст, картинки, голос) сразу
        // расшифровывается, а системные сообщения (которые не шифруются)
        // не остаются «единственным видимым» в чате.
        dtos.filter { it.type == "GROUP" }.forEach { groupDto ->
            runCatching { refreshSenderKeys(groupDto.id) }
                .onFailure { e ->
                    Timber.w(e, "syncChats: refreshSenderKeys failed for group ${groupDto.id}")
                }
        }
    }

    override suspend fun prefetchEmptyChatMessages() {
        // Тянем первую страницу сообщений для чатов, где локально пусто —
        // тогда при тапе на чат он откроется мгновенно, без сетевой задержки.
        // Вызывается из ChatListViewModel в фоне; не блокирует syncChats.
        // Идём по локальному снимку чатов (они уже синхронизированы); делаем
        // последовательно чтобы не превратить старт приложения в шторм
        // параллельных запросов на 20+ чатов.
        val chats = chatDao.getAllSync()
        for (chat in chats) {
            if (messageDao.countByChat(chat.id) == 0) {
                runCatching { fetchMessages(chat.id) }
                    .onFailure { e ->
                        Timber.w(e, "prefetchEmptyChatMessages: fetch failed for ${chat.id}")
                    }
            }
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
        val muted = mutedUntil != null
        chatDao.setMuted(chatId, muted)
        // Дублируем флаг в SharedPreferences, чтобы FcmService мог его прочитать
        // когда приложение убито и DI/Room ещё не подняты.
        MutedChatsPrefs.setMuted(context, chatId, muted)
    }

    /**
     * Удаление чата = soft-delete: помечаем isHidden=1.
     * Без soft-delete syncChats() оживлял бы чат при следующей синхронизации
     * списка с сервера (DELETE /chats endpoint-а нет, сервер ничего не знает
     * о «скрытых» чатах). При получении нового сообщения чат снова показывается —
     * см. IncomingMessageHandler.handle.
     */
    override suspend fun deleteChat(chatId: String): Result<Unit> = runCatching {
        chatDao.hideById(chatId)
    }

    /**
     * Подтягивает свежий профиль пользователя с сервера и апсертит в локальную БД.
     * Сохраняет флаг [isContact] из существующей записи (сервер о нём не знает —
     * контакты хранятся отдельно, добавляются явно через /contacts).
     */
    override suspend fun refreshUserProfile(userId: String): Result<Unit> = runCatching {
        val dto = api.getUserById(userId).data ?: return@runCatching
        val existing = userDao.getById(dto.id)
        userDao.upsert(UserEntity(
            id = dto.id,
            phone = dto.phone,
            username = dto.username,
            displayName = dto.displayName,
            avatarUrl = dto.avatarUrl,
            bio = dto.bio,
            isOnline = dto.isOnline,
            lastSeen = Instant.parse(dto.lastSeen).toEpochMilli(),
            publicKey = dto.publicKey,
            isContact = existing?.isContact ?: false,
            appVersionCode = dto.appVersionCode,
        ))
    }

    // ── Защита от понижения статуса при синхронизации ────────────────────────

    /**
     * Вставляет сообщения, не понижая статус.
     * Порядок приоритетов: SENDING(0) < SENT(1) < DELIVERED(2) < READ(3).
     *
     * ВАЖНО: используем insertAll (одна транзакция) вместо upsert в цикле.
     * Раньше каждый upsert триггерил invalidation Room-observer, что
     * приводило к N re-emit'ам observeAllWithLastMessage и пересборке
     * LazyColumn чатов N раз за один fetchMessages. На префетче пустых
     * чатов это и было причиной «блокировки скролла» на ~секунду:
     * 10 чатов × 50 сообщений = 500 emit'ов подряд.
     */
    private suspend fun upsertWithoutStatusDowngrade(messages: List<MessageEntity>) {
        if (messages.isEmpty()) return
        val existingByIds = messageDao.getByIds(messages.map { it.id })
            .associateBy { it.id }
        val finalList = messages.map { msg ->
            val existing = existingByIds[msg.id] ?: return@map msg
            val localPriority = statusPriority(existing.status)
            val remotePriority = statusPriority(msg.status)
            if (localPriority > remotePriority) msg.copy(status = existing.status) else msg
        }
        messageDao.insertAll(finalList)
    }

    private fun statusPriority(status: String): Int = when (status) {
        "SENDING"   -> 0
        "SENT"      -> 1
        "DELIVERED" -> 2
        "READ"      -> 3
        else        -> 0
    }

    // ── Шифрование ─────────────────────────────────────────────────────────

    /**
     * Результат шифрования сообщения. [usedEpoch] непустой только для GROUP —
     * это epoch sender-ключа, которым реально зашифровано сообщение, и его
     * нужно отправлять серверу в SendMessageRequest.groupEpoch. Получатель
     * читает groupEpoch и использует его для выбора правильного ключа из БД.
     */
    private data class EncryptedPayload(val content: String, val usedEpoch: Int?)

    /** Делегат к [CryptoManager.canonicalMessageAad]. */
    private fun aadFor(chatId: String, senderId: String, messageId: String, type: String): ByteArray =
        cryptoManager.canonicalMessageAad(chatId, senderId, messageId, type)

    private suspend fun encryptForChat(
        chat: ChatEntity,
        content: String,
        messageId: String,
        type: String,
    ): EncryptedPayload {
        val myUserId = currentUserIdSync()
        val aad = aadFor(chat.id, myUserId, messageId, type)
        // Группы (1.0.68): шифруем через свой sender key актуального epoch'а.
        // Если точного ключа под currentEpoch ещё нет (event group_member_added
        // мог запоздать или клиент только что вступил в чат), берём latest —
        // и шлём именно этот epoch в SendMessageRequest.groupEpoch.
        if (chat.type == "GROUP") {
            var myKey = groupSenderKeyDao.get(chat.id, myUserId, chat.currentEpoch)
                ?: groupSenderKeyDao.getLatest(chat.id, myUserId)
            if (myKey == null) {
                // Своего sender key нет вообще — например, клиент только что
                // обновился и пропустил событие group_member_added на старом
                // билде. Лениво генерим свой ключ и рассылаем остальным.
                Timber.w("encryptForChat: my sender key missing for group ${chat.id}, regenerating")
                val others = runCatching {
                    api.getChats().data.orEmpty()
                        .firstOrNull { it.id == chat.id }?.members.orEmpty()
                        .filter { it.id != myUserId }
                        .map { it.toDomain() }
                }.getOrDefault(emptyList())
                runCatching {
                    initializeSenderKey(chat.id, chat.currentEpoch, myUserId, others)
                }.onFailure { e ->
                    Timber.e(e, "encryptForChat: lazy senderKey generation failed")
                }
                myKey = groupSenderKeyDao.get(chat.id, myUserId, chat.currentEpoch)
                    ?: groupSenderKeyDao.getLatest(chat.id, myUserId)
                    ?: error("Не удалось сгенерировать sender key для группы ${chat.id}")
            }
            val senderKeyBytes = Base64.decode(myKey.senderKey, Base64.NO_WRAP)
            val ciphertext = groupCryptoManager.encryptGroupMessage(content, senderKeyBytes, messageId, aad)
            return EncryptedPayload(ciphertext, myKey.epoch)
        }

        val myPrivateKeyBase64 = localKeyStore.getPrivateKey() ?: error("No local private key")
        val myPrivateKeyBytes = Base64.decode(myPrivateKeyBase64, Base64.NO_WRAP)

        val otherUserId = chat.otherUserId ?: error("DIRECT chat without otherUserId")
        val otherUser = userDao.getById(otherUserId) ?: error("Recipient not found: $otherUserId")
        val theirPublicKeyBytes = Base64.decode(otherUser.publicKey, Base64.NO_WRAP)

        val sharedSecret = cryptoManager.computeSharedSecret(myPrivateKeyBytes, theirPublicKeyBytes)
        val ciphertext = cryptoManager.encryptMessage(content, sharedSecret, messageId, aad)
        return EncryptedPayload(ciphertext, null)
    }

    /**
     * Пробует расшифровать групповое сообщение, используя sender-ключ
     * владельца из локальной БД. Если ключ отсутствует или не подошёл —
     * тянет свежие sender keys с сервера и повторяет попытку.
     * Возвращает null если расшифровать не удалось.
     */
    private suspend fun tryDecryptGroupMessage(
        chatId: String,
        senderId: String,
        messageId: String,
        encryptedContent: String,
        aad: ByteArray? = null,
    ): String? {
        // Берём ВСЕ имеющиеся sender keys этого owner-а в чате и пробуем
        // по очереди (новые сначала). Сообщение могло быть зашифровано
        // под любым историческим epoch — особенно после logout/login, когда
        // мы повторно стянули свежие ключи, а старые могли пропасть.
        //
        // ВАЖНО: тут НЕ дёргаем refreshSenderKeys на промах. Раньше при
        // открытии группы с N не-расшифровываемых локально сообщений мы
        // делали N последовательных HTTP-запросов внутри цикла дешифровки —
        // именно это давало «чат долго что-то подгружает». Теперь fetchMessages
        // делает ОДИН bulk-refresh заранее перед циклом.
        for (key in groupSenderKeyDao.listByOwner(chatId, senderId)) {
            val bytes = runCatching {
                Base64.decode(key.senderKey, Base64.NO_WRAP)
            }.getOrNull() ?: continue
            val text = groupCryptoManager.decryptGroupMessage(encryptedContent, bytes, messageId, aad)
            if (text != null) return text
        }
        return null
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
        },
        isOtherOnline = otherIsOnline == true,
    )

    private fun ChatEntity.toChat(lastMessage: Message? = null, isOtherOnline: Boolean = false) = Chat(
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
        isOtherOnline = isOtherOnline,
        myRole = ChatRole.parse(myRole),
        currentEpoch = currentEpoch,
    )

    // ── Группы (1.0.68) ──────────────────────────────────────────────────────

    /**
     * Список участников группы с ролями. Сервер не даёт endpoint'а
     * `GET /chats/{id}` — тянем полный список чатов и находим нужный.
     * Лишний трафик приемлем для экрана GroupInfo, который открывается
     * нечасто; кешированные данные не отдаём, чтобы роли и состав были
     * актуальны после ротаций.
     */
    override fun observeGroupMembers(chatId: String) =
        chatMemberDao.observeMembersWithUser(chatId).map { rows ->
            rows.map { r ->
                User(
                    id = r.id,
                    phone = r.phone,
                    username = r.username,
                    displayName = r.displayName,
                    avatarUrl = r.avatarUrl,
                    bio = r.bio,
                    isOnline = r.isOnline,
                    lastSeen = r.lastSeen,
                    publicKey = r.publicKey,
                    isContact = r.isContact,
                    groupRole = ChatRole.parse(r.role),
                    appVersionCode = r.appVersionCode,
                )
            }
        }

    override fun observeGroupMemberCount(chatId: String) =
        chatMemberDao.observeMemberCount(chatId)

    override suspend fun getGroupMembers(chatId: String): List<User> {
        // Дёргаем endpoint конкретного чата вместо тяжёлого /chats со всем списком —
        // на устройствах с десятком групп GroupInfo раньше открывался с заметной
        // задержкой именно из-за этого.
        val chat = api.getChatById(chatId).data ?: return emptyList()
        val members = chat.members.orEmpty()
        // Кешируем result в local DB. UI, подписанный на observeGroupMembers,
        // получит обновление автоматически — не нужно вручную возвращать
        // что-либо из этого метода для рендера.
        cacheChatMembers(chatId, members)
        return members.map { it.toDomain() }
    }

    /**
     * Сохраняет состав чата (server snapshot) в local DB:
     *  - upsert каждого участника в users (имя, аватар, online, publicKey);
     *  - replaceForChat в chat_members (chatId, userId, role) — с удалением
     *    тех, кого нет в свежем snapshot (обработка кика, который пропустили).
     * Идемпотентно. Вызываем из всех мест где сервер вернул members:
     * createGroupChat, syncChats, getGroupMembers, addGroupMember, и т.д.
     */
    private suspend fun cacheChatMembers(
        chatId: String,
        members: List<com.secure.messenger.data.remote.api.dto.UserDto>,
    ) {
        if (members.isEmpty()) {
            // Сервер не вернул members — для DIRECT это норма (поле пустое
            // в большинстве ответов), а для GROUP это бы означало «состав
            // обнулился», что без подтверждения не делаем (могло быть просто
            // не отдано в этом ответе).
            return
        }
        // Batch-upsert: одна транзакция вместо N отдельных. Иначе каждый
        // upsert триггерит invalidation Room-observer'ов и UI пересобирается
        // N раз. На syncChats с 20 чатами × 4 участника это давало 80 emit'ов
        // подряд, что заметно дёргало список чатов.
        val existingByIds = userDao.getByIds(members.map { it.id }).associateBy { it.id }
        val users = members.map { dto ->
            UserEntity(
                id = dto.id, phone = dto.phone, username = dto.username,
                displayName = dto.displayName, avatarUrl = dto.avatarUrl,
                bio = dto.bio, isOnline = dto.isOnline,
                lastSeen = Instant.parse(dto.lastSeen).toEpochMilli(),
                publicKey = dto.publicKey,
                isContact = existingByIds[dto.id]?.isContact ?: false,
                appVersionCode = dto.appVersionCode,
            )
        }
        userDao.upsertAll(users)
        chatMemberDao.replaceForChat(
            chatId = chatId,
            members = members.map { dto ->
                ChatMemberEntity(
                    chatId = chatId,
                    userId = dto.id,
                    role = dto.role ?: ChatRole.MEMBER.name,
                )
            },
        )
    }

    override suspend fun updateGroupTitle(chatId: String, newTitle: String): Result<Unit> = runCatching {
        api.updateGroupInfo(chatId, UpdateGroupInfoDto(title = newTitle))
        chatDao.getById(chatId)?.let { existing ->
            chatDao.update(existing.copy(title = newTitle))
        }
    }

    override suspend fun updateGroupAvatar(
        chatId: String,
        imageBytes: ByteArray,
        mime: String,
    ): Result<String> = runCatching {
        // Расширение по MIME — сервер всё равно перепроверит по содержимому,
        // а нам это нужно чтобы header.Filename имел корректное расширение
        // (сервер reject'ит, если оно подозрительно — см. UploadAvatar).
        val ext = when (mime) {
            "image/png"  -> "png"
            "image/webp" -> "webp"
            else         -> "jpg"
        }
        val requestBody = imageBytes.toRequestBody(
            mime.toMediaTypeOrNull() ?: "image/jpeg".toMediaType(),
        )
        val part = okhttp3.MultipartBody.Part.createFormData(
            name = "avatar",
            filename = "avatar.$ext",
            body = requestBody,
        )
        val newUrl = api.uploadGroupAvatar(chatId, part).data?.avatarUrl
            ?: error("Server returned null avatar url")

        // Сразу обновляем локальный ChatEntity, чтобы UI показал новый аватар
        // не дожидаясь WS-события group_info_updated (оно тоже придёт, но
        // оптимистичный апдейт убирает мерцание заглушки).
        chatDao.getById(chatId)?.let { existing ->
            chatDao.update(existing.copy(avatarUrl = newUrl))
        }
        newUrl
    }

    override suspend fun addGroupMember(chatId: String, userId: String): Result<Unit> = runCatching {
        val resp = api.addGroupMember(chatId, AddMemberDto(userId = userId)).data
            ?: error("addGroupMember: server returned null")
        val newEpoch = resp.epoch

        // Обновляем локально текущий epoch чата.
        chatDao.getById(chatId)?.let { existing ->
            chatDao.update(existing.copy(currentEpoch = newEpoch))
        }

        // Ротация: инициатор (я) должен выложить свой sender key для нового
        // epoch'а всем участникам, включая нового. Остальные участники
        // ротируют свои ключи на своей стороне в ответ на WS-событие
        // group_member_added (см. IncomingMessageHandler).
        // Тянем именно конкретный чат (а не /chats со всем списком —
        // лишний трафик), и заодно кешируем свежий состав в local DB.
        val myUserId = currentUserIdSync()
        val freshMembers = api.getChatById(chatId).data?.members.orEmpty()
        cacheChatMembers(chatId, freshMembers)
        val members = freshMembers
            .filter { it.id != myUserId }
            .map { it.toDomain() }
        initializeSenderKey(chatId, newEpoch, myUserId, members)

        // Расшарить новенькому свою историю — все мои sender keys прошлых
        // epoch'ов, чтобы он смог расшифровать все мои старые сообщения.
        // Чужие ключи мы шарить не можем (мы их не выдавали и не должны
        // дарить за других). Каждый существующий участник делает то же
        // самое в ответ на group_member_added — см. handleGroupMemberAdded.
        runCatching {
            shareHistoricalKeysWithNewMember(chatId, userId)
        }.onFailure { e ->
            Timber.w(e, "addGroupMember: failed to share historical keys with $userId")
        }
    }

    override suspend fun shareHistoryWithNewMember(
        chatId: String,
        newMemberId: String,
    ): Result<Unit> = runCatching {
        shareHistoricalKeysWithNewMember(chatId, newMemberId)
    }

    // ── Групповые звонки ────────────────────────────────────────────────────

    override suspend fun startGroupCall(
        chatId: String,
        isVideo: Boolean,
        inviteUserIds: List<String>?,
    ): Result<com.secure.messenger.data.remote.api.dto.GroupCallDto> = runCatching {
        api.startGroupCall(
            chatId,
            com.secure.messenger.data.remote.api.dto.StartGroupCallDto(
                type = if (isVideo) "VIDEO" else "AUDIO",
                inviteUserIds = inviteUserIds,
            ),
        ).data ?: error("startGroupCall: server returned null")
    }

    override suspend fun getActiveGroupCall(
        chatId: String,
    ): Result<com.secure.messenger.data.remote.api.dto.GroupCallDto?> = runCatching {
        api.getActiveGroupCall(chatId).data
    }

    override suspend fun joinGroupCall(
        callId: String,
    ): Result<com.secure.messenger.data.remote.api.dto.GroupCallDto> = runCatching {
        api.joinGroupCall(callId).data ?: error("joinGroupCall: null")
    }

    override suspend fun leaveGroupCall(callId: String): Result<Unit> = runCatching {
        api.leaveGroupCall(callId)
        Unit
    }

    /**
     * Делится своими старыми sender keys с новым участником группы.
     * Берёт ВСЕ свои sender keys из локальной БД (по всем epoch'ам), оборачивает
     * каждый своим privKey + новый pubKey участника, заливает на сервер с
     * recipientId = новый_участник. Тот при следующем refreshSenderKeys
     * получит обёртки и сможет расшифровать историю моих сообщений.
     *
     * Симметричная операция выполняется на каждом клиенте при получении
     * WS-события group_member_added: «новенький в группе — поделюсь и я».
     */
    private suspend fun shareHistoricalKeysWithNewMember(chatId: String, newMemberId: String) {
        val myUserId = currentUserIdSync()
        if (newMemberId == myUserId) return // самому себе шарить нечего

        val myKeys = groupSenderKeyDao.listByOwner(chatId, myUserId)
        if (myKeys.isEmpty()) return

        val newMember = userDao.getById(newMemberId) ?: run {
            // Подтянуть свежий профиль если в локальной БД нет
            val dto = runCatching { api.getUserById(newMemberId).data }.getOrNull()
                ?: return
            UserEntity(
                id = dto.id, phone = dto.phone, username = dto.username,
                displayName = dto.displayName, avatarUrl = dto.avatarUrl,
                bio = dto.bio, isOnline = dto.isOnline,
                lastSeen = Instant.parse(dto.lastSeen).toEpochMilli(),
                publicKey = dto.publicKey, isContact = false,
            ).also { userDao.upsert(it) }
        }

        val newMemberPubKey = runCatching {
            Base64.decode(newMember.publicKey, Base64.NO_WRAP)
        }.getOrNull()
        if (newMemberPubKey == null || newMemberPubKey.size != 32) {
            Timber.w("shareHistoricalKeys: invalid pubKey for $newMemberId")
            return
        }

        val myPrivateKey = localKeyStore.getPrivateKey()?.let {
            Base64.decode(it, Base64.NO_WRAP)
        } ?: return

        // Группируем по epoch — uploadSenderKeys принимает ОДИН epoch на запрос.
        // Старые epoch'и могут различаться между ключами, поэтому шлём отдельно.
        val byEpoch = myKeys.groupBy { it.epoch }
        for ((epoch, keys) in byEpoch) {
            val key = keys.first()
            val senderKeyBytes = runCatching {
                Base64.decode(key.senderKey, Base64.NO_WRAP)
            }.getOrNull() ?: continue
            val wrapped = groupCryptoManager.wrapSenderKey(
                senderKey = senderKeyBytes,
                recipientPublicKey = newMemberPubKey,
                myPrivateKey = myPrivateKey,
                chatId = chatId,
            )
            val entry = SenderKeyEntryDto(
                recipientId = newMemberId,
                epoch = epoch,
                encryptedKey = wrapped,
            )
            runCatching {
                api.uploadSenderKeys(
                    chatId,
                    UploadSenderKeysDto(epoch = epoch, entries = listOf(entry)),
                )
            }.onFailure { e ->
                Timber.w(e, "shareHistoricalKeys: upload epoch=$epoch failed")
            }
        }
        Timber.d("shareHistoricalKeys: shared ${byEpoch.size} epochs with $newMemberId")
    }

    override suspend fun removeGroupMember(chatId: String, userId: String): Result<Unit> = runCatching {
        val resp = api.removeGroupMember(chatId, userId).data
            ?: error("removeGroupMember: server returned null")
        val newEpoch = resp.epoch

        chatDao.getById(chatId)?.let { existing ->
            chatDao.update(existing.copy(currentEpoch = newEpoch))
        }
        // Локально убираем кикнутого из chat_members сразу — UI GroupInfo
        // обновится без ожидания сети. Свежий состав потом перезатрётся
        // при cacheChatMembers ниже.
        chatMemberDao.delete(chatId, userId)
        // Старый sender key кикнутого локально удаляем — он больше не
        // должен расшифровывать никакие новые сообщения.
        groupSenderKeyDao.deleteByOwner(chatId, userId)

        // Ротируем свой ключ для нового epoch'а.
        val myUserId = currentUserIdSync()
        val freshMembers = api.getChatById(chatId).data?.members.orEmpty()
        cacheChatMembers(chatId, freshMembers)
        val members = freshMembers
            .filter { it.id != myUserId }
            .map { it.toDomain() }
        initializeSenderKey(chatId, newEpoch, myUserId, members)
    }

    override suspend fun leaveGroup(chatId: String): Result<Unit> = runCatching {
        api.leaveGroup(chatId)
        // Независимо от того удалилась группа целиком или передался CREATOR —
        // у меня её больше нет, чистим локально.
        groupSenderKeyDao.deleteByChat(chatId)
        chatMemberDao.deleteAllForChat(chatId)
        chatDao.deleteById(chatId)
    }

    override suspend fun changeGroupRole(
        chatId: String,
        userId: String,
        role: ChatRole,
    ): Result<Unit> = runCatching {
        require(role == ChatRole.ADMIN || role == ChatRole.MEMBER) {
            "changeGroupRole: only ADMIN or MEMBER allowed"
        }
        api.changeGroupRole(chatId, userId, ChangeRoleDto(role = role.name))
        // Оптимистичное локальное обновление — UI GroupInfo сразу покажет
        // новую роль, не ждём WS-event group_role_changed.
        chatMemberDao.upsert(ChatMemberEntity(chatId, userId, role.name))
    }

    override suspend fun transferGroupOwnership(
        chatId: String,
        newOwnerId: String,
    ): Result<Unit> = runCatching {
        api.transferGroupOwnership(
            chatId,
            com.secure.messenger.data.remote.api.dto.TransferOwnershipDto(userId = newOwnerId),
        )
    }

    override suspend fun deleteGroup(chatId: String): Result<Unit> = runCatching {
        api.deleteGroup(chatId)
        // Сервер каскадом удалит сообщения и sender-ключи. Локально тоже чистим
        // чтобы экран обновился сразу, не дожидаясь следующего syncChats.
        groupSenderKeyDao.deleteByChat(chatId)
        chatMemberDao.deleteAllForChat(chatId)
        chatDao.deleteById(chatId)
    }

    override suspend fun uploadMySenderKey(
        chatId: String,
        epoch: Int,
        recipients: List<User>,
    ): Result<Unit> = runCatching {
        val myUserId = currentUserIdSync()
        initializeSenderKey(chatId, epoch, myUserId, recipients)
    }

    override suspend fun refreshSenderKeys(chatId: String, epoch: Int?): Result<Unit> = runCatching {
        val entries = api.fetchSenderKeys(chatId, epoch).data.orEmpty()
        if (entries.isEmpty()) return@runCatching

        val myPrivateKeyBase64 = localKeyStore.getPrivateKey()
            ?: error("No local private key")
        val myPrivateKey = Base64.decode(myPrivateKeyBase64, Base64.NO_WRAP)

        val now = System.currentTimeMillis()
        for (entry in entries) {
            val ownerId = entry.ownerId ?: continue
            // publicKey владельца берём из локальной БД users (должен быть
            // закеширован при syncChats / createGroupChat). Если нет —
            // догружаем через REST на всякий случай.
            val ownerUser = userDao.getById(ownerId) ?: run {
                runCatching {
                    api.getUserById(ownerId).data?.let { dto ->
                        userDao.upsert(UserEntity(
                            id = dto.id, phone = dto.phone, username = dto.username,
                            displayName = dto.displayName, avatarUrl = dto.avatarUrl,
                            bio = dto.bio, isOnline = dto.isOnline,
                            lastSeen = Instant.parse(dto.lastSeen).toEpochMilli(),
                            publicKey = dto.publicKey, isContact = false,
                        ))
                    }
                }
                userDao.getById(ownerId)
            } ?: continue

            val ownerPublicKey = runCatching {
                Base64.decode(ownerUser.publicKey, Base64.NO_WRAP)
            }.getOrNull() ?: continue
            if (ownerPublicKey.size != 32) continue

            val senderKeyBytes = groupCryptoManager.unwrapSenderKey(
                encryptedBase64 = entry.encryptedKey,
                ownerPublicKey = ownerPublicKey,
                myPrivateKey = myPrivateKey,
                chatId = chatId,
            ) ?: continue

            groupSenderKeyDao.upsert(GroupSenderKeyEntity(
                chatId = chatId,
                ownerId = ownerId,
                epoch = entry.epoch,
                senderKey = Base64.encodeToString(senderKeyBytes, Base64.NO_WRAP),
                createdAt = now,
            ))
        }
    }

    // ── Внутренние helpers для sender keys ───────────────────────────────────

    /**
     * Генерирует (или достаёт из БД) мой sender key для [chatId]/[epoch],
     * шифрует его для каждого [recipients] и заливает на сервер. Свой ключ
     * сохраняется локально, чтобы использовать при отправке сообщений.
     *
     * Идемпотентно: если ключ уже есть в локальной БД — не перегенерируем,
     * просто (пере-)выкладываем для recipients (на случай новых участников).
     */
    private suspend fun initializeSenderKey(
        chatId: String,
        epoch: Int,
        myUserId: String,
        recipients: List<User>,
    ) {
        val existing = groupSenderKeyDao.get(chatId, myUserId, epoch)
        val senderKeyBytes: ByteArray = if (existing != null) {
            Base64.decode(existing.senderKey, Base64.NO_WRAP)
        } else {
            groupCryptoManager.generateSenderKey().also { fresh ->
                groupSenderKeyDao.upsert(GroupSenderKeyEntity(
                    chatId = chatId,
                    ownerId = myUserId,
                    epoch = epoch,
                    senderKey = Base64.encodeToString(fresh, Base64.NO_WRAP),
                    createdAt = System.currentTimeMillis(),
                ))
            }
        }

        val myPrivateKeyBase64 = localKeyStore.getPrivateKey()
            ?: error("No local private key")
        val myPrivateKey = Base64.decode(myPrivateKeyBase64, Base64.NO_WRAP)
        val myPublicKeyBase64 = localKeyStore.getPublicKey()
        val myPublicKey = myPublicKeyBase64?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        }

        // Помимо других участников всегда добавляем wrap'нутый ключ
        // ДЛЯ САМОГО СЕБЯ — иначе после logout/login локальная БД пуста,
        // refreshSenderKeys получает только чужие ключи, и собственные
        // отправленные сообщения не расшифровываются (нечем). Wrap'аем
        // через ECDH(myPriv, myPub) — тот же канал что для других.
        val selfEntry: SenderKeyEntryDto? = if (myPublicKey != null && myPublicKey.size == 32) {
            val wrappedSelf = groupCryptoManager.wrapSenderKey(
                senderKey = senderKeyBytes,
                recipientPublicKey = myPublicKey,
                myPrivateKey = myPrivateKey,
                chatId = chatId,
            )
            SenderKeyEntryDto(
                recipientId = myUserId,
                epoch = epoch,
                encryptedKey = wrappedSelf,
            )
        } else {
            Timber.w("initializeSenderKey: my own public key missing — self-wrap skipped")
            null
        }

        val recipientEntries = recipients.mapNotNull { recipient ->
            val pubKey = runCatching {
                Base64.decode(recipient.publicKey, Base64.NO_WRAP)
            }.getOrNull()
            if (pubKey == null || pubKey.size != 32) {
                Timber.w("initializeSenderKey: invalid public key for ${recipient.id}")
                return@mapNotNull null
            }
            val wrapped = groupCryptoManager.wrapSenderKey(
                senderKey = senderKeyBytes,
                recipientPublicKey = pubKey,
                myPrivateKey = myPrivateKey,
                chatId = chatId,
            )
            SenderKeyEntryDto(
                recipientId = recipient.id,
                epoch = epoch,
                encryptedKey = wrapped,
            )
        }

        val entries = (listOfNotNull(selfEntry) + recipientEntries)
        if (entries.isNotEmpty()) {
            api.uploadSenderKeys(chatId, UploadSenderKeysDto(epoch = epoch, entries = entries))
        }
    }

    companion object {
        // Совпадает с дефолтом сервера (см. messageRepo.List). Если сервер
        // вернёт меньше — значит вся история чата помещается в одну страницу.
        private const val INITIAL_FETCH_LIMIT = 50
    }
}

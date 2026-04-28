package com.secure.messenger.domain.repository

import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.ChatRole
import com.secure.messenger.domain.model.Message
import com.secure.messenger.domain.model.User
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun observeChats(): Flow<List<Chat>>
    fun observeMessages(chatId: String, limit: Int = 50): Flow<List<Message>>

    suspend fun getOrCreateDirectChat(userId: String): Result<Chat>
    /**
     * Создаёт групповой чат. Creator (текущий пользователь) автоматически
     * получает роль CREATOR. Одновременно генерирует и распределяет sender-ключи:
     * свой ключ для каждого участника зашифрован отдельно (X25519 + AES-GCM).
     */
    suspend fun createGroupChat(title: String, memberIds: List<String>): Result<Chat>

    suspend fun sendMessage(chatId: String, content: String, replyToId: String? = null): Result<Message>

    /**
     * Отправляет голосовое сообщение. Файлы не хранятся на сервере отдельно — байты
     * шифруются и передаются в encryptedContent (base64) обычным сообщением с типом AUDIO.
     * Параметр [waveform] — амплитуды для отображения волнограммы в плеере.
     */
    suspend fun sendVoiceMessage(
        chatId: String,
        audioBytes: ByteArray,
        durationSeconds: Int,
        waveform: IntArray = IntArray(0),
    ): Result<Message>

    /**
     * Отправляет картинку. Аналогично голосовому: байты ужимаются (JPEG ≤ 1280px),
     * упаковываются в JSON, шифруются и идут как обычное сообщение с типом IMAGE.
     * На сервере как файл не хранится.
     */
    suspend fun sendImageMessage(chatId: String, imageData: com.secure.messenger.utils.ImageCodec.ImageData): Result<Message>
    suspend fun deleteMessage(messageId: String): Result<Unit>
    suspend fun editMessage(messageId: String, newContent: String): Result<Message>

    suspend fun syncChats(myUserId: String): Result<Unit>
    /**
     * Тянет последнюю страницу сообщений с сервера. Возвращает `true`, если
     * сервер отдал полную страницу — значит, скорее всего есть ещё более
     * старые сообщения и пагинация имеет смысл. `false` — сервер отдал
     * меньше страницы (или ноль) → старее уже нет, UI может выключить
     * индикатор подгрузки.
     */
    suspend fun fetchMessages(chatId: String): Result<Boolean>

    /**
     * Префетч первой страницы сообщений для всех чатов, где локально пусто.
     * Вызывается фоном после syncChats: следующий тап на любой чат сразу
     * показывает историю, не ждёт сетевой fetchMessages. Идёт последовательно
     * (а не параллельно) — не хочется ронять сеть N параллельными
     * /v1/chats/{id}/messages при 20+ чатов.
     */
    suspend fun prefetchEmptyChatMessages()
    suspend fun fetchOlderMessages(chatId: String, beforeTimestamp: Long): Result<Boolean>

    suspend fun markAsRead(chatId: String): Result<Unit>
    suspend fun pinChat(chatId: String): Result<Unit>
    suspend fun muteChat(chatId: String, mutedUntil: Long?): Result<Unit>
    suspend fun deleteChat(chatId: String): Result<Unit>

    /**
     * Подтягивает с сервера актуальный профиль пользователя (включая isOnline
     * и lastSeen) и обновляет локальную БД. Используется при открытии чата
     * как страховка от ситуаций, когда WS-событие user_status могло быть
     * пропущено (например, собеседник только что перезашёл после долгого
     * отсутствия, а мы были оффлайн в тот момент).
     */
    suspend fun refreshUserProfile(userId: String): Result<Unit>

    // ── Группы (1.0.68) ──────────────────────────────────────────────────

    /**
     * Реактивный поток состава группы из локальной БД (chat_members JOIN users).
     * Эмитит instantly при подписке — UI рисует список / счётчик из кеша
     * без ожидания сети, а [getGroupMembers] отдельно обновляет данные
     * поверх. Раньше каждое открытие GroupInfoScreen дёргало сеть и
     * флешило спиннер.
     */
    fun observeGroupMembers(chatId: String): kotlinx.coroutines.flow.Flow<List<User>>

    /** Реактивный счётчик участников из локального кеша. */
    fun observeGroupMemberCount(chatId: String): kotlinx.coroutines.flow.Flow<Int>

    /**
     * Сетевой fetch актуального состава группы. Параллельно записывает
     * результат в локальный chat_members — UI, подписанный на
     * [observeGroupMembers], получит обновление автоматически. Возвращает
     * свежий список для совместимости со старыми вызовами.
     */
    suspend fun getGroupMembers(chatId: String): List<User>

    /** Редактирование названия группы. Требует CREATOR/ADMIN. */
    suspend fun updateGroupTitle(chatId: String, newTitle: String): Result<Unit>

    /**
     * Загружает аватар группы (jpeg/png/webp до 5 МБ). Сервер сохраняет файл,
     * возвращает публичный URL и рассылает всем участникам group_info_updated.
     * Локальный ChatEntity.avatarUrl обновляется этим вызовом тоже. Требует
     * CREATOR/ADMIN.
     */
    suspend fun updateGroupAvatar(chatId: String, imageBytes: ByteArray, mime: String): Result<String>

    /**
     * Добавление участника. Требует CREATOR/ADMIN. Клиент после успеха
     * обязан ротировать свой sender key (новый epoch) и выложить его для
     * всех участников (включая нового) через [uploadMySenderKey].
     */
    suspend fun addGroupMember(chatId: String, userId: String): Result<Unit>

    /** Кик участника. Требует CREATOR/ADMIN. */
    suspend fun removeGroupMember(chatId: String, userId: String): Result<Unit>

    /**
     * Добровольный выход. Сервер применяет каскадную передачу CREATOR.
     * Если группа удалена (последний ушёл) — локально тоже чистим.
     */
    suspend fun leaveGroup(chatId: String): Result<Unit>

    /**
     * Меняет роль участника между ADMIN и MEMBER. Доступно CREATOR'у и
     * любому ADMIN'у; ADMIN не может понижать другого ADMIN'a (сервер
     * вернёт 403). CREATOR-роль через этот метод установить нельзя — для
     * передачи владельца есть [transferGroupOwnership].
     */
    suspend fun changeGroupRole(chatId: String, userId: String, role: ChatRole): Result<Unit>

    /**
     * Передача роли CREATOR другому участнику. Только текущий CREATOR
     * может вызвать. После успеха он сам становится ADMIN'ом.
     */
    suspend fun transferGroupOwnership(chatId: String, newOwnerId: String): Result<Unit>

    /**
     * Удаление группы. Доступно только CREATOR'у. Сервер удаляет чат
     * каскадом (members, messages, sender-keys) и шлёт всем участникам
     * WS-событие group_deleted. Локально чат и сообщения тоже чистим.
     */
    suspend fun deleteGroup(chatId: String): Result<Unit>

    /**
     * Шарит со свежедобавленным участником группы СВОИ старые sender keys
     * (всех epoch'ов). После этого новый участник сможет расшифровать
     * историю моих сообщений в группе. Чужие ключи не шарим.
     *
     * Вызывается на каждом клиенте (адресате group_member_added для других),
     * чтобы новенький получил историю всех существующих участников.
     */
    suspend fun shareHistoryWithNewMember(chatId: String, newMemberId: String): Result<Unit>

    // ── Групповые звонки (1.0.71+) ──────────────────────────────────────────

    /**
     * Старт группового звонка в чате. Если уже идёт — возвращает существующий.
     * [inviteUserIds] — кому показать full-screen ringing. null = всем участникам.
     */
    suspend fun startGroupCall(
        chatId: String,
        isVideo: Boolean,
        inviteUserIds: List<String>? = null,
    ): Result<com.secure.messenger.data.remote.api.dto.GroupCallDto>

    /** Узнать идёт ли сейчас групповой звонок в чате. null → не идёт. */
    suspend fun getActiveGroupCall(chatId: String): Result<com.secure.messenger.data.remote.api.dto.GroupCallDto?>

    /** Присоединиться к идущему групповому звонку. */
    suspend fun joinGroupCall(callId: String): Result<com.secure.messenger.data.remote.api.dto.GroupCallDto>

    /** Выйти из группового звонка. */
    suspend fun leaveGroupCall(callId: String): Result<Unit>

    /**
     * Генерирует свой sender key для группы (или берёт существующий
     * из локальной БД по [epoch], если он уже есть), шифрует его для
     * каждого участника и заливает на сервер. [recipients] — список
     * участников, которым нужно выдать ключ (исключая себя).
     */
    suspend fun uploadMySenderKey(
        chatId: String,
        epoch: Int,
        recipients: List<User>,
    ): Result<Unit>

    /**
     * Скачивает sender-ключи, адресованные текущему пользователю,
     * расшифровывает и записывает в локальную БД. [epoch] — если задан,
     * тянутся только записи этого epoch'а, иначе все.
     */
    suspend fun refreshSenderKeys(chatId: String, epoch: Int? = null): Result<Unit>
}

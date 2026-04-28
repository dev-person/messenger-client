package com.secure.messenger.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.secure.messenger.data.local.dao.UserDao
import com.secure.messenger.data.remote.websocket.SignalingClient
import com.secure.messenger.data.remote.websocket.SignalingEvent
import com.secure.messenger.utils.SoundManager
import com.secure.messenger.utils.VoiceRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.ChatType
import com.secure.messenger.domain.model.Message
import com.secure.messenger.domain.model.User
import com.secure.messenger.domain.repository.AuthRepository
import com.secure.messenger.domain.repository.ChatRepository
import com.secure.messenger.domain.usecase.SendMessageUseCase
import com.secure.messenger.utils.TextEnhancer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class ChatUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val editingMessage: Message? = null,
    val replyingTo: Message? = null,
    val isLoadingOlder: Boolean = false,
    /**
     * true пока init-fetch первой страницы сообщений не завершён И в локальном
     * кеше пусто. UI в этом окне рисует skeleton вместо плашки «нет сообщений»,
     * иначе при открытии чата с холодным кешем мелькает фолбэк-пустота.
     */
    val isInitialLoading: Boolean = true,
    // По умолчанию считаем что старее нет — иначе при открытии чата с малой
    // историей предикат пагинации `lastVisible >= size-5` неизбежно срабатывает
    // ДО того, как асинхронный fetchMessages успеет выставить флаг в false,
    // и юзер видит ложный спиннер «загружаю старые». Фетч-цикл в init
    // включит флаг обратно, если сервер реально вернул полную страницу.
    val hasOlderMessages: Boolean = false,
    val showEmojiPicker: Boolean = false,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext context: Context,
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val sendMessageUseCase: SendMessageUseCase,
    authRepository: AuthRepository,
    private val userDao: UserDao,
    private val messageDao: com.secure.messenger.data.local.dao.MessageDao,
    private val callRepository: com.secure.messenger.domain.repository.CallRepository,
    private val signalingClient: SignalingClient,
    private val soundManager: SoundManager,
    private val localKeyStore: com.secure.messenger.utils.LocalKeyStore,
) : ViewModel() {

    private val voiceRecorder = VoiceRecorder(context)

    private val chatId: String = checkNotNull(savedStateHandle["chatId"])
    private val displayLimit = MutableStateFlow(PAGE_SIZE)

    val currentUserId: StateFlow<String?> = authRepository.currentUser
        .map { it?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Мой публичный ключ — для отображения safety code в профиле собеседника. */
    val myPublicKey: String? get() = localKeyStore.getPublicKey()

    // Список сообщений текущего чата — обновляется в реальном времени через WebSocket
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> = displayLimit
        .flatMapLatest { limit -> chatRepository.observeMessages(chatId, limit) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Метаданные текущего чата (название, аватар, участники) — для отображения в шапке экрана.
    // SharingStarted.Eagerly: flow стартует сразу при создании VM (не ждёт subscriber'а),
    // так что к моменту первой композиции ChatTopBar чат уже подгружен из локальной БД
    // и шапка не мелькает фолбэком «Чат».
    val chatInfo: StateFlow<Chat?> = chatRepository
        .observeChats()
        .map { list -> list.find { it.id == chatId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Активный 1-1 звонок (любой), независимо от того, в каком чате он идёт.
     * Если звонок есть и юзер свернул его back-кнопкой / PiP'ом — ChatScreen
     * показывает плашку «Идёт звонок · вернуться» под своей шапкой.
     */
    val activeCall: StateFlow<com.secure.messenger.domain.model.Call?> =
        callRepository.activeCall
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Активный групповой звонок в этом чате (если идёт). Используется для
     * плашки «Идёт звонок · присоединиться» над списком сообщений.
     * null = никакого звонка нет.
     */
    private val _activeGroupCall = MutableStateFlow<com.secure.messenger.data.remote.api.dto.GroupCallDto?>(null)
    val activeGroupCall: StateFlow<com.secure.messenger.data.remote.api.dto.GroupCallDto?> =
        _activeGroupCall.asStateFlow()

    init {
        // Подтягиваем актуальное состояние при открытии чата
        viewModelScope.launch {
            runCatching { chatRepository.getActiveGroupCall(chatId).getOrNull() }
                .onSuccess { _activeGroupCall.value = it }
        }
        // Слушаем WS-события — invite/ended — чтобы плашка появлялась/исчезала live
        viewModelScope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is com.secure.messenger.data.remote.websocket.SignalingEvent.GroupCallInvite ->
                        if (event.chatId == chatId) {
                            // Подтягиваем полный объект с участниками
                            runCatching {
                                chatRepository.getActiveGroupCall(chatId).getOrNull()
                            }.onSuccess { _activeGroupCall.value = it }
                        }
                    is com.secure.messenger.data.remote.websocket.SignalingEvent.GroupCallEnded ->
                        if (_activeGroupCall.value?.id == event.callId) {
                            _activeGroupCall.value = null
                        }
                    else -> Unit
                }
            }
        }
    }

    // Онлайн-статус собеседника — реактивно из Room (обновляется через WebSocket user_status)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isOtherUserOnline: StateFlow<Boolean> = chatInfo
        .flatMapLatest { chat ->
            val otherId = chat?.otherUserId
            if (otherId != null && chat.type == ChatType.DIRECT) {
                userDao.observeById(otherId).map { it?.isOnline == true }
            } else {
                flowOf(false)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Данные о собеседнике — для диалога профиля
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val otherUser: StateFlow<User?> = chatInfo
        .flatMapLatest { chat ->
            val otherId = chat?.otherUserId
            if (otherId != null && chat.type == ChatType.DIRECT) {
                userDao.observeById(otherId).map { it?.toDomain() }
            } else {
                flowOf(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Минимальная версия клиента, поддерживающая групповую E2E (sender-keys).
     * 0 у пользователя = неизвестно (никогда не отчитался) — не считаем устаревшим.
     */
    private val MIN_GROUP_VERSION_CODE = 68

    /**
     * Список участников группового чата. Подгружается с сервера при открытии
     * (один раз) и пересчитывается при изменении состава — через слежение за
     * `chatInfo.currentEpoch` (epoch инкрементится сервером при add/remove
     * /leave, так что любой такой реакт автоматически перетянет свежий состав).
     * Для DIRECT-чата — пустой список.
     */
    private val _groupMembers = MutableStateFlow<List<User>>(emptyList())
    val groupMembers: StateFlow<List<User>> = _groupMembers.asStateFlow()

    init {
        // Список участников читаем реактивно из локального кеша (chat_members
        // JOIN users) — UI получает данные мгновенно при открытии чата, без
        // ожидания сети. Сетевой refresh запускаем отдельно и без UI-спиннера:
        // его результат сам прольётся в Flow через chatMemberDao.
        viewModelScope.launch {
            chatRepository.observeGroupMembers(chatId).collect { members ->
                _groupMembers.value = members
            }
        }
        viewModelScope.launch {
            chatInfo
                .filterNotNull()
                .filter { it.type == ChatType.GROUP }
                .distinctUntilChangedBy { it.id to it.currentEpoch }
                .collect { chat ->
                    runCatching { chatRepository.getGroupMembers(chat.id) }
                        .onFailure { e ->
                            Timber.w(e, "ChatViewModel: refresh group members failed")
                        }
                }
        }
    }

    /**
     * Есть ли в группе участники с устаревшей версией клиента (versionCode < 68),
     * которые не смогут расшифровать sender-keys. Для DIRECT всегда false.
     */
    val hasOutdatedGroupMembers: StateFlow<Boolean> = _groupMembers
        .map { members -> members.any { it.appVersionCode in 1 until MIN_GROUP_VERSION_CODE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Кол-во участников группы онлайн (без меня). Реактивно отслеживает изменения
     * `users.isOnline` (которые приходят через WS-событие `user_status` →
     * `IncomingMessageHandler.handleUserStatus`).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val groupOnlineCount: StateFlow<Int> = _groupMembers
        .flatMapLatest { members ->
            if (members.isEmpty()) flowOf(0)
            else {
                val ids = members.map { it.id }
                userDao.observeByIds(ids).map { entities ->
                    val myId = currentUserId.value
                    entities.count { it.isOnline && it.id != myId }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Кто сейчас печатает в группе — userId → displayName. Сбрасывается через
     * 3 секунды после последнего события Typing от каждого юзера. UI-компонент
     * сам решает как компактно отобразить (1 имя / «X и ещё N»).
     */
    private val _groupTypingNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val groupTypingNames: StateFlow<Map<String, String>> = _groupTypingNames.asStateFlow()
    private val typingResetJobs = mutableMapOf<String, Job>()

    // Индикатор «печатает…»
    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()
    private var typingResetJob: Job? = null

    // Дебаунс отправки typing: не чаще раза в 2 секунды
    private var typingSentAt = 0L

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    /**
     * Память «этот чат точно пустой» — флаг ставится после успешного
     * fetchMessages вернувшего 0 сообщений, чистится при первом непустом
     * messages.collect. Без него юзер при каждом повторном открытии пустого
     * чата видел skeleton ~800мс пока сеть не вернула очередной []. Теперь
     * skeleton показываем только при первом холодном открытии.
     */
    private val knownEmptyPrefs = context.getSharedPreferences(
        "chats_known_empty", Context.MODE_PRIVATE,
    )
    private val isKnownEmpty: Boolean = knownEmptyPrefs.getBoolean(chatId, false)

    init {
        // Если уже знаем что чат пустой — не показываем skeleton, сразу
        // EmptyChatHint. Это убирает мерцание при повторных открытиях.
        if (isKnownEmpty) {
            _uiState.value = _uiState.value.copy(isInitialLoading = false)
        }

        // Загружаем историю сообщений с сервера при открытии чата (+ синхронизация удалений).
        // Если сервер вернул меньше страницы — отключаем флаг hasOlderMessages,
        // чтобы LazyColumn в чатах с малым числом сообщений не показывал ложный
        // спиннер «загружаю старые» при первой же отрисовке.
        viewModelScope.launch {
            chatRepository.fetchMessages(chatId)
                .onSuccess { hasMore ->
                    // Включаем пагинацию только если сервер реально вернул
                    // полную страницу (есть смысл фетчить старее).
                    _uiState.value = _uiState.value.copy(
                        hasOlderMessages = hasMore,
                        isInitialLoading = false,
                    )
                    // Если после успешного fetch'а локально 0 сообщений — чат
                    // действительно пустой, помечаем чтобы при следующем открытии
                    // не светить skeleton'ом.
                    if (messages.value.isEmpty()) {
                        knownEmptyPrefs.edit().putBoolean(chatId, true).apply()
                    }
                }
                .onFailure { e ->
                    Timber.e(e, "fetchMessages failed for chatId=$chatId")
                    _uiState.value = _uiState.value.copy(isInitialLoading = false)
                }
        }
        // Снимаем skeleton как только в БД появилось хотя бы одно сообщение,
        // и тут же снимаем флаг known-empty (оказался не пустой).
        viewModelScope.launch {
            messages.filter { it.isNotEmpty() }.first()
            knownEmptyPrefs.edit().remove(chatId).apply()
            if (_uiState.value.isInitialLoading) {
                _uiState.value = _uiState.value.copy(isInitialLoading = false)
            }
        }
        // Hard timeout: skeleton не должен висеть дольше 800мс. Если сеть
        // тормозит и в кеше пусто — лучше показать EmptyChatHint, чем держать
        // юзера на пустом скелетоне неопределённо долго.
        viewModelScope.launch {
            kotlinx.coroutines.delay(800)
            if (_uiState.value.isInitialLoading) {
                _uiState.value = _uiState.value.copy(isInitialLoading = false)
            }
        }
        // Помечаем все сообщения как прочитанные при открытии чата
        markAsRead()
        observeTyping()
        // При каждом новом сообщении — помечаем как прочитанное (пока чат открыт)
        observeNewMessagesAndMarkRead()
        // Страховочный рефреш профиля собеседника — нужно на случай, если WS-event
        // user_status был пропущен (собеседник перезашёл пока мы были в офлайне).
        refreshOtherUserProfile()
    }

    /**
     * Обновляет профиль собеседника по REST сразу после открытия чата.
     * Работает только для DIRECT-чатов; в групповых пока пропускаем (там
     * статусов много, их подтягивает syncChats целиком).
     */
    private fun refreshOtherUserProfile() {
        viewModelScope.launch {
            // Ждём первое непустое значение chatInfo (у которого уже определён
            // тип и otherUserId) — иначе можем промахнуться по ещё не загруженному
            // из Room chat-entity.
            val chat = chatInfo
                .filter { it != null && (it.type != ChatType.DIRECT || it.otherUserId != null) }
                .first() ?: return@launch
            val otherId = chat.otherUserId ?: return@launch
            if (chat.type == ChatType.DIRECT) {
                chatRepository.refreshUserProfile(otherId).onFailure { e ->
                    Timber.w(e, "refreshUserProfile($otherId) failed")
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeNewMessagesAndMarkRead() {
        viewModelScope.launch {
            // Дебаунс 1.5с — без него при 15 upsert-ах подряд (отправка альбома)
            // мы 15 раз дёргали /read → nginx ratelimit → 503 → лавина ошибок.
            messages
                .debounce(1_500)
                .collect { markAsRead() }
        }
    }

    private fun observeTyping() {
        viewModelScope.launch {
            signalingClient.events.collect { event ->
                if (event is SignalingEvent.Typing && event.chatId == chatId) {
                    val chat = chatInfo.value
                    if (chat?.type == ChatType.GROUP) {
                        // Имя берём из локально загруженного списка участников;
                        // если member ещё не подгружен — используем UUID как
                        // фолбэк (UI его не покажет, переключится при следующем
                        // обновлении groupMembers).
                        val name = _groupMembers.value.firstOrNull { it.id == event.userId }
                            ?.displayName?.ifBlank { null }
                            ?: event.userId
                        _groupTypingNames.update { it + (event.userId to name) }
                        typingResetJobs[event.userId]?.cancel()
                        typingResetJobs[event.userId] = viewModelScope.launch {
                            delay(3_000)
                            _groupTypingNames.update { it - event.userId }
                            typingResetJobs.remove(event.userId)
                        }
                    } else {
                        _isTyping.value = true
                        typingResetJob?.cancel()
                        typingResetJob = viewModelScope.launch {
                            delay(3_000)
                            _isTyping.value = false
                        }
                    }
                }
            }
        }
    }

    fun onInputChange(text: String) {
        _inputText.value = text
        if (text.isNotEmpty()) {
            val now = System.currentTimeMillis()
            if (now - typingSentAt > 2_000) {
                typingSentAt = now
                signalingClient.sendTyping(chatId)
            }
        }
    }

    fun sendMessage() {
        val editing = _uiState.value.editingMessage
        val replyTo = _uiState.value.replyingTo
        val content = _inputText.value.trim()
        if (content.isEmpty()) return

        if (editing != null) {
            _inputText.value = ""
            _uiState.value = _uiState.value.copy(editingMessage = null)
            viewModelScope.launch {
                chatRepository.editMessage(editing.id, content)
                    .onFailure { e ->
                        _uiState.value = _uiState.value.copy(error = "Ошибка редактирования: ${e.message}")
                    }
            }
            return
        }

        _inputText.value = ""
        _uiState.value = _uiState.value.copy(replyingTo = null)
        viewModelScope.launch {
            sendMessageUseCase(chatId, content, replyToId = replyTo?.id)
                .onSuccess { soundManager.playMessageSent() }
                .onFailure { e ->
                    Timber.e(e, "Не удалось отправить сообщение в чат $chatId")
                    _uiState.value = _uiState.value.copy(error = "Ошибка отправки: ${e.message}")
                }
        }
    }

    // ── Запись голосового ─────────────────────────────────────────────────

    /** Записанное голосовое в ожидании превью / подтверждения отправки. */
    data class PendingVoice(
        val bytes: ByteArray,
        val durationSeconds: Int,
        val waveform: IntArray,
    )

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingSeconds = MutableStateFlow(0)
    val recordingSeconds: StateFlow<Int> = _recordingSeconds.asStateFlow()

    /**
     * Live-амплитуды микрофона во время записи — для рисования реактивной
     * волны в индикаторе записи. Берётся напрямую из VoiceRecorder, который
     * сэмплирует maxAmplitude каждые 50 мс.
     */
    val recordingWaveform: StateFlow<IntArray> = voiceRecorder.liveAmplitudes

    /** После окончания записи здесь лежит результат для превью в модалке. */
    private val _pendingVoice = MutableStateFlow<PendingVoice?>(null)
    val pendingVoice: StateFlow<PendingVoice?> = _pendingVoice.asStateFlow()

    private var recordingTimerJob: Job? = null

    /** Отправляет картинку (после выбора через системный picker и сжатия в ImageCodec). */
    fun sendImage(imageData: com.secure.messenger.utils.ImageCodec.ImageData) {
        viewModelScope.launch {
            chatRepository.sendImageMessage(chatId, imageData)
                .onSuccess { soundManager.playMessageSent() }
                .onFailure { e ->
                    Timber.e(e, "Не удалось отправить картинку")
                    _uiState.value = _uiState.value.copy(error = "Ошибка отправки картинки: ${e.message}")
                }
        }
    }

    /**
     * Отправляет альбом из нескольких картинок. Все получают один groupId —
     * UI рендерит их как единую плитку (как в Telegram). Каждая картинка
     * шифруется и шлётся отдельным сообщением (иначе не влезает в SQLite
     * CursorWindow сервера при > 1–2 МБ).
     *
     * Прогресс обновляется после каждой успешной отправки — UI показывает
     * плашку «3 / 15». После завершения всей серии прогресс сбрасывается.
     */
    fun sendImages(images: List<com.secure.messenger.utils.ImageCodec.ImageData>) {
        if (images.isEmpty()) return
        if (images.size == 1) {
            sendImage(images.first())
            return
        }
        val groupId = java.util.UUID.randomUUID().toString()
        val total = images.size
        viewModelScope.launch {
            _albumSendProgress.value = AlbumProgress(0, total)
            var failed = 0
            images.forEachIndexed { index, img ->
                val grouped = img.copy(
                    groupId = groupId,
                    groupIndex = index,
                    groupSize = total,
                )
                val result = chatRepository.sendImageMessage(chatId, grouped)
                if (result.isFailure) {
                    failed++
                    Timber.e(result.exceptionOrNull(), "sendImages: $index/$total failed")
                }
                _albumSendProgress.value = AlbumProgress(index + 1, total)
                // Пауза между отправками + намёк GC — чтобы Flow-эмиссии успели
                // обработаться и не копились в очереди рекомпозиции.
                delay(250)
            }
            _albumSendProgress.value = null
            if (failed > 0) {
                _uiState.value = _uiState.value.copy(
                    error = "Не удалось отправить $failed из $total",
                )
            } else {
                soundManager.playMessageSent()
            }
        }
    }

    /** Прогресс отправки альбома: sent из total. null — отправки нет. */
    data class AlbumProgress(val sent: Int, val total: Int)

    private val _albumSendProgress = MutableStateFlow<AlbumProgress?>(null)
    val albumSendProgress: StateFlow<AlbumProgress?> = _albumSendProgress.asStateFlow()

    /** Начинает запись голосового сообщения. Должно вызываться после выдачи RECORD_AUDIO. */
    fun startVoiceRecording() {
        if (_isRecording.value) return
        if (!voiceRecorder.start()) {
            _uiState.value = _uiState.value.copy(error = "Не удалось начать запись")
            return
        }
        _isRecording.value = true
        _recordingSeconds.value = 0
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            while (_isRecording.value) {
                delay(1000)
                _recordingSeconds.value += 1
                // Жёсткий лимит — 60 секунд
                if (_recordingSeconds.value >= 60) {
                    stopVoiceRecording(cancel = false)
                }
            }
        }
    }

    /**
     * Останавливает запись. Если [cancel] = true — запись выбрасывается.
     * Иначе байты сохраняются в [pendingVoice] для превью в модалке —
     * пользователь решает в [confirmSendPendingVoice] / [discardPendingVoice].
     */
    fun stopVoiceRecording(cancel: Boolean) {
        if (!_isRecording.value) return
        _isRecording.value = false
        recordingTimerJob?.cancel()
        recordingTimerJob = null

        if (cancel) {
            voiceRecorder.cancel()
            _recordingSeconds.value = 0
            return
        }

        val result = voiceRecorder.stop()
        _recordingSeconds.value = 0
        if (result == null) {
            _uiState.value = _uiState.value.copy(error = "Запись пуста")
            return
        }
        _pendingVoice.value = PendingVoice(
            bytes = result.bytes,
            durationSeconds = result.durationSeconds,
            waveform = result.waveform,
        )
    }

    /** Отправка ранее записанного и подтверждённого в превью голосового. */
    fun confirmSendPendingVoice() {
        val pending = _pendingVoice.value ?: return
        _pendingVoice.value = null
        viewModelScope.launch {
            chatRepository.sendVoiceMessage(chatId, pending.bytes, pending.durationSeconds, pending.waveform)
                .onSuccess { soundManager.playMessageSent() }
                .onFailure { e ->
                    Timber.e(e, "Не удалось отправить голосовое")
                    _uiState.value = _uiState.value.copy(error = "Ошибка голосового: ${e.message}")
                }
        }
    }

    fun discardPendingVoice() {
        _pendingVoice.value = null
    }

    override fun onCleared() {
        super.onCleared()
        if (_isRecording.value) voiceRecorder.cancel()
        recordingTimerJob?.cancel()
    }

    /** Начать ответ на сообщение — показывает preview в инпуте. */
    fun startReplying(message: Message) {
        _uiState.value = _uiState.value.copy(replyingTo = message, editingMessage = null)
    }

    fun cancelReplying() {
        _uiState.value = _uiState.value.copy(replyingTo = null)
    }

    fun toggleEmojiPicker() {
        _uiState.value = _uiState.value.copy(showEmojiPicker = !_uiState.value.showEmojiPicker)
    }

    fun closeEmojiPicker() {
        if (_uiState.value.showEmojiPicker) {
            _uiState.value = _uiState.value.copy(showEmojiPicker = false)
        }
    }

    fun appendEmoji(emoji: String) {
        _inputText.value = _inputText.value + emoji
    }

    /**
     * Удаляет последний символ (графему) из инпута. Корректно обрабатывает
     * многобайтовые эмоджи и ZWJ-последовательности (👨‍👩‍👧, 🏳️‍🌈) —
     * BreakIterator находит границу графемного кластера и удаляет до неё.
     */
    fun backspaceInput() {
        val current = _inputText.value
        if (current.isEmpty()) return
        val bi = java.text.BreakIterator.getCharacterInstance()
        bi.setText(current)
        val prev = bi.preceding(current.length)
        _inputText.value = if (prev > 0) current.substring(0, prev) else ""
    }

    // ── Подсветка цитируемого сообщения при клике на цитату ──────────────

    /** ID сообщения, которое сейчас подсвечено (после тапа на цитату). */
    private val _highlightedMessageId = MutableStateFlow<String?>(null)
    val highlightedMessageId: StateFlow<String?> = _highlightedMessageId.asStateFlow()

    private var highlightJob: Job? = null

    /** Подсвечивает сообщение и автоматически снимает подсветку через 1.5 сек. */
    fun highlightMessage(messageId: String) {
        _highlightedMessageId.value = messageId
        highlightJob?.cancel()
        highlightJob = viewModelScope.launch {
            delay(1500)
            _highlightedMessageId.value = null
        }
    }

    fun deleteMessage(message: Message) {
        viewModelScope.launch {
            chatRepository.deleteMessage(message.id)
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(error = "Ошибка удаления: ${e.message}")
                }
        }
    }

    fun startEditing(message: Message) {
        _uiState.value = _uiState.value.copy(editingMessage = message)
        _inputText.value = message.content
    }

    fun cancelEditing() {
        _uiState.value = _uiState.value.copy(editingMessage = null)
        _inputText.value = ""
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** Выставляет сообщение об ошибке во внешнюю UI-state (снекбар подхватит). */
    fun reportError(message: String) {
        _uiState.value = _uiState.value.copy(error = message)
    }

    /** Улучшает текст в инпуте: Gemini Nano (если есть) → правила */
    fun enhanceText() {
        val current = _inputText.value
        if (current.isBlank()) return
        viewModelScope.launch {
            val (enhanced, changed) = TextEnhancer.enhance(current)
            if (changed) {
                _inputText.value = enhanced
            }
        }
    }

    /** Подгружает старые сообщения (вызывается при скролле вверх).
     *
     *  Логика двухуровневая:
     *  1. Если в локальной БД есть сообщения СТАРЕЕ самого старого видимого
     *     — просто увеличиваем displayLimit, observeMessages эмитит больше.
     *     Без сетевого запроса.
     *  2. Если локально дальше нечего — идём в сеть за следующей страницей,
     *     если сервер ранее сообщал что страницы есть (hasOlderMessages).
     *
     *  Раньше шаг 1 пропускался (всегда лезли в сеть), и при `hasOlderMessages
     *  = false` пагинация вообще не работала — даже если в БД лежали старые
     *  сообщения от прошлой сессии. Юзер видел только 20 свежих.
     */
    private var lastLoadOlderFailedAt = 0L

    fun loadOlderMessages() {
        val state = _uiState.value
        if (state.isLoadingOlder) return
        // Защита от retry-шторма: если предыдущая попытка упала меньше 3
        // секунд назад — не пытаемся снова (иначе при 503 от nginx firstVisible=0
        // триггерит подгрузку в цикле, и мы долбим сервер).
        if (System.currentTimeMillis() - lastLoadOlderFailedAt < 3_000) return

        val oldest = messages.value.firstOrNull() ?: return
        _uiState.value = state.copy(isLoadingOlder = true)

        viewModelScope.launch {
            val localOlderCount = runCatching {
                messageDao.countOlderThan(chatId, oldest.timestamp)
            }.getOrDefault(0)

            // Локально есть старее — РАСШИРЯЕМ ОКНО мгновенно. Юзер видит
            // продолжение списка без сетевой задержки.
            if (localOlderCount > 0) {
                displayLimit.value += PAGE_SIZE
                _uiState.value = _uiState.value.copy(isLoadingOlder = false)
            }

            // Параллельно (или вместо, если локально было пусто) идём в сеть.
            // Это нужно для синхронизации удалений: если на той стороне юзер
            // что-то удалил пока меня не было онлайн, fetchOlderMessages
            // увидит это в diff'е по диапазону и подтянет состояние сервера.
            // hasOlderMessages — подсказка от прошлого fetch'а; даже если он
            // false, дёрнуть один раз сверх кеша имеет смысл (sync deletions).
            val shouldFetchNetwork = state.hasOlderMessages || localOlderCount == 0
            if (!shouldFetchNetwork) return@launch

            chatRepository.fetchOlderMessages(chatId, oldest.timestamp)
                .onSuccess { hasMore ->
                    // Если ОКНО ещё не расширили (localOlderCount == 0) — расширяем
                    // теперь, после прихода данных. Иначе уже расширено выше.
                    if (localOlderCount == 0) {
                        displayLimit.value += PAGE_SIZE
                    }
                    _uiState.value = _uiState.value.copy(isLoadingOlder = false, hasOlderMessages = hasMore)
                }
                .onFailure { e ->
                    Timber.e(e, "Failed to load older messages")
                    lastLoadOlderFailedAt = System.currentTimeMillis()
                    _uiState.value = _uiState.value.copy(isLoadingOlder = false)
                }
        }
    }

    private fun markAsRead() {
        viewModelScope.launch { chatRepository.markAsRead(chatId) }
    }

    companion object {
        // 20 вместо 50 — при 50 в диалоге с большим числом картинок Coil
        // пытался одновременно декодировать множество полноразмерных bitmap-ов
        // и приложение падало по OOM при cold-open чата.
        private const val PAGE_SIZE = 20
    }
}

package com.secure.messenger.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.secure.messenger.data.local.dao.UserDao
import com.secure.messenger.data.remote.websocket.SignalingClient
import com.secure.messenger.data.remote.websocket.SignalingEvent
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class ChatUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val editingMessage: Message? = null,
    val replyingTo: Message? = null,
    val isLoadingOlder: Boolean = false,
    val hasOlderMessages: Boolean = true,
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
    private val signalingClient: SignalingClient,
) : ViewModel() {

    private val voiceRecorder = VoiceRecorder(context)

    private val chatId: String = checkNotNull(savedStateHandle["chatId"])
    private val displayLimit = MutableStateFlow(PAGE_SIZE)

    val currentUserId: StateFlow<String?> = authRepository.currentUser
        .map { it?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Список сообщений текущего чата — обновляется в реальном времени через WebSocket
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> = displayLimit
        .flatMapLatest { limit -> chatRepository.observeMessages(chatId, limit) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Метаданные текущего чата (название, аватар, участники) — для отображения в шапке экрана
    val chatInfo: StateFlow<Chat?> = chatRepository
        .observeChats()
        .map { list -> list.find { it.id == chatId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    init {
        // Загружаем историю сообщений с сервера при открытии чата (+ синхронизация удалений)
        viewModelScope.launch {
            chatRepository.fetchMessages(chatId).onFailure { e ->
                Timber.e(e, "fetchMessages failed for chatId=$chatId")
            }
        }
        // Помечаем все сообщения как прочитанные при открытии чата
        markAsRead()
        observeTyping()
        // При каждом новом сообщении — помечаем как прочитанное (пока чат открыт)
        observeNewMessagesAndMarkRead()
    }

    private fun observeNewMessagesAndMarkRead() {
        viewModelScope.launch {
            messages.collect { markAsRead() }
        }
    }

    private fun observeTyping() {
        viewModelScope.launch {
            signalingClient.events.collect { event ->
                if (event is SignalingEvent.Typing && event.chatId == chatId) {
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

    /** После окончания записи здесь лежит результат для превью в модалке. */
    private val _pendingVoice = MutableStateFlow<PendingVoice?>(null)
    val pendingVoice: StateFlow<PendingVoice?> = _pendingVoice.asStateFlow()

    private var recordingTimerJob: Job? = null

    /** Отправляет картинку (после выбора через системный picker и сжатия в ImageCodec). */
    fun sendImage(imageData: com.secure.messenger.utils.ImageCodec.ImageData) {
        viewModelScope.launch {
            chatRepository.sendImageMessage(chatId, imageData)
                .onFailure { e ->
                    Timber.e(e, "Не удалось отправить картинку")
                    _uiState.value = _uiState.value.copy(error = "Ошибка отправки картинки: ${e.message}")
                }
        }
    }

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

    /** Подгружает старые сообщения (вызывается при скролле вверх) */
    fun loadOlderMessages() {
        val state = _uiState.value
        if (state.isLoadingOlder || !state.hasOlderMessages) return

        val oldest = messages.value.firstOrNull() ?: return
        _uiState.value = state.copy(isLoadingOlder = true)

        viewModelScope.launch {
            chatRepository.fetchOlderMessages(chatId, oldest.timestamp)
                .onSuccess { hasMore ->
                    displayLimit.value += PAGE_SIZE
                    _uiState.value = _uiState.value.copy(isLoadingOlder = false, hasOlderMessages = hasMore)
                }
                .onFailure { e ->
                    Timber.e(e, "Failed to load older messages")
                    _uiState.value = _uiState.value.copy(isLoadingOlder = false)
                }
        }
    }

    private fun markAsRead() {
        viewModelScope.launch { chatRepository.markAsRead(chatId) }
    }

    companion object {
        private const val PAGE_SIZE = 50
    }
}

package com.secure.messenger.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.messenger.data.local.dao.UserDao
import com.secure.messenger.data.remote.websocket.SignalingClient
import com.secure.messenger.data.remote.websocket.SignalingEvent
import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.ChatType
import com.secure.messenger.domain.model.Message
import com.secure.messenger.domain.repository.AuthRepository
import com.secure.messenger.domain.repository.ChatRepository
import com.secure.messenger.domain.usecase.SendMessageUseCase
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
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val sendMessageUseCase: SendMessageUseCase,
    authRepository: AuthRepository,
    private val userDao: UserDao,
    private val signalingClient: SignalingClient,
) : ViewModel() {

    private val chatId: String = checkNotNull(savedStateHandle["chatId"])

    val currentUserId: StateFlow<String?> = authRepository.currentUser
        .map { it?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Список сообщений текущего чата — обновляется в реальном времени через WebSocket
    val messages: StateFlow<List<Message>> = chatRepository
        .observeMessages(chatId)
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

    // Индикатор «печатает…»
    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()
    private var typingResetJob: Job? = null

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    init {
        // Загружаем историю сообщений с сервера при открытии чата
        viewModelScope.launch { chatRepository.fetchMessages(chatId) }
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
    }

    fun sendMessage() {
        val editing = _uiState.value.editingMessage
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
        viewModelScope.launch {
            sendMessageUseCase(chatId, content)
                .onFailure { e ->
                    Timber.e(e, "Не удалось отправить сообщение в чат $chatId")
                    _uiState.value = _uiState.value.copy(error = "Ошибка отправки: ${e.message}")
                }
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

    private fun markAsRead() {
        viewModelScope.launch { chatRepository.markAsRead(chatId) }
    }
}

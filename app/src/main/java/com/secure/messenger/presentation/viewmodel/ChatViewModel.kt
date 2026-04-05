package com.secure.messenger.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.Message
import com.secure.messenger.domain.repository.AuthRepository
import com.secure.messenger.domain.repository.ChatRepository
import com.secure.messenger.domain.usecase.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class ChatUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val sendMessageUseCase: SendMessageUseCase,
    authRepository: AuthRepository,
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

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    init {
        // Загружаем историю сообщений с сервера при открытии чата
        viewModelScope.launch { chatRepository.fetchMessages(chatId) }
        // Помечаем все сообщения как прочитанные при открытии чата
        markAsRead()
    }

    fun onInputChange(text: String) {
        _inputText.value = text
    }

    fun sendMessage() {
        val content = _inputText.value.trim()
        if (content.isEmpty()) return
        _inputText.value = ""

        viewModelScope.launch {
            sendMessageUseCase(chatId, content)
                .onFailure { e ->
                    Timber.e(e, "Не удалось отправить сообщение в чат $chatId")
                    _uiState.value = ChatUiState(error = "Ошибка отправки: ${e.message}")
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun markAsRead() {
        viewModelScope.launch { chatRepository.markAsRead(chatId) }
    }
}

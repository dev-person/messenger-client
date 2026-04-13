package com.secure.messenger.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.messenger.data.remote.api.MessengerApi
import com.secure.messenger.data.remote.websocket.SignalingClient
import com.secure.messenger.data.remote.websocket.SignalingEvent
import com.secure.messenger.di.AuthTokenProvider
import com.secure.messenger.data.remote.api.SupportInfoDto
import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.User
import com.secure.messenger.domain.repository.AuthRepository
import com.secure.messenger.domain.repository.ChatRepository
import com.secure.messenger.domain.repository.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    authRepository: AuthRepository,
    private val contactRepository: ContactRepository,
    private val api: MessengerApi,
    private val signalingClient: SignalingClient,
    private val tokenProvider: AuthTokenProvider,
) : ViewModel() {

    // Список всех чатов — обновляется в реальном времени
    val chats: StateFlow<List<Chat>> = chatRepository
        .observeChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Текущий авторизованный пользователь — для отображения в шапке
    val currentUser: StateFlow<User?> = authRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Состояние WebSocket — берём напрямую из SignalingClient (StateFlow с replay)
    val isConnected: StateFlow<Boolean> = signalingClient.isConnected

    // Чаты, в которых собеседник сейчас печатает (chatId → сбрасывается через 3с тишины)
    private val _typingChats = MutableStateFlow<Set<String>>(emptySet())
    val typingChats: StateFlow<Set<String>> = _typingChats.asStateFlow()
    private val typingResetJobs = mutableMapOf<String, Job>()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Флаг «первой загрузки» — true пока syncChats() не отработал хотя бы раз.
    // Используется в UI для показа скелетон-плейсхолдеров вместо «Нет чатов»
    // при пустом списке: иначе при первом запуске мелькает заглушка пустого
    // состояния, потом резко появляются реальные чаты.
    private val _isInitialLoading = MutableStateFlow(true)
    val isInitialLoading: StateFlow<Boolean> = _isInitialLoading.asStateFlow()

    init {
        syncChats()
        observeTyping()
        // При каждом восстановлении WebSocket-соединения — повторно синхронизируем
        // список чатов. Это обновляет онлайн-статус собеседников: пока WS был
        // отключён, события user_status могли быть пропущены, а сервер при
        // GET /chats возвращает актуальные is_online из БД.
        observeReconnect()
    }

    private fun observeReconnect() {
        viewModelScope.launch {
            // Пропускаем самое первое значение (false до подключения), реагируем
            // только на переход из false → true.
            var prev = signalingClient.isConnected.value
            signalingClient.isConnected.collect { connected ->
                if (connected && !prev) {
                    Timber.d("WS reconnected — re-syncing chats for fresh online statuses")
                    syncChats()
                }
                prev = connected
            }
        }
    }

    private fun observeTyping() {
        viewModelScope.launch {
            signalingClient.events.collect { event ->
                if (event is SignalingEvent.Typing) {
                    val chatId = event.chatId
                    _typingChats.value = _typingChats.value + chatId
                    typingResetJobs[chatId]?.cancel()
                    typingResetJobs[chatId] = viewModelScope.launch {
                        delay(3_000)
                        _typingChats.value = _typingChats.value - chatId
                    }
                }
            }
        }
    }

    private fun syncChats() {
        viewModelScope.launch {
            val user = currentUser.filterNotNull().first()
            chatRepository.syncChats(user.id)
                .onFailure { e ->
                    Timber.e(e, "Ошибка синхронизации чатов")
                    _error.value = "Не удалось загрузить чаты"
                }
            // После первого syncChats — UI больше не должен показывать скелетон.
            // Делаем независимо от success/failure: даже если сервер недоступен,
            // дальше пользователь увидит либо локальный кэш, либо «Нет чатов».
            _isInitialLoading.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }

    // ── Действия с чатами ─────────────────────────────────────────────────────

    /** Удаляет чат из локальной БД */
    fun deleteChat(chatId: String) {
        viewModelScope.launch { chatRepository.deleteChat(chatId) }
    }

    /** Блокирует пользователя (удаляет из контактов) */
    fun blockUser(userId: String) {
        viewModelScope.launch {
            runCatching { api.removeContact(userId) }
        }
    }

    /** Загружает информацию «Поддержать автора» с сервера */
    suspend fun loadSupportInfo(): SupportInfoDto? {
        return api.getSupportInfo().data
    }
}

package com.secure.messenger.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.messenger.BuildConfig
import com.secure.messenger.data.remote.websocket.SignalingClient
import com.secure.messenger.data.remote.websocket.SignalingEvent
import com.secure.messenger.di.AuthTokenProvider
import com.secure.messenger.service.IncomingMessageHandler
import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.User
import com.secure.messenger.domain.repository.AuthRepository
import com.secure.messenger.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    authRepository: AuthRepository,
    private val signalingClient: SignalingClient,
    private val tokenProvider: AuthTokenProvider,
    private val incomingMessageHandler: IncomingMessageHandler,
) : ViewModel() {

    // Список всех чатов — обновляется в реальном времени
    val chats: StateFlow<List<Chat>> = chatRepository
        .observeChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Текущий авторизованный пользователь — для отображения в шапке
    val currentUser: StateFlow<User?> = authRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Состояние WebSocket-подключения к серверу
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    init {
        observeConnectionState()
        connectWebSocket()
        syncChats()
    }

    private fun syncChats() {
        viewModelScope.launch {
            val user = currentUser.filterNotNull().first()
            chatRepository.syncChats(user.id)
        }
    }

    // Подключаемся к WebSocket при создании ViewModel (открытие главного экрана)
    private fun connectWebSocket() {
        val token = tokenProvider.token ?: return
        signalingClient.connect(BuildConfig.WS_BASE_URL, token)
    }

    // Подписываемся на события и отслеживаем статус подключения
    private fun observeConnectionState() {
        viewModelScope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.Connected    -> _isConnected.value = true
                    is SignalingEvent.Disconnected -> {
                        _isConnected.value = false
                        scheduleReconnect()
                    }
                    is SignalingEvent.NewMessage -> viewModelScope.launch {
                        incomingMessageHandler.handle(event.json)
                    }
                    else -> Unit
                }
            }
        }
    }

    // Переподключение с задержкой — вызывается автоматически при разрыве
    private fun scheduleReconnect() {
        viewModelScope.launch {
            delay(3_000)
            if (!_isConnected.value) {
                connectWebSocket()
            }
        }
    }

    // Немедленное переподключение — вызывается при возврате в foreground
    fun reconnect() {
        if (!_isConnected.value) {
            connectWebSocket()
        }
    }

    override fun onCleared() {
        // Отключаемся при уходе с главного экрана
        signalingClient.disconnect()
        super.onCleared()
    }
}

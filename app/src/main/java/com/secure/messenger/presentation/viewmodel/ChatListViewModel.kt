package com.secure.messenger.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.messenger.data.remote.api.MessengerApi
import com.secure.messenger.data.remote.websocket.SignalingClient
import com.secure.messenger.di.AuthTokenProvider
import com.secure.messenger.data.remote.api.SupportInfoDto
import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.User
import com.secure.messenger.domain.repository.AuthRepository
import com.secure.messenger.domain.repository.ChatRepository
import com.secure.messenger.domain.repository.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    init {
        syncChats()
    }

    private fun syncChats() {
        viewModelScope.launch {
            val user = currentUser.filterNotNull().first()
            chatRepository.syncChats(user.id)
        }
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

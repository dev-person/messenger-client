package com.secure.messenger.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.ChatRole
import com.secure.messenger.domain.model.Contact
import com.secure.messenger.domain.model.User
import com.secure.messenger.domain.repository.AuthRepository
import com.secure.messenger.domain.repository.ChatRepository
import com.secure.messenger.domain.repository.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class GroupInfoUiState(
    val chat: Chat? = null,
    val members: List<User> = emptyList(),
    val myUserId: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val leftGroup: Boolean = false,
    val error: String? = null,
)

/**
 * ViewModel экрана с информацией о группе: название, список участников
 * с ролями, и доступные действия (добавить/кикнуть/сменить роль/выйти)
 * в зависимости от своей роли.
 *
 * [chatId] передаётся через SavedStateHandle из нав-аргумента.
 */
@HiltViewModel
class GroupInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val authRepository: AuthRepository,
    contactRepository: ContactRepository,
    private val userDao: com.secure.messenger.data.local.dao.UserDao,
) : ViewModel() {

    private val chatId: String = checkNotNull(savedStateHandle["chatId"])

    private val _uiState = MutableStateFlow(GroupInfoUiState())
    val uiState: StateFlow<GroupInfoUiState> = _uiState.asStateFlow()

    /**
     * Для диалога «Добавить участника» — зарегистрированные контакты,
     * которых ещё нет в группе. Возвращаем User (а не Contact), чтобы UI
     * мог нарисовать аватар: avatarUrl/publicKey/isOnline лежат именно в
     * UserDao, а Contact знает только displayName/phone из адресной книги.
     */
    val addableContacts: StateFlow<List<User>> = kotlinx.coroutines.flow.combine(
        contactRepository.observeContacts(),
        userDao.observeContacts(),
        _uiState,
    ) { contacts, users, state ->
        val memberIds = state.members.map { it.id }.toSet()
        val byId = users.associateBy { it.id }
        contacts
            .filter { it.isRegistered && it.id !in memberIds }
            .map { c ->
                val u = byId[c.id]
                User(
                    id = c.id,
                    phone = c.phone,
                    username = u?.username ?: "",
                    displayName = c.displayName,
                    avatarUrl = u?.avatarUrl,
                    bio = u?.bio,
                    isOnline = u?.isOnline ?: false,
                    lastSeen = u?.lastSeen ?: 0L,
                    publicKey = u?.publicKey ?: "",
                    isContact = true,
                )
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val myId = runCatching { authRepository.currentUser.first()?.id }
                .getOrNull().orEmpty()
            val chat = chatRepository.observeChats().first()
                .firstOrNull { it.id == chatId }
            val members = runCatching { chatRepository.getGroupMembers(chatId) }
                .getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                chat = chat,
                members = members,
                myUserId = myId,
            )
        }
    }

    fun updateAvatar(jpegBytes: ByteArray) {
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            chatRepository.updateGroupAvatar(chatId, jpegBytes, "image/jpeg")
                .onSuccess { newUrl ->
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        chat = _uiState.value.chat?.copy(avatarUrl = newUrl),
                    )
                }
                .onFailure { e -> reportError("Не удалось сохранить аватар: ${e.message}") }
        }
    }

    fun updateTitle(newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            chatRepository.updateGroupTitle(chatId, trimmed)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        chat = _uiState.value.chat?.copy(title = trimmed),
                    )
                }
                .onFailure { e -> reportError("Не удалось сохранить название: ${e.message}") }
        }
    }

    fun addMember(userId: String) {
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            chatRepository.addGroupMember(chatId, userId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isSaving = false)
                    refresh()
                }
                .onFailure { e -> reportError("Не удалось добавить: ${e.message}") }
        }
    }

    fun removeMember(userId: String) {
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            chatRepository.removeGroupMember(chatId, userId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        members = _uiState.value.members.filterNot { it.id == userId },
                    )
                }
                .onFailure { e -> reportError("Не удалось исключить: ${e.message}") }
        }
    }

    fun changeRole(userId: String, role: ChatRole) {
        if (role !in listOf(ChatRole.ADMIN, ChatRole.MEMBER)) return
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            chatRepository.changeGroupRole(chatId, userId, role)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isSaving = false)
                    // Обновляем локально — чтобы UI отреагировал сразу
                    _uiState.value = _uiState.value.copy(
                        members = _uiState.value.members.map {
                            if (it.id == userId) it.copy(groupRole = role) else it
                        },
                    )
                }
                .onFailure { e -> reportError("Не удалось сменить роль: ${e.message}") }
        }
    }

    /**
     * Передаёт роль CREATOR другому участнику. Только текущий CREATOR
     * может вызвать; на сервере это перепроверяется. После успеха
     * перезагружаем участников чтобы новые роли отобразились в UI.
     */
    fun transferOwnership(userId: String) {
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            chatRepository.transferGroupOwnership(chatId, userId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isSaving = false)
                    refresh()
                }
                .onFailure { e -> reportError("Не удалось передать владельца: ${e.message}") }
        }
    }

    fun leaveGroup() {
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            chatRepository.leaveGroup(chatId)
                .onSuccess {
                    Timber.d("Left group $chatId")
                    _uiState.value = _uiState.value.copy(isSaving = false, leftGroup = true)
                }
                .onFailure { e -> reportError("Не удалось выйти: ${e.message}") }
        }
    }

    /**
     * Включает/выключает уведомления для группы. Локальный флаг — сервер про
     * мьют не знает (FCM-пуш приходит, но клиент его не показывает).
     * Оптимистично обновляем UI сразу — `refresh` не подписан на flow,
     * поэтому состояние Switch без оптимистики «отскакивало бы» обратно.
     */
    fun setMuted(muted: Boolean) {
        _uiState.value = _uiState.value.copy(
            chat = _uiState.value.chat?.copy(isMuted = muted),
        )
        viewModelScope.launch {
            chatRepository.muteChat(chatId, mutedUntil = if (muted) Long.MAX_VALUE else null)
                .onFailure { e -> reportError("Не удалось изменить настройки: ${e.message}") }
        }
    }

    /**
     * Удаление группы. Доступно только CREATOR'у. Сервер удаляет каскадом
     * и шлёт всем участникам group_deleted. Используем тот же флаг leftGroup
     * для возврата на список чатов — UI с точки зрения навигации не отличает
     * «вышел» и «удалил».
     */
    fun deleteGroup() {
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            chatRepository.deleteGroup(chatId)
                .onSuccess {
                    Timber.d("Deleted group $chatId")
                    _uiState.value = _uiState.value.copy(isSaving = false, leftGroup = true)
                }
                .onFailure { e -> reportError("Не удалось удалить группу: ${e.message}") }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun reportError(msg: String) {
        _uiState.value = _uiState.value.copy(isSaving = false, error = msg)
    }
}

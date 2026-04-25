package com.secure.messenger.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.messenger.domain.model.Contact
import com.secure.messenger.domain.repository.ChatRepository
import com.secure.messenger.domain.repository.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class CreateGroupUiState(
    val title: String = "",
    val selectedContactIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    /** JPEG-байты обрезанного аватара (после AvatarCropDialog). null — без аватара. */
    val avatarBytes: ByteArray? = null,
    val isCreating: Boolean = false,
    val createdChatId: String? = null,
    val error: String? = null,
)

/**
 * Экран создания группы: ввод названия, выбор участников из списка
 * зарегистрированных контактов. Лимит участников — 49 (+ сам creator = 50).
 */
@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    contactRepository: ContactRepository,
    private val chatRepository: ChatRepository,
    userDao: com.secure.messenger.data.local.dao.UserDao,
) : ViewModel() {

    companion object {
        const val MAX_GROUP_MEMBERS = 50
        const val MAX_SELECTABLE = MAX_GROUP_MEMBERS - 1 // минус creator
    }

    private val _uiState = MutableStateFlow(CreateGroupUiState())
    val uiState: StateFlow<CreateGroupUiState> = _uiState.asStateFlow()

    /**
     * Зарегистрированные контакты, склеенные с UserEntity для avatarUrl/
     * username/online. Тип `User`, чтобы UI мог использовать общий
     * AvatarImage и единый стиль (медведь-Grizzly как заглушка).
     */
    val contacts: StateFlow<List<com.secure.messenger.domain.model.User>> =
        kotlinx.coroutines.flow.combine(
            contactRepository.observeContacts(),
            userDao.observeContacts(),
        ) { contacts, users ->
            val byId = users.associateBy { it.id }
            contacts
                .filter { it.isRegistered }
                .map { c ->
                    val u = byId[c.id]
                    com.secure.messenger.domain.model.User(
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

    fun onTitleChange(value: String) {
        _uiState.value = _uiState.value.copy(title = value)
    }

    fun onSearchChange(value: String) {
        _uiState.value = _uiState.value.copy(searchQuery = value)
    }

    /** Сохраняет уже обрезанные JPEG-байты аватара. Загружается на сервер
     *  после успешного создания группы (нужен chatId).  */
    fun onAvatarPicked(jpegBytes: ByteArray) {
        _uiState.value = _uiState.value.copy(avatarBytes = jpegBytes)
    }

    fun clearAvatar() {
        _uiState.value = _uiState.value.copy(avatarBytes = null)
    }

    fun toggleContact(contactId: String) {
        val current = _uiState.value.selectedContactIds
        val next = if (contactId in current) {
            current - contactId
        } else {
            if (current.size >= MAX_SELECTABLE) {
                _uiState.value = _uiState.value.copy(
                    error = "Максимум $MAX_SELECTABLE участников помимо вас",
                )
                return
            }
            current + contactId
        }
        _uiState.value = _uiState.value.copy(selectedContactIds = next)
    }

    fun createGroup() {
        val state = _uiState.value
        val title = state.title.trim()
        when {
            title.isEmpty() -> {
                _uiState.value = state.copy(error = "Введите название группы")
                return
            }
            state.selectedContactIds.isEmpty() -> {
                _uiState.value = state.copy(error = "Выберите хотя бы одного участника")
                return
            }
            state.isCreating -> return
        }

        _uiState.value = state.copy(isCreating = true, error = null)
        viewModelScope.launch {
            chatRepository.createGroupChat(title, state.selectedContactIds.toList())
                .onSuccess { chat ->
                    Timber.d("Group created: ${chat.id}")
                    // Если выбрали аватар — заливаем его сразу же, до показа
                    // ChatScreen, чтобы пользователь не открыл чат с заглушкой
                    // и не увидел «прыжок» аватара через секунду.
                    state.avatarBytes?.let { bytes ->
                        runCatching {
                            chatRepository.updateGroupAvatar(chat.id, bytes, "image/jpeg")
                        }.onFailure { e ->
                            Timber.w(e, "createGroup: avatar upload failed (group already created)")
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        isCreating = false,
                        createdChatId = chat.id,
                    )
                }
                .onFailure { e ->
                    Timber.e(e, "createGroup failed")
                    _uiState.value = _uiState.value.copy(
                        isCreating = false,
                        error = e.message ?: "Не удалось создать группу",
                    )
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

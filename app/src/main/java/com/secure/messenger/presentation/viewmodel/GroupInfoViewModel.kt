package com.secure.messenger.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.ChatRole
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
 *
 * Состав группы хранится в локальной БД (chat_members JOIN users) и читается
 * реактивно — экран открывается мгновенно из кеша, а сетевой fetch обновляет
 * данные «поверх» без визуального сброса. Раньше каждое открытие дёргало
 * сеть и UI флешил спиннер «загрузка участников».
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
        observeLocal()
        refreshFromNetwork()
    }

    /**
     * Реактивно слушает локальную БД: chat (название, аватар, роль), members
     * (с ролями и онлайн-статусом). UI получает данные мгновенно из кеша
     * и обновляется live при WS-events (user_status, group_member_*, и т.п.).
     */
    private fun observeLocal() {
        viewModelScope.launch {
            val myId = runCatching { authRepository.currentUser.first()?.id }
                .getOrNull().orEmpty()
            _uiState.value = _uiState.value.copy(myUserId = myId, isLoading = false)
        }
        viewModelScope.launch {
            chatRepository.observeChats().collect { chats ->
                val chat = chats.firstOrNull { it.id == chatId }
                if (chat != _uiState.value.chat) {
                    _uiState.value = _uiState.value.copy(chat = chat)
                }
            }
        }
        viewModelScope.launch {
            chatRepository.observeGroupMembers(chatId).collect { members ->
                if (members != _uiState.value.members) {
                    _uiState.value = _uiState.value.copy(members = members)
                }
            }
        }
    }

    /**
     * Сетевой refresh актуального состава. Запускается при открытии экрана
     * и после действий, меняющих состав (add/remove/transfer). Никаких
     * UI-спиннеров не выставляет — данные уже видны из локального кеша,
     * сеть просто перезатирает их «поверх» через chatMemberDao.
     */
    fun refreshFromNetwork() {
        viewModelScope.launch {
            runCatching { chatRepository.getGroupMembers(chatId) }
                .onFailure { e ->
                    Timber.w(e, "GroupInfoViewModel: refresh failed (offline?)")
                }
        }
    }

    /** Алиас для совместимости со старым именем — поведение то же. */
    fun refresh() = refreshFromNetwork()

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
                    // addGroupMember сам пишет свежий состав в chat_members,
                    // observeGroupMembers выдаст обновление автоматически.
                    _uiState.value = _uiState.value.copy(isSaving = false)
                }
                .onFailure { e -> reportError("Не удалось добавить: ${e.message}") }
        }
    }

    fun removeMember(userId: String) {
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            chatRepository.removeGroupMember(chatId, userId)
                .onSuccess {
                    // removeGroupMember сам удаляет запись из chat_members
                    // (плюс перезатирает свежим snapshot'ом).
                    _uiState.value = _uiState.value.copy(isSaving = false)
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
                    // changeGroupRole оптимистично пишет новую роль в chat_members,
                    // observeGroupMembers подхватит изменение.
                    _uiState.value = _uiState.value.copy(isSaving = false)
                }
                .onFailure { e -> reportError("Не удалось сменить роль: ${e.message}") }
        }
    }

    /**
     * Передаёт роль CREATOR другому участнику. Только текущий CREATOR
     * может вызвать; на сервере это перепроверяется. После успеха просим
     * сервер свежий состав — сервер каскадом меняет роли (бывший creator
     * становится admin'ом) и cacheChatMembers перезатрёт обе роли.
     */
    fun transferOwnership(userId: String) {
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            chatRepository.transferGroupOwnership(chatId, userId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isSaving = false)
                    refreshFromNetwork()
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

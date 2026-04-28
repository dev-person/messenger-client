package com.secure.messenger.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.messenger.domain.model.User
import com.secure.messenger.domain.repository.AuthRepository
import com.secure.messenger.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI-стейт экрана выбора участников группового звонка.
 *
 * [members] — все участники группы кроме самого пользователя; при пустом
 * списке экран показывает плашку «нет других участников».
 * [selectedIds] — выбранные на текущий момент. Кнопка «Позвонить» активна
 * когда хотя бы один выбран.
 */
data class GroupCallPickerUiState(
    val isLoading: Boolean = true,
    val members: List<User> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val error: String? = null,
)

@HiltViewModel
class GroupCallPickerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val chatId: String = checkNotNull(savedStateHandle["chatId"])

    private val _uiState = MutableStateFlow(GroupCallPickerUiState())
    val uiState: StateFlow<GroupCallPickerUiState> = _uiState.asStateFlow()

    init {
        observeLocalAndRefresh()
    }

    /**
     * Список участников читаем из локального кеша (chat_members JOIN users)
     * — picker открывается мгновенно. Параллельно запускаем сетевой refresh,
     * чтобы синхронизировать любые изменения состава, которые могли быть
     * пропущены пока юзер был офлайн. По дефолту все «другие» участники
     * выбраны (как в Telegram); первый emit заполняет selectedIds.
     */
    private fun observeLocalAndRefresh() {
        viewModelScope.launch {
            val myId = runCatching { authRepository.currentUser.first()?.id }
                .getOrNull().orEmpty()
            var firstEmit = true
            chatRepository.observeGroupMembers(chatId).collect { all ->
                val others = all.filter { it.id != myId }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    members = others,
                    selectedIds = if (firstEmit) {
                        others.map { it.id }.toSet()
                    } else {
                        // На последующих эмитах сохраняем ручной выбор юзера,
                        // но удаляем из selected тех, кого больше нет в группе.
                        _uiState.value.selectedIds intersect others.map { it.id }.toSet()
                    },
                )
                firstEmit = false
            }
        }
        viewModelScope.launch {
            runCatching { chatRepository.getGroupMembers(chatId) }
                .onFailure { e ->
                    if (_uiState.value.members.isEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = e.message ?: "Не удалось загрузить участников",
                        )
                    }
                }
        }
    }

    fun onSearchChange(q: String) {
        _uiState.value = _uiState.value.copy(searchQuery = q)
    }

    fun toggle(userId: String) {
        val current = _uiState.value.selectedIds
        _uiState.value = _uiState.value.copy(
            selectedIds = if (userId in current) current - userId else current + userId,
        )
    }

    fun selectAll() {
        _uiState.value = _uiState.value.copy(
            selectedIds = _uiState.value.members.map { it.id }.toSet(),
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedIds = emptySet())
    }
}

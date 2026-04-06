package com.secure.messenger.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.messenger.domain.model.Contact
import com.secure.messenger.domain.model.User
import com.secure.messenger.domain.repository.ChatRepository
import com.secure.messenger.domain.repository.ContactRepository
import com.secure.messenger.domain.usecase.SyncContactsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class ContactsUiState(
    val isSyncing: Boolean = false,
    val searchResults: List<User> = emptyList(),
    val error: String? = null,
    val openChatId: String? = null,
    // Все контакты с устройства (зарегистрированные + незарегистрированные)
    val deviceContacts: List<Contact> = emptyList(),
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val syncContactsUseCase: SyncContactsUseCase,
    private val chatRepository: ChatRepository,
) : ViewModel() {

    // Зарегистрированные контакты из БД (обновляются автоматически)
    private val dbContacts: StateFlow<List<Contact>> = contactRepository
        .observeContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    // Объединённый список: контакты с устройства + из БД (без дублей)
    val contacts: StateFlow<List<Contact>> = combine(
        dbContacts,
        _uiState,
    ) { db, state ->
        val allById = LinkedHashMap<String, Contact>()
        // Сначала добавляем контакты из синхронизации (зарегистрированные + незарегистрированные)
        state.deviceContacts.forEach { allById[it.id] = it }
        // Потом перезаписываем зарегистрированными из БД (более актуальные данные)
        db.forEach { allById[it.id] = it }
        allById.values.toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchQuery = MutableStateFlow("")

    init {
        observeSearchQuery()
        // НЕ вызываем syncContacts() в init — ждём разрешения от Screen
    }

    @OptIn(FlowPreview::class)
    private fun observeSearchQuery() {
        viewModelScope.launch {
            _searchQuery.debounce(300).collect { query ->
                if (query.length >= 2) searchUsers(query)
                else _uiState.value = _uiState.value.copy(searchResults = emptyList())
            }
        }
    }

    fun syncContacts() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            syncContactsUseCase()
                .onSuccess { allContacts ->
                    Timber.d("Synced ${allContacts.size} contacts (${allContacts.count { it.isRegistered }} registered)")
                    _uiState.value = _uiState.value.copy(deviceContacts = allContacts)
                }
                .onFailure { e ->
                    Timber.e(e, "Sync contacts failed")
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            _uiState.value = _uiState.value.copy(isSyncing = false)
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    private fun searchUsers(query: String) {
        viewModelScope.launch {
            contactRepository.searchUsers(query)
                .onSuccess { users -> _uiState.value = _uiState.value.copy(searchResults = users) }
                .onFailure { e -> Timber.e(e, "Search failed") }
        }
    }

    fun addContact(userId: String) {
        viewModelScope.launch {
            contactRepository.addContact(userId)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun openDirectChat(userId: String) {
        viewModelScope.launch {
            chatRepository.getOrCreateDirectChat(userId)
                .onSuccess { chat -> _uiState.value = _uiState.value.copy(openChatId = chat.id) }
                .onFailure { e ->
                    Timber.e(e, "Failed to open direct chat with $userId")
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
        }
    }

    fun clearOpenChat() {
        _uiState.value = _uiState.value.copy(openChatId = null)
    }

    fun inviteContact(phone: String): String = contactRepository.buildInviteLink(phone)

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

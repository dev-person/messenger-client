package com.secure.messenger.presentation.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.messenger.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileSetupUiState(
    val displayName: String = "",
    val username: String = "",
    val bio: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ProfileSetupViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileSetupUiState())
    val uiState: StateFlow<ProfileSetupUiState> = _uiState.asStateFlow()

    init {
        // Предзаполняем из текущего профиля (если пользователь уже что-то указал)
        viewModelScope.launch {
            authRepository.currentUser.collect { user ->
                user?.let {
                    _uiState.value = _uiState.value.copy(
                        displayName = it.displayName,
                        username = it.username,
                        bio = it.bio ?: "",
                    )
                }
            }
        }
    }

    fun onDisplayNameChange(value: String) {
        _uiState.value = _uiState.value.copy(displayName = value, error = null)
    }

    fun onUsernameChange(value: String) {
        // Разрешаем только буквы, цифры и _
        val cleaned = value.filter { it.isLetterOrDigit() || it == '_' }.lowercase()
        _uiState.value = _uiState.value.copy(username = cleaned, error = null)
    }

    fun onBioChange(value: String) {
        _uiState.value = _uiState.value.copy(bio = value)
    }

    fun save(onSuccess: () -> Unit) {
        val name = _uiState.value.displayName.trim()
        val username = _uiState.value.username.trim()
        if (name.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "Укажите ваше имя")
            return
        }
        if (username.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "Укажите username")
            return
        }
        if (username.length < 3) {
            _uiState.value = _uiState.value.copy(error = "Username должен быть не менее 3 символов")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            authRepository.updateProfile(
                displayName = name,
                username = _uiState.value.username.trim(),
                bio = _uiState.value.bio.trim().ifEmpty { null },
            ).onSuccess { onSuccess() }
             .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

}

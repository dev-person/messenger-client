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

data class ProfileEditUiState(
    val phone: String = "",
    val displayName: String = "",
    val username: String = "",
    val bio: String = "",
    val avatarUrl: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileEditUiState())
    val uiState: StateFlow<ProfileEditUiState> = _uiState.asStateFlow()

    init {
        // Загружаем текущий профиль при открытии экрана
        viewModelScope.launch {
            authRepository.currentUser.collect { user ->
                user?.let {
                    _uiState.value = _uiState.value.copy(
                        phone = it.phone,
                        displayName = it.displayName,
                        username = it.username,
                        bio = it.bio ?: "",
                        avatarUrl = it.avatarUrl,
                    )
                }
            }
        }
    }

    fun onDisplayNameChange(value: String) {
        _uiState.value = _uiState.value.copy(displayName = value, error = null, saved = false)
    }

    fun onUsernameChange(value: String) {
        // Разрешаем только строчные буквы, цифры и нижнее подчёркивание
        val cleaned = value.filter { it.isLetterOrDigit() || it == '_' }.lowercase()
        _uiState.value = _uiState.value.copy(username = cleaned, error = null, saved = false)
    }

    fun onBioChange(value: String) {
        _uiState.value = _uiState.value.copy(bio = value, saved = false)
    }

    fun save() {
        val name = _uiState.value.displayName.trim()
        if (name.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "Укажите ваше имя")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            authRepository.updateProfile(
                displayName = name,
                username = _uiState.value.username.trim(),
                bio = _uiState.value.bio.trim().ifEmpty { null },
            ).onSuccess {
                _uiState.value = _uiState.value.copy(isLoading = false, saved = true)
            }.onFailure {
                _uiState.value = _uiState.value.copy(isLoading = false, error = it.message)
            }
        }
    }

    // Загружаем аватар на сервер — принимаем сжатые байты и расширение (jpg/png)
    fun uploadAvatar(imageBytes: ByteArray, extension: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            authRepository.uploadAvatar(imageBytes, extension)
                .onSuccess { user ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        avatarUrl = user.avatarUrl,
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Не удалось загрузить фото",
                    )
                }
        }
    }

    fun logout(onLogout: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onLogout()
        }
    }
}

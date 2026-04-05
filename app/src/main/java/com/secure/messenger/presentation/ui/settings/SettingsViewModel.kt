package com.secure.messenger.presentation.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class SettingsUiState(
    val notificationsEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
) : ViewModel() {

    // Хранилище настроек — простой SharedPreferences
    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(loadFromPrefs())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Читаем сохранённые настройки при старте
    private fun loadFromPrefs() = SettingsUiState(
        notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS, true),
        soundEnabled         = prefs.getBoolean(KEY_SOUND, true),
        vibrationEnabled     = prefs.getBoolean(KEY_VIBRATION, true),
    )

    fun toggleNotifications() {
        val newValue = !_uiState.value.notificationsEnabled
        prefs.edit().putBoolean(KEY_NOTIFICATIONS, newValue).apply()
        _uiState.value = _uiState.value.copy(notificationsEnabled = newValue)
    }

    fun toggleSound() {
        val newValue = !_uiState.value.soundEnabled
        prefs.edit().putBoolean(KEY_SOUND, newValue).apply()
        _uiState.value = _uiState.value.copy(soundEnabled = newValue)
    }

    fun toggleVibration() {
        val newValue = !_uiState.value.vibrationEnabled
        prefs.edit().putBoolean(KEY_VIBRATION, newValue).apply()
        _uiState.value = _uiState.value.copy(vibrationEnabled = newValue)
    }

    companion object {
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_SOUND         = "sound_enabled"
        private const val KEY_VIBRATION     = "vibration_enabled"
    }
}

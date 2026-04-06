package com.secure.messenger.presentation.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.lifecycle.ViewModel
import com.secure.messenger.utils.UpdateManager
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
    @ApplicationContext private val appContext: Context,
    private val updateManager: UpdateManager,
) : ViewModel() {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(loadFromPrefs())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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

    /** Проверяет обновление вручную и показывает Toast с результатом */
    suspend fun checkForUpdate(activityContext: Context) {
        val info = updateManager.checkForUpdate()
        if (info != null) {
            // Обновление найдено — скачиваем и устанавливаем
            Toast.makeText(activityContext, "Скачивание v${info.versionName}…", Toast.LENGTH_SHORT).show()
            updateManager.downloadAndInstall(info.downloadUrl!!, activityContext)
        } else {
            Toast.makeText(activityContext, "У вас последняя версия", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_SOUND         = "sound_enabled"
        private const val KEY_VIBRATION     = "vibration_enabled"
    }
}

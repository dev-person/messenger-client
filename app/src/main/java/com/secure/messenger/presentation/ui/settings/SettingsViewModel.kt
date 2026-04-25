package com.secure.messenger.presentation.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.messenger.data.local.dao.ChatDao
import com.secure.messenger.data.local.dao.MessageDao
import com.secure.messenger.data.remote.api.MessengerApi
import com.secure.messenger.domain.repository.ChatRepository
import com.secure.messenger.data.remote.api.SessionDto
import com.secure.messenger.domain.repository.AuthRepository
import com.secure.messenger.presentation.theme.AppColorScheme
import com.secure.messenger.presentation.theme.ChatWallpaper
import com.secure.messenger.presentation.theme.ThemePreferences
import com.secure.messenger.utils.CryptoManager
import com.secure.messenger.utils.LegacyKeyManager
import com.secure.messenger.utils.LocalKeyStore
import com.secure.messenger.utils.UpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val notificationsEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val colorScheme: AppColorScheme = AppColorScheme.CLASSIC,
    val wallpaper: ChatWallpaper = ChatWallpaper.NONE,
    val wallpaperBlur: Int = 0,
    // Password dialog
    val showPasswordDialog: Boolean = false,
    val hasExistingPassword: Boolean = false,
    val oldPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val passwordError: String? = null,
    val passwordLoading: Boolean = false,
    // Sessions
    val sessions: List<SessionDto> = emptyList(),
    val sessionsLoading: Boolean = false,
    val showSessionsDialog: Boolean = false,
    // Unlock
    val showUnlockDialog: Boolean = false,
    val unlockPassword: String = "",
    val unlockError: String? = null,
    val unlockLoading: Boolean = false,
    /** true = на сервере есть пароль, но локальный ключ случайный (пользователь пропустил ввод). */
    val messagesLocked: Boolean = false,
    // Delete key OTP
    val showDeleteKeyOtp: Boolean = false,
    val deleteKeyOtpCode: String = "",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val updateManager: UpdateManager,
    private val authRepository: AuthRepository,
    private val cryptoManager: CryptoManager,
    private val localKeyStore: LocalKeyStore,
    private val legacyKeyManager: LegacyKeyManager,
    private val api: MessengerApi,
    private val chatRepository: ChatRepository,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
) : ViewModel() {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(loadFromPrefs())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // Ключ случайный (не из пароля) — проверяем на сервере, задан ли пароль.
        // Если задан — показываем «Разблокировать». Если нет — ничего.
        if (localKeyStore.hasKeyPair() && !localKeyStore.isKeyFromPassword()) {
            viewModelScope.launch {
                val locked = runCatching {
                    api.getPasswordStatus().data?.hasPassword == true
                }.getOrDefault(false)
                _uiState.value = _uiState.value.copy(messagesLocked = locked)
            }
        }
    }

    private fun loadFromPrefs() = SettingsUiState(
        notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS, true),
        soundEnabled         = prefs.getBoolean(KEY_SOUND, true),
        vibrationEnabled     = prefs.getBoolean(KEY_VIBRATION, true),
        colorScheme          = ThemePreferences.colorScheme.value,
        wallpaper            = ThemePreferences.wallpaper.value,
        wallpaperBlur        = ThemePreferences.wallpaperBlur.value,
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

    /**
     * Применяет новую цветовую схему. Сам reveal-анимацию здесь НЕ запускаем —
     * она требует доступа к Android View для захвата снимка старого UI в
     * Bitmap, а это можно сделать только из Composable (через LocalView).
     * Триггер живёт в SettingsScreen и вызывает [ThemeTransition.startReveal]
     * ДО того как мы здесь поменяем схему.
     */
    fun setColorScheme(scheme: AppColorScheme) {
        val current = _uiState.value.colorScheme
        if (current == scheme) return
        ThemePreferences.setColorScheme(scheme)
        _uiState.value = _uiState.value.copy(colorScheme = scheme)
    }

    fun setWallpaper(wp: ChatWallpaper) {
        ThemePreferences.setWallpaper(wp)
        _uiState.value = _uiState.value.copy(wallpaper = wp)
    }

    fun setWallpaperBlur(value: Int) {
        ThemePreferences.setWallpaperBlur(value)
        _uiState.value = _uiState.value.copy(wallpaperBlur = value)
    }

    /**
     * Проверяет обновление вручную. Если найдено — UpdateManager обновит свой
     * StateFlow `updateAvailable`, и UpdateDialog в MainActivity автоматически
     * покажется с прогрессом скачивания и кнопкой отмены.
     */
    suspend fun checkForUpdate(activityContext: Context) {
        val info = updateManager.checkForUpdate()
        if (info == null) {
            Toast.makeText(activityContext, "У вас последняя версия", Toast.LENGTH_SHORT).show()
        }
        // Если info != null — диалог покажется автоматически через наблюдение updateAvailable
    }

    // ── Sessions ─────────────────────────────────────────────────────────────

    fun showSessions() {
        _uiState.value = _uiState.value.copy(showSessionsDialog = true, sessionsLoading = true)
        viewModelScope.launch {
            runCatching { api.getSessions().data.orEmpty() }
                .onSuccess { sessions ->
                    _uiState.value = _uiState.value.copy(sessions = sessions, sessionsLoading = false)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(sessionsLoading = false)
                    Toast.makeText(appContext, "Не удалось загрузить сессии", Toast.LENGTH_SHORT).show()
                }
        }
    }

    fun dismissSessions() {
        _uiState.value = _uiState.value.copy(showSessionsDialog = false)
    }

    fun terminateSession(sessionId: String) {
        viewModelScope.launch {
            runCatching { api.terminateSession(sessionId) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        sessions = _uiState.value.sessions.filter { it.id != sessionId }
                    )
                }
                .onFailure {
                    Toast.makeText(appContext, "Не удалось завершить сессию", Toast.LENGTH_SHORT).show()
                }
        }
    }

    fun terminateAllOtherSessions() {
        viewModelScope.launch {
            runCatching { api.terminateOtherSessions() }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        sessions = _uiState.value.sessions.filter { it.isCurrent }
                    )
                    Toast.makeText(appContext, "Все другие сессии завершены", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(appContext, "Ошибка", Toast.LENGTH_SHORT).show()
                }
        }
    }

    // ── Unlock old messages ────────────────────────────────────────────────

    fun showUnlockDialog() {
        _uiState.value = _uiState.value.copy(
            showUnlockDialog = true, unlockPassword = "", unlockError = null,
        )
    }

    fun dismissUnlockDialog() {
        _uiState.value = _uiState.value.copy(showUnlockDialog = false)
    }

    fun onUnlockPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(unlockPassword = value, unlockError = null)
    }

    fun unlockMessages() {
        val password = _uiState.value.unlockPassword
        if (password.isEmpty()) {
            _uiState.value = _uiState.value.copy(unlockError = "Введите пароль")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(unlockLoading = true)

            authRepository.verifyPassword(password)
                .onSuccess {
                    val user = authRepository.currentUser.first()
                    val phone = user?.phone ?: return@launch
                    val userId = user.id

                    // Выводим ключ из пароля
                    val (publicKey, privateKey) = cryptoManager.deriveKeyPairFromPassword(phone, password)
                    val publicKeyBase64 = Base64.encodeToString(publicKey, Base64.NO_WRAP)
                    val privateKeyBase64 = Base64.encodeToString(privateKey, Base64.NO_WRAP)
                    localKeyStore.saveKeyPair(publicKeyBase64, privateKeyBase64, fromPassword = true)
                    localKeyStore.setOwner(userId)

                    authRepository.updateProfile(displayName = "", username = "", bio = null)

                    // Скачиваем с сервера legacy-ключи — для расшифровки сообщений,
                    // зашифрованных более ранними (до текущего пароля) ключами
                    legacyKeyManager.downloadAndSaveLegacyKeys(phone, password)

                    // Пересинхронизируем — decryptOrKeepExisting сохранит ранее
                    // расшифрованные сообщения и попробует расшифровать неудачные новым ключом
                    chatRepository.syncChats(userId)
                    val chats = api.getChats().data.orEmpty()
                    for (chat in chats) {
                        runCatching { chatRepository.fetchMessages(chat.id) }
                    }

                    _uiState.value = _uiState.value.copy(
                        showUnlockDialog = false,
                        unlockLoading = false,
                        messagesLocked = false,
                    )
                    Toast.makeText(appContext, "Сообщения разблокированы", Toast.LENGTH_SHORT).show()
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        unlockError = e.message ?: "Неверный пароль",
                        unlockLoading = false,
                    )
                }
        }
    }

    fun deleteKeyFromSettings() {
        // Запрашиваем OTP
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(unlockLoading = true, unlockError = null)
            val user = authRepository.currentUser.first()
            val phone = user?.phone ?: return@launch
            authRepository.requestOtp(phone)
            _uiState.value = _uiState.value.copy(
                unlockLoading = false,
                showDeleteKeyOtp = true,
                deleteKeyOtpCode = "",
            )
        }
    }

    fun onDeleteKeyOtpChange(value: String) {
        _uiState.value = _uiState.value.copy(deleteKeyOtpCode = value, unlockError = null)
    }

    fun confirmDeleteKeyFromSettings() {
        val code = _uiState.value.deleteKeyOtpCode
        if (code.length != 6) {
            _uiState.value = _uiState.value.copy(unlockError = "Введите 6-значный код")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(unlockLoading = true)
            authRepository.deletePassword(code)
                .onSuccess {
                    val (publicKey, privateKey) = cryptoManager.generateKeyPair()
                    val publicKeyBase64 = Base64.encodeToString(publicKey, Base64.NO_WRAP)
                    val privateKeyBase64 = Base64.encodeToString(privateKey, Base64.NO_WRAP)
                    localKeyStore.saveKeyPair(publicKeyBase64, privateKeyBase64)
                    authRepository.updateProfile(displayName = "", username = "", bio = null)

                    _uiState.value = _uiState.value.copy(
                        showUnlockDialog = false,
                        showDeleteKeyOtp = false,
                        unlockLoading = false,
                        messagesLocked = false,
                    )
                    Toast.makeText(appContext, "Ключ удалён. Задайте новый пароль", Toast.LENGTH_LONG).show()
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        unlockError = e.message ?: "Неверный код",
                        unlockLoading = false,
                    )
                }
        }
    }

    fun cancelDeleteKeyOtp() {
        _uiState.value = _uiState.value.copy(showDeleteKeyOtp = false, unlockError = null)
    }

    // ── Password ─────────────────────────────────────────────────────────────

    fun showChangePasswordDialog() {
        _uiState.value = _uiState.value.copy(
            showPasswordDialog = true,
            hasExistingPassword = false,
            oldPassword = "",
            newPassword = "",
            confirmPassword = "",
            passwordError = null,
        )
        // Проверяем на сервере, задан ли пароль
        viewModelScope.launch {
            val has = runCatching {
                api.getPasswordStatus().data?.hasPassword == true
            }.getOrDefault(false)
            _uiState.value = _uiState.value.copy(hasExistingPassword = has)
        }
    }

    fun dismissChangePasswordDialog() {
        _uiState.value = _uiState.value.copy(showPasswordDialog = false)
    }

    fun onOldPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(oldPassword = value, passwordError = null)
    }

    fun onNewPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(newPassword = value, passwordError = null)
    }

    fun onConfirmPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(confirmPassword = value, passwordError = null)
    }

    fun changePassword() {
        val state = _uiState.value
        if (state.newPassword.length < 8) {
            _uiState.value = state.copy(passwordError = "Минимум 8 символов")
            return
        }
        if (state.newPassword != state.confirmPassword) {
            _uiState.value = state.copy(passwordError = "Пароли не совпадают")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(passwordLoading = true)

            val oldPwd = state.oldPassword.ifEmpty { null }
            authRepository.setPassword(state.newPassword, oldPwd)
                .onSuccess {
                    // Получаем телефон текущего пользователя
                    val user = authRepository.currentUser.first()
                    val phone = user?.phone ?: return@launch

                    // Приватный ключ ДО смены пароля — его нужно сохранить как
                    // legacy, чтобы старые сообщения (зашифрованные им) можно
                    // было расшифровать после ротации ключа.
                    val prevPrivateKey = localKeyStore.getPrivateKey()

                    // Генерируем новый ключ из телефон+новый_пароль
                    val (publicKey, privateKey) = cryptoManager.deriveKeyPairFromPassword(phone, state.newPassword)
                    val publicKeyBase64 = Base64.encodeToString(publicKey, Base64.NO_WRAP)
                    val privateKeyBase64 = Base64.encodeToString(privateKey, Base64.NO_WRAP)
                    localKeyStore.saveKeyPair(publicKeyBase64, privateKeyBase64, fromPassword = true)

                    // Загружаем новый публичный ключ на сервер
                    authRepository.updateProfile(displayName = "", username = "", bio = null)

                    // Ротация legacy-blob:
                    //  - Если был старый пароль — существующий blob зашифрован им,
                    //    перешифровываем его новым + добавляем prevPrivateKey.
                    //  - Если это первая установка пароля (oldPwd=null) — prevPrivateKey
                    //    это случайный ключ из «беспарольного» периода, его надо
                    //    сохранить как legacy.
                    if (prevPrivateKey != null) {
                        if (oldPwd != null) {
                            legacyKeyManager.rotateLegacyKeysOnPasswordChange(
                                phone = phone,
                                oldPassword = oldPwd,
                                newPassword = state.newPassword,
                                currentPrivateKeyBase64 = prevPrivateKey,
                            )
                        } else {
                            legacyKeyManager.uploadCurrentKeyAsLegacy(
                                currentRandomPrivateKeyBase64 = prevPrivateKey,
                                phone = phone,
                                password = state.newPassword,
                            )
                        }
                    }

                    _uiState.value = _uiState.value.copy(
                        showPasswordDialog = false,
                        passwordLoading = false,
                    )
                    Toast.makeText(appContext, "Пароль изменён", Toast.LENGTH_SHORT).show()
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        passwordError = e.message ?: "Ошибка",
                        passwordLoading = false,
                    )
                }
        }
    }

    companion object {
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_SOUND         = "sound_enabled"
        private const val KEY_VIBRATION     = "vibration_enabled"
    }
}

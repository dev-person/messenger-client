package com.secure.messenger.presentation.ui.auth

import android.content.Context
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.messenger.data.repository.OtpCooldownException
import com.secure.messenger.data.repository.OtpVerifyException
import com.secure.messenger.data.repository.PasswordException
import com.secure.messenger.di.AuthTokenProvider
import com.secure.messenger.domain.repository.AuthRepository
import com.secure.messenger.utils.CryptoManager
import com.secure.messenger.utils.LegacyKeyManager
import com.secure.messenger.utils.LocalKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

enum class AuthStep { PHONE, OTP, PASSWORD }

// Страна с телефонным кодом
data class Country(
    val name: String,
    val dialCode: String,
    val flag: String,
    /** Длина номера БЕЗ кода страны (только цифры) */
    val phoneLength: Int = 10,
    /** Маска для отображения: # = цифра, пробел/скобки/дефис — разделители */
    val phoneMask: String = "### ### ## ##",
)

/** Применяет маску к строке цифр. # заменяется на цифру по порядку. */
fun applyPhoneMask(digits: String, mask: String): String {
    val sb = StringBuilder()
    var idx = 0
    for (ch in mask) {
        if (idx >= digits.length) break
        if (ch == '#') {
            sb.append(digits[idx++])
        } else {
            sb.append(ch)
        }
    }
    return sb.toString()
}

val COUNTRIES = listOf(
    Country("Россия",        "+7",    "🇷🇺", 10, "(###) ###-##-##"),
    Country("Казахстан",     "+7",    "🇰🇿", 10, "(###) ###-##-##"),
    Country("Украина",       "+380",  "🇺🇦", 9,  "(##) ###-##-##"),
    Country("Беларусь",      "+375",  "🇧🇾", 9,  "(##) ###-##-##"),
    Country("Узбекистан",    "+998",  "🇺🇿", 9,  "(##) ###-##-##"),
    Country("Грузия",        "+995",  "🇬🇪", 9,  "(###) ##-##-##"),
    Country("Армения",       "+374",  "🇦🇲", 8,  "(##) ###-###"),
    Country("Азербайджан",   "+994",  "🇦🇿", 9,  "(##) ###-##-##"),
    Country("США",           "+1",    "🇺🇸", 10, "(###) ###-####"),
    Country("Великобритания","+44",   "🇬🇧", 10, "#### ######"),
    Country("Германия",      "+49",   "🇩🇪", 10, "### #######"),
    Country("Франция",       "+33",   "🇫🇷", 9,  "# ## ## ## ##"),
    Country("Турция",        "+90",   "🇹🇷", 10, "(###) ###-##-##"),
    Country("Израиль",       "+972",  "🇮🇱", 9,  "##-###-####"),
    Country("ОАЭ",           "+971",  "🇦🇪", 9,  "##-###-####"),
    Country("Индия",         "+91",   "🇮🇳", 10, "##### #####"),
    Country("Китай",         "+86",   "🇨🇳", 11, "### #### ####"),
    Country("Бразилия",      "+55",   "🇧🇷", 11, "(##) #####-####"),
    Country("Польша",        "+48",   "🇵🇱", 9,  "### ### ###"),
    Country("Финляндия",     "+358",  "🇫🇮", 9,  "## ### ####"),
)

data class AuthUiState(
    val step: AuthStep = AuthStep.PHONE,
    // Выбранная страна
    val country: Country = COUNTRIES.first(),
    // Номер без кода страны
    val phoneNumber: String = "",
    // Показать диалог выбора страны
    val showCountryPicker: Boolean = false,
    val countrySearch: String = "",
    // OTP
    val otp: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    // Обратный отсчёт до повторной отправки (секунды)
    val resendCountdown: Int = 0,
    // Password
    val password: String = "",
    val passwordConfirm: String = "",
    /** true = у пользователя уже задан пароль (ввести), false = новый пароль (задать). */
    val hasPassword: Boolean = false,
    // Delete key confirmation
    val deleteKeyOtp: String = "",
    val showDeleteKeyOtpInput: Boolean = false,
) {
    // Полный номер для API
    val fullPhone: String get() = country.dialCode + phoneNumber.trimStart('0')
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val authRepository: AuthRepository,
    private val cryptoManager: CryptoManager,
    private val localKeyStore: LocalKeyStore,
    private val legacyKeyManager: LegacyKeyManager,
    private val tokenProvider: AuthTokenProvider,
) : ViewModel() {

    /**
     * SharedPreferences для хранения времени окончания кулдауна OTP.
     * Сохраняет timestamp (мс) до которого нельзя повторно запрашивать код.
     * Переживает перезапуск приложения — пользователь не может обойти кулдаун
     * закрытием и повторным открытием приложения.
     */
    private val cooldownPrefs = appContext.getSharedPreferences("otp_cooldown", Context.MODE_PRIVATE)
    private companion object {
        const val KEY_COOLDOWN_EXPIRY = "cooldown_expiry_ms"
    }

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null

    init {
        // Восстанавливаем кулдаун после перезапуска приложения
        restoreCooldownIfActive()
    }

    // ── Ввод телефона ──────────────────────────────────────────────────────────

    fun onPhoneNumberChange(number: String) {
        _uiState.value = _uiState.value.copy(phoneNumber = number, error = null)
    }

    fun onCountrySelected(country: Country) {
        _uiState.value = _uiState.value.copy(
            country = country,
            showCountryPicker = false,
            countrySearch = "",
            error = null,
        )
    }

    fun onShowCountryPicker() {
        _uiState.value = _uiState.value.copy(showCountryPicker = true)
    }

    fun onDismissCountryPicker() {
        _uiState.value = _uiState.value.copy(showCountryPicker = false, countrySearch = "")
    }

    fun onCountrySearch(query: String) {
        _uiState.value = _uiState.value.copy(countrySearch = query)
    }

    // ── OTP ───────────────────────────────────────────────────────────────────

    fun onOtpChange(otp: String) {
        _uiState.value = _uiState.value.copy(otp = otp, error = null)
    }

    fun backToPhone() {
        // Не сбрасываем countdown — сервер не позволит отправить раньше времени
        _uiState.value = _uiState.value.copy(
            step = AuthStep.PHONE,
            otp = "",
            error = null,
        )
    }

    // ── Запрос OTP ────────────────────────────────────────────────────────────

    fun requestOtp() {
        if (_uiState.value.resendCountdown > 0) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            authRepository.requestOtp(_uiState.value.fullPhone)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(step = AuthStep.OTP, otp = "")
                    startResendCountdown(300)
                }
                .onFailure { e ->
                    if (e is OtpCooldownException) {
                        // Сервер говорит сколько ждать — показываем отсчёт и переходим к вводу кода
                        _uiState.value = _uiState.value.copy(step = AuthStep.OTP, otp = "", error = null)
                        startResendCountdown(e.retryAfterSeconds)
                    } else {
                        _uiState.value = _uiState.value.copy(error = e.message)
                    }
                }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun resendOtp() {
        if (_uiState.value.resendCountdown > 0) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, otp = "")
            authRepository.requestOtp(_uiState.value.fullPhone)
                .onSuccess { startResendCountdown(300) }
                .onFailure { e ->
                    if (e is OtpCooldownException) {
                        startResendCountdown(e.retryAfterSeconds)
                    } else {
                        _uiState.value = _uiState.value.copy(error = e.message)
                    }
                }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    /**
     * Восстанавливает кулдаун из SharedPreferences если он ещё активен.
     * Вызывается при создании ViewModel (в т.ч. после перезапуска приложения).
     * Не переключает на экран OTP — номер телефона при перезапуске не сохранён.
     * Кнопка «Получить код» будет заблокирована пока таймер тикает.
     */
    private fun restoreCooldownIfActive() {
        val expiryMs = cooldownPrefs.getLong(KEY_COOLDOWN_EXPIRY, 0)
        val remainingMs = expiryMs - System.currentTimeMillis()
        if (remainingMs > 1_000) {
            val remainingSec = (remainingMs / 1_000).toInt()
            startResendCountdownInternal(remainingSec)
        }
    }

    private fun startResendCountdown(seconds: Int) {
        // Сохраняем время окончания кулдауна — переживёт перезапуск приложения
        cooldownPrefs.edit()
            .putLong(KEY_COOLDOWN_EXPIRY, System.currentTimeMillis() + seconds * 1_000L)
            .apply()
        startResendCountdownInternal(seconds)
    }

    private fun startResendCountdownInternal(seconds: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (s in seconds downTo 1) {
                _uiState.value = _uiState.value.copy(resendCountdown = s)
                delay(1000)
            }
            _uiState.value = _uiState.value.copy(resendCountdown = 0)
            // Кулдаун истёк — очищаем сохранённое значение
            cooldownPrefs.edit().remove(KEY_COOLDOWN_EXPIRY).apply()
        }
    }

    // ── Подтверждение OTP ─────────────────────────────────────────────────────

    fun verifyOtp(onSuccess: (isNewUser: Boolean) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            authRepository.verifyOtp(_uiState.value.fullPhone, _uiState.value.otp)
                .onSuccess { result ->
                    cooldownPrefs.edit().remove(KEY_COOLDOWN_EXPIRY).apply()
                    countdownJob?.cancel()

                    val userId = result.user.id
                    val serverPublicKey = result.user.publicKey

                    // Если на устройстве есть ключи ЭТОГО пользователя
                    // И локальный публичный ключ совпадает с серверным —
                    // ключ актуален, пароль не нужен
                    if (localKeyStore.hasKeyPair() && localKeyStore.isOwner(userId)) {
                        val localPublicKey = localKeyStore.getPublicKey()
                        if (localPublicKey != null && localPublicKey == serverPublicKey) {
                            authRepository.updateProfile(displayName = "", username = "", bio = null)
                            onSuccess(false)
                            return@launch
                        }
                        // Ключ устарел (пароль сменили на другом устройстве) — очищаем
                        localKeyStore.clear()
                    }

                    // Ключи от другого пользователя — очищаем
                    if (localKeyStore.hasKeyPair() && !localKeyStore.isOwner(userId)) {
                        localKeyStore.clear()
                    }

                    // Нет ключей → нужен пароль (ввести существующий или задать новый)
                    _uiState.value = _uiState.value.copy(
                        step = AuthStep.PASSWORD,
                        hasPassword = result.hasPassword,
                        password = "",
                        passwordConfirm = "",
                        error = null,
                    )
                }
                .onFailure { e ->
                    when {
                        e is OtpVerifyException && e.errorCode == "too_many_attempts" -> {
                            _uiState.value = _uiState.value.copy(otp = "", error = e.message)
                        }
                        e is OtpVerifyException && e.errorCode == "invalid_code" -> {
                            _uiState.value = _uiState.value.copy(otp = "", error = e.message)
                        }
                        else -> {
                            _uiState.value = _uiState.value.copy(error = e.message)
                        }
                    }
                }

            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    // ── Пароль шифрования ────────────────────────────────────────────────────

    fun onPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(password = value, error = null)
    }

    fun onPasswordConfirmChange(value: String) {
        _uiState.value = _uiState.value.copy(passwordConfirm = value, error = null)
    }

    /**
     * Подтверждение пароля: если hasPassword=true → верифицируем на сервере,
     * иначе → устанавливаем новый. Затем генерируем ключ из телефон+пароль.
     */
    fun submitPassword(onSuccess: (isNewUser: Boolean) -> Unit) {
        val state = _uiState.value
        val password = state.password

        if (state.hasPassword) {
            // Ввод существующего пароля
            if (password.isEmpty()) {
                _uiState.value = state.copy(error = "Введите пароль")
                return
            }
        } else {
            // Установка нового пароля
            if (password.length < 8) {
                _uiState.value = state.copy(error = "Минимум 8 символов")
                return
            }
            if (password != state.passwordConfirm) {
                _uiState.value = state.copy(error = "Пароли не совпадают")
                return
            }
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val serverResult = if (state.hasPassword) {
                authRepository.verifyPassword(password)
            } else {
                authRepository.setPassword(password)
            }

            serverResult
                .onSuccess {
                    val kdfVersion = if (state.hasPassword) {
                        // Новое устройство: скачиваем legacy-ключи. Возвращаемая
                        // версия указывает каким KDF (PBKDF2 v1 / Argon2id v2)
                        // выводился identity-keypair на других устройствах —
                        // должны использовать тот же, иначе keypair не сойдётся.
                        legacyKeyManager.downloadAndSaveLegacyKeys(state.fullPhone, password)
                    } else {
                        // Первичная установка пароля → всегда v2 (Argon2id).
                        LegacyKeyManager.KdfVersion.V2_ARGON2ID
                    }
                    generateKeyFromPassword(state.fullPhone, password, kdfVersion)
                    onSuccess(!state.hasPassword)
                }
                .onFailure { e ->
                    val msg = when (e) {
                        is PasswordException -> e.message ?: "Ошибка"
                        else -> e.message ?: "Нет связи с сервером"
                    }
                    _uiState.value = _uiState.value.copy(error = msg)
                }

            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    /**
     * Пропуск пароля — генерируем случайный ключ. Старые сообщения будут скрыты.
     * Только для новых пользователей (hasPassword=false).
     */
    fun skipPassword(onSuccess: (isNewUser: Boolean) -> Unit) {
        viewModelScope.launch {
            val (publicKey, privateKey) = cryptoManager.generateKeyPair()
            val publicKeyBase64 = Base64.encodeToString(publicKey, Base64.NO_WRAP)
            val privateKeyBase64 = Base64.encodeToString(privateKey, Base64.NO_WRAP)
            localKeyStore.saveKeyPair(publicKeyBase64, privateKeyBase64)
            saveKeyOwner()

            authRepository.updateProfile(displayName = "", username = "", bio = null)
                .onFailure { Timber.e(it, "Не удалось загрузить публичный ключ") }

            onSuccess(true)
        }
    }

    /**
     * Шаг 1: запросить OTP для подтверждения удаления ключа.
     */
    fun deleteEncryptionKey() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            authRepository.requestOtp(_uiState.value.fullPhone)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        showDeleteKeyOtpInput = true,
                        deleteKeyOtp = "",
                        error = null,
                    )
                }
                .onFailure { e ->
                    if (e is OtpCooldownException) {
                        // Код уже отправлен — показываем ввод
                        _uiState.value = _uiState.value.copy(
                            showDeleteKeyOtpInput = true,
                            deleteKeyOtp = "",
                            error = null,
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(error = e.message)
                    }
                }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun onDeleteKeyOtpChange(value: String) {
        _uiState.value = _uiState.value.copy(deleteKeyOtp = value, error = null)
    }

    /**
     * Шаг 2: подтвердить OTP и удалить ключ.
     */
    fun confirmDeleteKey() {
        val code = _uiState.value.deleteKeyOtp
        if (code.length != 6) {
            _uiState.value = _uiState.value.copy(error = "Введите 6-значный код")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            authRepository.deletePassword(code)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        hasPassword = false,
                        password = "",
                        passwordConfirm = "",
                        showDeleteKeyOtpInput = false,
                        error = null,
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun cancelDeleteKey() {
        _uiState.value = _uiState.value.copy(showDeleteKeyOtpInput = false, error = null)
    }

    /**
     * Генерирует детерминированную пару ключей X25519 из телефон+пароль
     * и загружает публичный ключ на сервер.
     */
    private suspend fun generateKeyFromPassword(
        phone: String,
        password: String,
        kdfVersion: LegacyKeyManager.KdfVersion,
    ) {
        // Identity-keypair выводится той же KDF, которой шифруется legacy-blob
        // на сервере — иначе на разных устройствах одного юзера будут разные
        // ключи и переписка перестанет расшифровываться.
        val (publicKey, privateKey) = legacyKeyManager.deriveIdentityKeypair(
            kdfVersion, phone, password,
        )
        val publicKeyBase64 = Base64.encodeToString(publicKey, Base64.NO_WRAP)
        val privateKeyBase64 = Base64.encodeToString(privateKey, Base64.NO_WRAP)
        localKeyStore.saveKeyPair(publicKeyBase64, privateKeyBase64, fromPassword = true)
        saveKeyOwner()

        authRepository.updateProfile(displayName = "", username = "", bio = null)
            .onFailure { Timber.e(it, "Не удалось загрузить публичный ключ") }
    }

    /** Привязывает ключ к текущему пользователю. */
    private suspend fun saveKeyOwner() {
        val user = authRepository.currentUser.first()
        user?.id?.let { localKeyStore.setOwner(it) }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
    }
}

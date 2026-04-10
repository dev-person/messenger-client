package com.secure.messenger.presentation.ui.auth

import android.content.Context
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.messenger.data.repository.OtpCooldownException
import com.secure.messenger.di.AuthTokenProvider
import com.secure.messenger.domain.repository.AuthRepository
import com.secure.messenger.utils.CryptoManager
import com.secure.messenger.utils.LocalKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

enum class AuthStep { PHONE, OTP }

// Страна с телефонным кодом
data class Country(
    val name: String,
    val dialCode: String,
    val flag: String,
)

val COUNTRIES = listOf(
    Country("Россия",        "+7",    "🇷🇺"),
    Country("Казахстан",     "+7",    "🇰🇿"),
    Country("Украина",       "+380",  "🇺🇦"),
    Country("Беларусь",      "+375",  "🇧🇾"),
    Country("Узбекистан",    "+998",  "🇺🇿"),
    Country("Грузия",        "+995",  "🇬🇪"),
    Country("Армения",       "+374",  "🇦🇲"),
    Country("Азербайджан",   "+994",  "🇦🇿"),
    Country("США",           "+1",    "🇺🇸"),
    Country("Великобритания","+44",   "🇬🇧"),
    Country("Германия",      "+49",   "🇩🇪"),
    Country("Франция",       "+33",   "🇫🇷"),
    Country("Турция",        "+90",   "🇹🇷"),
    Country("Израиль",       "+972",  "🇮🇱"),
    Country("ОАЭ",           "+971",  "🇦🇪"),
    Country("Индия",         "+91",   "🇮🇳"),
    Country("Китай",         "+86",   "🇨🇳"),
    Country("Бразилия",      "+55",   "🇧🇷"),
    Country("Польша",        "+48",   "🇵🇱"),
    Country("Финляндия",     "+358",  "🇫🇮"),
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

            val isNewUser = !localKeyStore.hasKeyPair()

            authRepository.verifyOtp(_uiState.value.fullPhone, _uiState.value.otp)
                .onSuccess {
                    // Верификация успешна — очищаем сохранённый кулдаун
                    cooldownPrefs.edit().remove(KEY_COOLDOWN_EXPIRY).apply()
                    countdownJob?.cancel()
                    ensureKeyPairExists()
                    onSuccess(isNewUser)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }

            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    /**
     * Генерирует пару ключей X25519 при первом входе.
     * Публичный ключ загружается на сервер для E2E-шифрования.
     */
    private suspend fun ensureKeyPairExists() {
        if (localKeyStore.hasKeyPair()) return

        val (publicKey, privateKey) = cryptoManager.generateKeyPair()
        val publicKeyBase64 = Base64.encodeToString(publicKey, Base64.NO_WRAP)
        val privateKeyBase64 = Base64.encodeToString(privateKey, Base64.NO_WRAP)
        localKeyStore.saveKeyPair(publicKeyBase64, privateKeyBase64)

        // Загружаем публичный ключ, чтобы другие могли нам писать
        authRepository.updateProfile(displayName = "", username = "", bio = null)
            .onFailure { Timber.e(it, "Не удалось загрузить публичный ключ") }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
    }
}

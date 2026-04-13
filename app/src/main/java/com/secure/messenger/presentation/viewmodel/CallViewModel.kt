package com.secure.messenger.presentation.viewmodel

import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.messenger.data.local.dao.UserDao
import com.secure.messenger.data.remote.webrtc.WebRtcCallState
import com.secure.messenger.data.remote.webrtc.WebRtcManager
import com.secure.messenger.domain.model.Call
import com.secure.messenger.domain.model.CallState
import com.secure.messenger.domain.model.CallType
import com.secure.messenger.domain.repository.CallRepository
import com.secure.messenger.domain.usecase.StartCallUseCase
import com.secure.messenger.service.MessagingService
import com.secure.messenger.utils.SoundManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack
import timber.log.Timber
import javax.inject.Inject

data class CallUiState(
    val call: Call? = null,
    val isMuted: Boolean = false,
    val isCameraOn: Boolean = true,
    val isSpeakerOn: Boolean = false,
    val connectionState: WebRtcCallState = WebRtcCallState.IDLE,
)

@HiltViewModel
class CallViewModel @Inject constructor(
    private val callRepository: CallRepository,
    private val startCallUseCase: StartCallUseCase,
    private val userDao: UserDao,
    @ApplicationContext private val context: Context,
    val webRtcManager: WebRtcManager,
    private val soundManager: SoundManager,
) : ViewModel() {

    val localVideoTrack: StateFlow<VideoTrack?> = webRtcManager.localVideoTrackFlow
    val remoteVideoTrack: StateFlow<VideoTrack?> = webRtcManager.remoteVideoTrack
    val webRtcState: StateFlow<WebRtcCallState> = webRtcManager.callState

    // Exposes the active call for AppNavHost to observe and navigate to CallScreen
    val activeCallState: StateFlow<Call?> = callRepository.activeCall
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private val _peerDisplayName = MutableStateFlow("")
    val peerDisplayName: StateFlow<String> = _peerDisplayName.asStateFlow()

    private val _peerAvatarUrl = MutableStateFlow<String?>(null)
    val peerAvatarUrl: StateFlow<String?> = _peerAvatarUrl.asStateFlow()

    // true если этот юзер сам инициировал звонок (нужно для гудка ringback —
    // его проигрывают только инициатору). false для входящих звонков.
    @Volatile private var isOutgoingCall = false

    init {
        // Keep uiState.call in sync with repository's activeCall
        viewModelScope.launch {
            callRepository.activeCall.collect { call ->
                _uiState.value = _uiState.value.copy(call = call)
                // For incoming calls, resolve caller's display name + avatar automatically
                if (call != null && call.state == CallState.RINGING) {
                    isOutgoingCall = false
                    resolvePeer(call.callerId)
                }
            }
        }

        // Гудок исходящего вызова: проигрываем только когда мы сами звоним
        // (isOutgoingCall = true). Запускается при CALLING / CONNECTING,
        // останавливается на CONNECTED / ENDED / IDLE. Для входящих звонков
        // (isOutgoingCall = false) гудок никогда не запускается — там играет
        // рингтон системного уведомления.
        viewModelScope.launch {
            webRtcState.collect { state ->
                val shouldRingback = isOutgoingCall && (
                    state == WebRtcCallState.CALLING ||
                    state == WebRtcCallState.CONNECTING
                )
                if (shouldRingback) {
                    soundManager.startOutgoingRingback()
                } else {
                    soundManager.stopOutgoingRingback()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Страховка: если ViewModel разрушается во время ringback (например,
        // навигация назад с экрана звонка) — обязательно гасим звук.
        soundManager.stopOutgoingRingback()
    }

    /**
     * Resolves a user ID to their display name and avatar URL from the local DB.
     * Called when CallScreen opens (for both outgoing and incoming calls).
     */
    fun resolvePeer(userId: String) {
        if (userId.isBlank()) return
        viewModelScope.launch {
            val user = userDao.getById(userId) ?: return@launch
            val name = user.displayName.ifEmpty { user.username }.ifEmpty { userId }
            _peerDisplayName.value = name
            _peerAvatarUrl.value = user.avatarUrl
        }
    }

    fun startCall(userId: String, type: CallType) {
        // Помечаем что МЫ инициировали звонок — нужно для гудка ringback,
        // его проигрывают только инициатору пока удалённая сторона не ответит.
        isOutgoingCall = true
        viewModelScope.launch {
            startCallUseCase(userId, type)
                .onFailure { e -> Timber.e(e, "Failed to start call") }
        }
    }

    fun acceptCall(callId: String) {
        cancelCallNotification()
        viewModelScope.launch {
            callRepository.acceptCall(callId)
                .onFailure { e -> Timber.e(e, "Failed to accept call") }
        }
    }

    fun hangUp() {
        cancelCallNotification()
        viewModelScope.launch {
            val callId = _uiState.value.call?.id ?: return@launch
            callRepository.hangUp(callId)
            _uiState.value = CallUiState()
        }
    }

    fun declineCall() {
        cancelCallNotification()
        viewModelScope.launch {
            val callId = _uiState.value.call?.id ?: return@launch
            callRepository.declineCall(callId)
            _uiState.value = CallUiState()
        }
    }

    fun toggleMute() {
        val muted = !_uiState.value.isMuted
        webRtcManager.toggleMute(muted)
        _uiState.value = _uiState.value.copy(isMuted = muted)
    }

    fun toggleCamera() {
        val on = !_uiState.value.isCameraOn
        webRtcManager.toggleCamera(on)
        _uiState.value = _uiState.value.copy(isCameraOn = on)
    }

    fun toggleSpeaker() {
        val on = !_uiState.value.isSpeakerOn
        webRtcManager.setSpeakerOn(on)
        _uiState.value = _uiState.value.copy(isSpeakerOn = on)
    }

    fun switchCamera() = webRtcManager.switchCamera()

    private fun cancelCallNotification() {
        context.getSystemService(NotificationManager::class.java)
            .cancel(MessagingService.NOTIF_ID_CALL)
    }
}

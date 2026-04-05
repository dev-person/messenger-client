package com.secure.messenger.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.messenger.data.remote.webrtc.WebRtcCallState
import com.secure.messenger.data.remote.webrtc.WebRtcManager
import com.secure.messenger.domain.model.Call
import com.secure.messenger.domain.model.CallType
import com.secure.messenger.domain.repository.CallRepository
import com.secure.messenger.domain.usecase.StartCallUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val webRtcManager: WebRtcManager,
) : ViewModel() {

    val localVideoTrack: StateFlow<VideoTrack?> = webRtcManager.localVideoTrackFlow
    val remoteVideoTrack: StateFlow<VideoTrack?> = webRtcManager.remoteVideoTrack
    val webRtcState: StateFlow<WebRtcCallState> = webRtcManager.callState

    // Exposes the active call for AppNavHost to observe and navigate to CallScreen
    val activeCallState: StateFlow<Call?> = callRepository.activeCall
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    init {
        // Keep uiState.call in sync with repository's activeCall
        viewModelScope.launch {
            callRepository.activeCall.collect { call ->
                _uiState.value = _uiState.value.copy(call = call)
            }
        }
    }

    fun startCall(userId: String, type: CallType) {
        viewModelScope.launch {
            startCallUseCase(userId, type)
                .onFailure { e -> Timber.e(e, "Failed to start call") }
        }
    }

    fun acceptCall(callId: String) {
        viewModelScope.launch {
            callRepository.acceptCall(callId)
                .onFailure { e -> Timber.e(e, "Failed to accept call") }
        }
    }

    fun hangUp() {
        viewModelScope.launch {
            val callId = _uiState.value.call?.id ?: return@launch
            callRepository.hangUp(callId)
            _uiState.value = CallUiState()
        }
    }

    fun declineCall() {
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
}

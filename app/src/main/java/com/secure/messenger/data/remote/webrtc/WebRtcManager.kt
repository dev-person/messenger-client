package com.secure.messenger.data.remote.webrtc

import android.content.Context
import com.secure.messenger.data.remote.websocket.SignalingClient
import com.secure.messenger.data.remote.websocket.SignalingEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages WebRTC peer connections for audio/video calls.
 *
 * Security:
 *   - DTLS-SRTP is enforced by default in the WebRTC library (all media is encrypted).
 *   - ICE credentials are exchanged over the encrypted WebSocket signaling channel.
 *   - Only TURN/STUN with TLS endpoints should be used in production.
 *
 * Call flow — outgoing:
 *   startOutgoingCall(callId, peerId, isVideo)
 *     → builds PeerConnection → adds tracks → creates offer → sends via SignalingClient
 *
 * Call flow — incoming:
 *   1. [SignalingEvent.Offer] arrives → offer SDP is buffered; peerUserId set in SignalingClient
 *   2. acceptIncomingCall(callId, isVideo)
 *        → builds PeerConnection → adds tracks → processes buffered offer → creates answer
 */
@Singleton
class WebRtcManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val signalingClient: SignalingClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val eglBase: EglBase = EglBase.create()

    private lateinit var peerConnectionFactory: PeerConnectionFactory

    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null

    // Offer SDP buffered while waiting for the user to accept the incoming call
    private var pendingOfferSdp: String? = null

    private val _callState = MutableStateFlow(WebRtcCallState.IDLE)
    val callState: StateFlow<WebRtcCallState> = _callState.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrackFlow: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    // ── Initialisation ────────────────────────────────────────────────────────

    fun initialize() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        listenToSignalingEvents()
    }

    // ── Signaling listener ────────────────────────────────────────────────────

    private fun listenToSignalingEvents() {
        scope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.Offer -> handleRemoteOffer(event)
                    is SignalingEvent.Answer -> handleRemoteAnswer(event.sdp)
                    is SignalingEvent.IceCandidate -> addRemoteIceCandidate(event.candidate, event.sdpMid, event.sdpMLineIndex)
                    is SignalingEvent.CallEnded -> endCall()
                    else -> Unit
                }
            }
        }
    }

    // ── Outgoing call ─────────────────────────────────────────────────────────

    fun startOutgoingCall(callId: String, peerId: String, isVideo: Boolean) {
        _callState.value = WebRtcCallState.CALLING
        buildPeerConnection(callId)
        if (isVideo) addVideoTrack()
        addAudioTrack()
        createOffer(callId, peerId, isVideo)
    }

    private fun createOffer(callId: String, peerId: String, isVideo: Boolean) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
                scope.launch { signalingClient.sendOffer(callId, peerId, isVideo, sdp.description) }
            }
        }, constraints)
    }

    // ── Incoming call ─────────────────────────────────────────────────────────

    /**
     * Called when the user taps "Accept" on an incoming call.
     *
     * Builds the PeerConnection and adds local media tracks. If the remote offer has
     * already arrived (buffered in [pendingOfferSdp]), it is processed immediately.
     * Otherwise, it will be processed as soon as it arrives via [handleRemoteOffer].
     */
    fun acceptIncomingCall(callId: String, isVideo: Boolean) {
        _callState.value = WebRtcCallState.CONNECTING
        buildPeerConnection(callId)
        if (isVideo) addVideoTrack()
        addAudioTrack()

        val buffered = pendingOfferSdp
        if (buffered != null) {
            pendingOfferSdp = null
            processRemoteOffer(buffered)
        }
    }

    /**
     * Handles an incoming offer from the remote peer.
     *
     * If the user has already accepted (PeerConnection exists), the offer is processed
     * immediately. Otherwise, the SDP is buffered until [acceptIncomingCall] is called.
     * The peer ID (callerId) has already been stored in [SignalingClient] by [parseAndEmit].
     */
    private fun handleRemoteOffer(event: SignalingEvent.Offer) {
        if (peerConnection != null) {
            // User accepted before the offer arrived — process immediately
            processRemoteOffer(event.sdp)
        } else {
            // Buffer until the user accepts
            pendingOfferSdp = event.sdp
        }
    }

    private fun processRemoteOffer(remoteSdp: String) {
        val offer = SessionDescription(SessionDescription.Type.OFFER, remoteSdp)
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), offer)
        createAnswer()
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
                scope.launch { signalingClient.sendAnswer(sdp.description) }
                // CONNECTED state is set by onConnectionChange when the ICE handshake completes
            }
        }, constraints)
    }

    private fun handleRemoteAnswer(remoteSdp: String) {
        val answer = SessionDescription(SessionDescription.Type.ANSWER, remoteSdp)
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), answer)
        // CONNECTED state is set by onConnectionChange when the ICE handshake completes
    }

    private fun addRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    // ── PeerConnection builder ────────────────────────────────────────────────

    private fun buildPeerConnection(callId: String) {
        val iceServers = listOf(
            // Production: use your own TURN server with TLS
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            // DTLS-SRTP is the default and cannot be disabled in modern WebRTC
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            buildPeerConnectionObserver(callId),
        )
    }

    private fun buildPeerConnectionObserver(callId: String) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            scope.launch {
                signalingClient.sendIceCandidate(
                    callId = callId,
                    candidate = candidate.sdp,
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                )
            }
        }

        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) {
                _remoteVideoTrack.value = track
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            _callState.value = when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> WebRtcCallState.CONNECTED
                PeerConnection.PeerConnectionState.DISCONNECTED,
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED -> WebRtcCallState.ENDED
                else -> _callState.value
            }
            Timber.d("WebRTC connection state: $state")
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) = Unit
    }

    // ── Media tracks ──────────────────────────────────────────────────────────

    private fun addAudioTrack() {
        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("echoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("noiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("autoGainControl", "true"))
        })
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track", audioSource)
        peerConnection?.addTrack(localAudioTrack!!)
    }

    private fun addVideoTrack() {
        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoCapturer = buildCameraCapturer()
        val videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)

        videoCapturer?.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        videoCapturer?.startCapture(1280, 720, 30)

        localVideoTrack = peerConnectionFactory.createVideoTrack("video_track", videoSource)
        _localVideoTrack.value = localVideoTrack
        peerConnection?.addTrack(localVideoTrack!!)
    }

    private fun buildCameraCapturer(): VideoCapturer {
        val enumerator = Camera2Enumerator(context)
        // Prefer front camera for video calls
        return enumerator.deviceNames
            .firstOrNull { enumerator.isFrontFacing(it) }
            ?.let { enumerator.createCapturer(it, null) }
            ?: enumerator.createCapturer(enumerator.deviceNames.first(), null)
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    fun toggleMute(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun toggleCamera(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        (videoCapturer as? org.webrtc.CameraVideoCapturer)?.switchCamera(null)
    }

    fun setSpeakerOn(on: Boolean) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        manager.isSpeakerphoneOn = on
    }

    // ── Teardown ──────────────────────────────────────────────────────────────

    fun endCall() {
        pendingOfferSdp = null

        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null

        localVideoTrack?.dispose()
        localVideoTrack = null
        _localVideoTrack.value = null

        localAudioTrack?.dispose()
        localAudioTrack = null

        _remoteVideoTrack.value = null

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null

        _callState.value = WebRtcCallState.IDLE
    }

    fun dispose() {
        endCall()
        peerConnectionFactory.dispose()
        eglBase.release()
    }
}

enum class WebRtcCallState { IDLE, CALLING, RINGING, CONNECTING, CONNECTED, ENDED }

/** No-op SdpObserver base class to reduce boilerplate. */
open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) { Timber.e("SDP create failure: $error") }
    override fun onSetFailure(error: String) { Timber.e("SDP set failure: $error") }
}

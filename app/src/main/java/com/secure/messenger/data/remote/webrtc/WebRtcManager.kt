package com.secure.messenger.data.remote.webrtc

import android.content.Context
import com.secure.messenger.data.remote.api.MessengerApi
import com.secure.messenger.data.remote.websocket.SignalingClient
import com.secure.messenger.data.remote.websocket.SignalingEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * Управляет WebRTC-соединениями для аудио/видео звонков.
 *
 * Безопасность:
 *   - DTLS-SRTP включён по умолчанию в WebRTC (весь медиа-трафик зашифрован).
 *   - ICE-учётные данные передаются по зашифрованному WebSocket-каналу.
 *   - В продакшене следует использовать TURN/STUN с TLS.
 *
 * Исходящий звонок:
 *   startOutgoingCall(callId, peerId, isVideo)
 *     → создаёт PeerConnection → добавляет треки → создаёт offer → отправляет через SignalingClient
 *
 * Входящий звонок:
 *   1. Приходит [SignalingEvent.Offer] → SDP буферизуется; peerUserId сохраняется в SignalingClient
 *   2. acceptIncomingCall(callId, isVideo)
 *        → создаёт PeerConnection → добавляет треки → обрабатывает буферизованный offer → создаёт answer
 */
@Singleton
class WebRtcManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val signalingClient: SignalingClient,
    private val api: MessengerApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val eglBase: EglBase = EglBase.create()

    private lateinit var peerConnectionFactory: PeerConnectionFactory

    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null

    // SDP-предложение, буферизованное до принятия входящего звонка пользователем
    private var pendingOfferSdp: String? = null

    // ICE-кандидаты, пришедшие до создания PeerConnection или установки remote description
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    // Завершает звонок, если ICE-согласование не завершилось за 30 секунд.
    // Без TURN-сервера симметричный NAT может привести к бесконечному зависанию.
    private var connectionTimeoutJob: Job? = null

    private val _callState = MutableStateFlow(WebRtcCallState.IDLE)
    val callState: StateFlow<WebRtcCallState> = _callState.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrackFlow: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private var factoryInitialized = false

    // Кешированный список ICE-серверов с временем загрузки
    private var cachedIceServers: List<PeerConnection.IceServer>? = null
    private var iceServersFetchedAt: Long = 0L
    private val ICE_CACHE_TTL_MS = 5 * 60 * 1000L  // 5 минут

    init {
        listenToSignalingEvents()
        // Загружаем ICE-конфигурацию с сервера заранее
        scope.launch { fetchIceServers() }
    }

    // ── Инициализация ─────────────────────────────────────────────────────────

    /**
     * Лениво инициализирует [PeerConnectionFactory] при первом вызове.
     * EGL-контекст создаётся однократно в [eglBase].
     */
    private fun ensureFactoryInitialized() {
        if (factoryInitialized) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        factoryInitialized = true
    }

    /**
     * Доступ к разделяемому [PeerConnectionFactory] и [eglBase] для других
     * WebRTC-классов (например, GroupCallManager). Гарантирует что factory
     * инициализирован.
     */
    fun sharedFactory(): PeerConnectionFactory {
        ensureFactoryInitialized()
        return peerConnectionFactory
    }

    /** Доступ для GroupCallManager — те же ICE-серверы (с TTL-кешем). */
    suspend fun fetchIceServersForGroupCall(): List<PeerConnection.IceServer> {
        refreshIceServersIfNeeded()
        return cachedIceServers?.takeIf { it.isNotEmpty() } ?: fallbackIceServers()
    }

    // ── Обработка сигнальных событий ──────────────────────────────────────────

    private fun listenToSignalingEvents() {
        scope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.Offer -> handleRemoteOffer(event)
                    is SignalingEvent.Answer -> handleRemoteAnswer(event.sdp)
                    is SignalingEvent.IceCandidate -> addRemoteIceCandidate(event.candidate, event.sdpMid, event.sdpMLineIndex)
                    is SignalingEvent.CallEnded -> handleRemoteCallEnded()
                    else -> Unit
                }
            }
        }
    }

    // ── Исходящий звонок ──────────────────────────────────────────────────────

    fun startOutgoingCall(callId: String, peerId: String, isVideo: Boolean) {
        ensureFactoryInitialized()
        setAudioMode(active = true)
        _callState.value = WebRtcCallState.CALLING
        pendingIceCandidates.clear()
        remoteDescriptionSet = false
        startConnectionTimeout()
        scope.launch {
            refreshIceServersIfNeeded()
            buildPeerConnection(callId)
            if (isVideo) addVideoTrack()
            addAudioTrack()
            createOffer(callId, peerId, isVideo)
        }
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

    // ── Входящий звонок ───────────────────────────────────────────────────────

    /**
     * Вызывается когда пользователь нажимает «Принять» на входящем звонке.
     *
     * Создаёт PeerConnection и добавляет локальные медиа-треки. Если удалённый offer
     * уже пришёл (буферизован в [pendingOfferSdp]), обрабатывается сразу.
     * Иначе — обработается при поступлении через [handleRemoteOffer].
     */
    fun acceptIncomingCall(callId: String, isVideo: Boolean) {
        ensureFactoryInitialized()
        setAudioMode(active = true)
        _callState.value = WebRtcCallState.CONNECTING
        startConnectionTimeout()
        scope.launch {
            refreshIceServersIfNeeded()
            buildPeerConnection(callId)
            if (isVideo) addVideoTrack()
            addAudioTrack()

            val buffered = pendingOfferSdp
            if (buffered != null) {
                pendingOfferSdp = null
                processRemoteOffer(buffered)
            }
        }
    }

    /**
     * Обрабатывает входящий offer от удалённого пира.
     *
     * Если пользователь уже принял (PeerConnection существует) — обрабатывается сразу.
     * Иначе SDP буферизуется до вызова [acceptIncomingCall].
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
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                drainPendingIceCandidates()
            }
        }, offer)
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
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                drainPendingIceCandidates()
            }
        }, answer)
    }

    private fun addRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        if (peerConnection != null && remoteDescriptionSet) {
            peerConnection?.addIceCandidate(ice)
        } else {
            // Буферизуем до момента, когда PeerConnection и remote description будут готовы
            Timber.d("Буферизуем ICE-кандидат (pc=${peerConnection != null}, rd=$remoteDescriptionSet)")
            pendingIceCandidates.add(ice)
        }
    }

    /** Применяет все буферизованные ICE-кандидаты к PeerConnection */
    private fun drainPendingIceCandidates() {
        if (pendingIceCandidates.isNotEmpty()) {
            Timber.d("Применяем ${pendingIceCandidates.size} буферизованных ICE-кандидатов")
            pendingIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
            pendingIceCandidates.clear()
        }
    }

    // ── Построение PeerConnection ─────────────────────────────────────────────

    /**
     * Загружает ICE-серверы (STUN/TURN) с сервера.
     * При ошибке — используем fallback (публичные серверы).
     */
    private suspend fun fetchIceServers() {
        val servers = runCatching {
            api.getIceServers().data?.flatMap { dto ->
                dto.urls.map { url ->
                    PeerConnection.IceServer.builder(url).apply {
                        if (!dto.username.isNullOrEmpty()) setUsername(dto.username)
                        if (!dto.credential.isNullOrEmpty()) setPassword(dto.credential)
                    }.createIceServer()
                }
            }
        }.getOrNull()
        if (!servers.isNullOrEmpty()) {
            cachedIceServers = servers
            iceServersFetchedAt = System.currentTimeMillis()
        }
        Timber.d("ICE серверы загружены: ${cachedIceServers?.size ?: 0}")
    }

    /** Перезагружает ICE-серверы если кеш устарел */
    private suspend fun refreshIceServersIfNeeded() {
        val age = System.currentTimeMillis() - iceServersFetchedAt
        if (cachedIceServers == null || age > ICE_CACHE_TTL_MS) {
            fetchIceServers()
        }
    }

    /** Возвращает fallback ICE-серверы если серверный конфиг недоступен */
    private fun fallbackIceServers() = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
    )

    private fun buildPeerConnection(callId: String) {
        // ICE-серверы уже обновлены вызывающей корутиной (refreshIceServersIfNeeded)
        val iceServers = cachedIceServers?.takeIf { it.isNotEmpty() } ?: fallbackIceServers()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Принудительно используем relay (TURN) если STUN не проходит
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            buildPeerConnectionObserver(callId),
        )
    }

    private fun startConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            // 5 минут — стандартное время дозвона как в Telegram/WhatsApp.
            // Раньше было 30 сек, и звонок сам завершался даже если собеседник
            // просто тянулся к телефону.
            delay(CALL_RING_TIMEOUT_MS)
            if (_callState.value != WebRtcCallState.CONNECTED &&
                _callState.value != WebRtcCallState.IDLE &&
                _callState.value != WebRtcCallState.ENDED) {
                Timber.w("WebRTC: connection timeout after ${CALL_RING_TIMEOUT_MS / 1000}s, ending call")
                handleRemoteCallEnded()
            }
        }
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
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    connectionTimeoutJob?.cancel()
                    WebRtcCallState.CONNECTED
                }
                PeerConnection.PeerConnectionState.DISCONNECTED,
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED -> {
                    // peerConnection is nulled by endCall() before close() is called, so if it's
                    // still non-null here, the remote side dropped the connection unexpectedly.
                    if (peerConnection != null) WebRtcCallState.ENDED else _callState.value
                }
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

    // ── Медиа-треки ──────────────────────────────────────────────────────────

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
        // Захват в 720p@30 — потолок качества для 1:1 звонка. Реальное
        // отдаваемое видео полностью адаптивно: Google Congestion Control
        // (TWCC) на каждой стороне меряет пропускную способность канала и
        // сам подстраивает битрейт/разрешение в обе стороны.
        //
        // Раньше захватывали 960x540@24 потому что 720p@30 при cap'е 800 kbps
        // «душил» аудио — encoder отбирал bandwidth, congestion control не
        // успевал. Теперь cap поднят до 1.5 Mbps (стандарт LiveKit/Meet/Zoom
        // для 720p@30 H.264) — encoder больше не «грызётся» с аудио.
        videoCapturer?.startCapture(1280, 720, 30)

        localVideoTrack = peerConnectionFactory.createVideoTrack("video_track", videoSource)
        _localVideoTrack.value = localVideoTrack
        val sender = peerConnection?.addTrack(localVideoTrack!!)

        // Адаптация под качество сети:
        //  - maxBitrate 1.5 Mbps — потолок при отличной сети;
        //    при плохой WebRTC сам сожмёт до 100-300 kbps;
        //  - degradationPreference=MAINTAIN_FRAMERATE — на просадке режется
        //    разрешение, fps остаётся плавным (лучше «чуть мыльно но плавно»,
        //    чем «чётко но слайд-шоу»).
        sender?.parameters?.let { params ->
            params.encodings.forEach { enc ->
                enc.maxBitrateBps = 1_500_000
                enc.maxFramerate = 30
            }
            params.degradationPreference =
                org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            sender.parameters = params
        }
    }

    private fun buildCameraCapturer(): VideoCapturer {
        val enumerator = Camera2Enumerator(context)
        // Предпочитаем фронтальную камеру для видеозвонков
        return enumerator.deviceNames
            .firstOrNull { enumerator.isFrontFacing(it) }
            ?.let { enumerator.createCapturer(it, null) }
            ?: enumerator.createCapturer(enumerator.deviceNames.first(), null)
    }

    // ── Управление звонком ────────────────────────────────────────────────────

    fun toggleMute(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun toggleCamera(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        (videoCapturer as? org.webrtc.CameraVideoCapturer)?.switchCamera(null)
    }

    // AudioManager.isSpeakerphoneOn deprecated с Android 12 (API 31) в пользу
    // setCommunicationDevice/clearCommunicationDevice. Миграция нетривиальна
    // (нужен fallback и тестирование на разных устройствах) — оставляем
    // существующее поведение и явно подавляем предупреждение, чтобы не шумело
    // в CI-логе. Зарелизим миграцию отдельной задачей вместе с правками
    // аудиомаршрутизации в звонках.
    @Suppress("DEPRECATION")
    fun setSpeakerOn(on: Boolean) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        manager.isSpeakerphoneOn = on
    }

    @Suppress("DEPRECATION")
    private fun setAudioMode(active: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (active) {
            am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        } else {
            am.mode = android.media.AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
        }
    }

    // ── Завершение звонка ─────────────────────────────────────────────────────

    /**
     * Завершает звонок локально. Обнуляет [peerConnection] ДО вызова close(),
     * чтобы колбэк [onConnectionChange](CLOSED) не выставил [WebRtcCallState.ENDED]
     * (иначе CallScreen закроется при следующем открытии).
     */
    fun endCall() {
        connectionTimeoutJob?.cancel()
        val pc = peerConnection
        peerConnection = null   // null first — suppresses spurious ENDED from onConnectionChange
        pendingOfferSdp = null
        pendingIceCandidates.clear()
        remoteDescriptionSet = false

        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null

        localVideoTrack?.dispose()
        localVideoTrack = null
        _localVideoTrack.value = null

        localAudioTrack?.dispose()
        localAudioTrack = null

        _remoteVideoTrack.value = null

        pc?.close()
        pc?.dispose()

        setAudioMode(active = false)
        _callState.value = WebRtcCallState.IDLE
    }

    /**
     * Вызывается когда УДАЛЁННАЯ сторона завершила звонок (через [SignalingEvent.CallEnded]).
     * Выставляет [WebRtcCallState.ENDED], чтобы CallScreen закрылся.
     */
    private fun handleRemoteCallEnded() {
        connectionTimeoutJob?.cancel()
        if (peerConnection == null && pendingOfferSdp == null) return  // no active session, ignore
        val pc = peerConnection
        peerConnection = null
        pendingOfferSdp = null
        pendingIceCandidates.clear()
        remoteDescriptionSet = false

        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null

        localVideoTrack?.dispose()
        localVideoTrack = null
        _localVideoTrack.value = null

        localAudioTrack?.dispose()
        localAudioTrack = null

        _remoteVideoTrack.value = null

        pc?.close()
        pc?.dispose()

        setAudioMode(active = false)
        _callState.value = WebRtcCallState.ENDED
    }

    fun dispose() {
        endCall()
        if (factoryInitialized) peerConnectionFactory.dispose()
        eglBase.release()
    }

    private companion object {
        // Сколько максимум висит дозвон без ответа собеседника. По истечении —
        // звонок автоматически завершается. 5 минут — стандарт Telegram/WhatsApp.
        const val CALL_RING_TIMEOUT_MS = 5 * 60 * 1000L
    }
}

enum class WebRtcCallState { IDLE, CALLING, RINGING, CONNECTING, CONNECTED, ENDED }

/** Базовый SdpObserver без действий — уменьшает шаблонный код. */
open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) { Timber.e("SDP create failure: $error") }
    override fun onSetFailure(error: String) { Timber.e("SDP set failure: $error") }
}

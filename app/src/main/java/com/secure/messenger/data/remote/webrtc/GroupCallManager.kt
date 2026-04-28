package com.secure.messenger.data.remote.webrtc

import android.content.Context
import com.secure.messenger.data.remote.websocket.SignalingClient
import com.secure.messenger.data.remote.websocket.SignalingEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Менеджер mesh P2P звонка до 4 участников.
 *
 * Архитектура:
 *   - Один локальный аудио-трек и один видео-трек, добавляются в КАЖДОЕ
 *     peer connection (в mesh у каждого участника N-1 пиров).
 *   - Сервер только сигналит — медиа DTLS-SRTP идёт между клиентами напрямую.
 *
 * Жизненный цикл:
 *   1. start(callId, isVideo) — захват камеры/микрофона; ждём WS-events
 *      group_call_participant_joined чтобы знать к кому коннектиться.
 *   2. На каждом WS-событии создаём peer connection, шлём offer.
 *   3. На входящем offer — отвечаем answer.
 *   4. ICE-кандидаты сыплются в обе стороны через WS.
 *   5. leave() — закрываем все peers, отпускаем камеру.
 *
 * Адаптация качества под количество участников:
 *   - 2 человека (1 пир) — 720p@30 / 1.5 Mbps на трек
 *   - 3-4 человека (2-3 пира на отправку) — 540p@24 / 600 kbps на трек,
 *     чтобы суммарный аплоад не убивал слабые сети.
 */
@Singleton
class GroupCallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webRtcManager: WebRtcManager,
    private val signalingClient: SignalingClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Состояние одного peer'а (один из участников группового звонка, кроме меня). */
    data class PeerState(
        val userId: String,
        val pc: PeerConnection,
        val remoteVideo: VideoTrack? = null,
        val remoteDescriptionSet: Boolean = false,
        val pendingIce: MutableList<IceCandidate> = mutableListOf(),
    )

    private val peers = ConcurrentHashMap<String, PeerState>()
    private val _participants = MutableStateFlow<Map<String, PeerState>>(emptyMap())
    /** Состояние всех peer'ов (key = userId). UI рендерит сетку из этой Map. */
    val participants: StateFlow<Map<String, PeerState>> = _participants.asStateFlow()

    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null

    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo.asStateFlow()

    private val _state = MutableStateFlow(GroupCallState.IDLE)
    val state: StateFlow<GroupCallState> = _state.asStateFlow()

    /**
     * userId'ы тех участников, от которых сейчас идёт звук (выше порога).
     * Обновляется ~3 раза в секунду через polling RTC stats; используется
     * для подсветки тайла «активный говорящий».
     */
    private val _speakingUserIds = MutableStateFlow<Set<String>>(emptySet())
    val speakingUserIds: StateFlow<Set<String>> = _speakingUserIds.asStateFlow()
    private var statsJob: Job? = null

    private var currentCallId: String? = null
    private var myUserId: String = ""
    private var isVideoCall: Boolean = false
    private var isMuted: Boolean = false
    private var isCameraOn: Boolean = true

    init {
        listenSignaling()
    }

    /** Доступ к общему EglBase.Context — нужен для рендера VideoTrack в UI. */
    fun eglBaseContext(): org.webrtc.EglBase.Context = webRtcManager.eglBase.eglBaseContext

    // ── Публичный API ─────────────────────────────────────────────────────────

    /**
     * Старт/присоединение к групповому звонку. После вызова локальные треки
     * захвачены и готовы; peer connection'ы создаются по мере прихода
     * group_call_participant_joined.
     */
    suspend fun start(callId: String, isVideo: Boolean, myUserId: String) {
        if (currentCallId == callId) return // уже в звонке
        leave() // на всякий случай — закрыть старые ресурсы
        currentCallId = callId
        this.myUserId = myUserId
        isVideoCall = isVideo
        isMuted = false
        isCameraOn = isVideo
        _state.value = GroupCallState.CONNECTING
        webRtcManager.fetchIceServersForGroupCall() // прогреть кеш
        ensureLocalTracks()
        _state.value = GroupCallState.CONNECTED
        startAudioLevelPolling()
    }

    /**
     * Создать peer connection с указанным userId и отправить ему offer.
     *
     * Glare resolution: при mesh-старте оба клиента (мой и его) могут
     * параллельно вызвать connectToPeer друг для друга — один через
     * participant_joined event у уже-в-звонке стороны, другой через
     * /join API на свежем участнике, который сам инициирует подключение
     * ко всем существующим. Если оба сделают setRemoteDescription по
     * пересекающимся offer'ам, медиа-треки рассогласовываются и видео
     * пропадает у одной/обеих сторон. Чтобы избежать этого: инициирует
     * только тот клиент, чей userId лексикографически МЕНЬШЕ. Другая
     * сторона ждёт offer и обрабатывает его через handleOffer.
     */
    suspend fun connectToPeer(userId: String) {
        val callId = currentCallId ?: return
        if (peers.containsKey(userId)) return
        if (myUserId.isNotEmpty() && myUserId >= userId) {
            Timber.d("connectToPeer($userId): skip, my id is greater — peer initiates")
            return
        }
        val pc = createPeer(userId) ?: return
        attachLocalTracks(pc)
        adjustEncodingForGroupSize(pc)

        pc.createOffer(object : SimpleSdpObs() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(SimpleSdpObs(), sdp)
                signalingClient.sendGroupCallOffer(callId, userId, sdp.description)
            }
        }, MediaConstraints())
    }

    /** Удалить peer (другой участник вышел). */
    fun disconnectPeer(userId: String) {
        val peer = peers.remove(userId) ?: return
        runCatching { peer.pc.close() }
        publishParticipants()
    }

    /** Полностью завершить локальный звонок (выйти и освободить ресурсы). */
    fun leave() {
        statsJob?.cancel()
        statsJob = null
        _speakingUserIds.value = emptySet()

        peers.values.forEach { runCatching { it.pc.close() } }
        peers.clear()
        publishParticipants()

        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null
        localVideoTrack = null
        localAudioTrack = null
        _localVideo.value = null

        currentCallId = null
        myUserId = ""
        _state.value = GroupCallState.IDLE
    }

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        localAudioTrack?.setEnabled(!isMuted)
        return isMuted
    }

    fun toggleCamera(): Boolean {
        if (!isVideoCall) return isCameraOn
        isCameraOn = !isCameraOn
        localVideoTrack?.setEnabled(isCameraOn)
        return isCameraOn
    }

    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    // ── Сигналинг ─────────────────────────────────────────────────────────────

    private fun listenSignaling() {
        scope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.GroupCallOffer -> if (event.callId == currentCallId) {
                        handleOffer(event.from, event.sdp)
                    }
                    is SignalingEvent.GroupCallAnswer -> if (event.callId == currentCallId) {
                        handleAnswer(event.from, event.sdp)
                    }
                    is SignalingEvent.GroupCallIce -> if (event.callId == currentCallId) {
                        handleIce(event.from, event.candidate, event.sdpMid, event.sdpMLineIndex)
                    }
                    is SignalingEvent.GroupCallParticipantJoined -> if (event.callId == currentCallId) {
                        // Новый участник — устанавливаем с ним peer connection.
                        scope.launch { connectToPeer(event.userId) }
                    }
                    is SignalingEvent.GroupCallParticipantLeft -> if (event.callId == currentCallId) {
                        disconnectPeer(event.userId)
                    }
                    is SignalingEvent.GroupCallEnded -> if (event.callId == currentCallId) {
                        leave()
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun handleOffer(fromUser: String, sdp: String) {
        val callId = currentCallId ?: return
        ensureLocalTracks()
        // Если peer уже есть — это означает что обе стороны попытались
        // инициировать одновременно. По правилу glare: отклоняем offer
        // от пользователя с большим userId (lexicographic), принимаем от меньшего.
        // Но проще: если уже есть, просто перезаписываем.
        val existing = peers[fromUser]
        if (existing != null) {
            runCatching { existing.pc.close() }
            peers.remove(fromUser)
        }
        val pc = createPeer(fromUser) ?: return
        attachLocalTracks(pc)
        adjustEncodingForGroupSize(pc)

        val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SimpleSdpObs() {
            override fun onSetSuccess() {
                peers[fromUser] = peers[fromUser]!!.copy(remoteDescriptionSet = true)
                drainIce(fromUser)
                pc.createAnswer(object : SimpleSdpObs() {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        pc.setLocalDescription(SimpleSdpObs(), answer)
                        signalingClient.sendGroupCallAnswer(callId, fromUser, answer.description)
                    }
                }, MediaConstraints())
            }
        }, remoteSdp)
    }

    private fun handleAnswer(fromUser: String, sdp: String) {
        val peer = peers[fromUser] ?: run {
            Timber.w("handleAnswer: no peer for $fromUser")
            return
        }
        val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peer.pc.setRemoteDescription(object : SimpleSdpObs() {
            override fun onSetSuccess() {
                peers[fromUser] = peers[fromUser]!!.copy(remoteDescriptionSet = true)
                drainIce(fromUser)
            }
        }, remoteSdp)
    }

    private fun handleIce(fromUser: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        val peer = peers[fromUser]
        if (peer == null) {
            // Кандидаты пришли до peer connection — игнор (партнёр должен начать с offer)
            Timber.d("handleIce: no peer yet for $fromUser, ignoring")
            return
        }
        if (peer.remoteDescriptionSet) {
            peer.pc.addIceCandidate(ice)
        } else {
            peer.pendingIce.add(ice)
        }
    }

    private fun drainIce(fromUser: String) {
        val peer = peers[fromUser] ?: return
        peer.pendingIce.forEach { peer.pc.addIceCandidate(it) }
        peer.pendingIce.clear()
    }

    // ── Локальные треки и Peer Connection ─────────────────────────────────────

    private suspend fun ensureLocalTracks() {
        if (localAudioTrack != null) return
        val factory = webRtcManager.sharedFactory()

        // Включаем APM (audio processing module) явно: эхоподавление,
        // шумоподавление, авто-усиление. По дефолту WebRTC их включает,
        // но без явных constraints поведение зависит от версии нативной
        // libwebrtc — лучше прибить гвоздями, как сделано в WebRtcManager
        // для 1-1 звонков.
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("echoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("noiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("autoGainControl", "true"))
        }
        val audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("group-audio", audioSource)

        if (isVideoCall) {
            val helper = SurfaceTextureHelper.create("GroupCallCapture", webRtcManager.eglBase.eglBaseContext)
            val capturer = buildCameraCapturer()
            val videoSource = factory.createVideoSource(capturer.isScreencast)
            capturer.initialize(helper, context, videoSource.capturerObserver)
            // 540p@24 — стандарт для mesh с 3-4 участниками. Каждый шлёт N-1 копий.
            capturer.startCapture(960, 540, 24)
            videoCapturer = capturer
            localVideoTrack = factory.createVideoTrack("group-video", videoSource)
            _localVideo.value = localVideoTrack
        }
    }

    private fun buildCameraCapturer(): VideoCapturer {
        val enumerator = Camera2Enumerator(context)
        return enumerator.deviceNames
            .firstOrNull { enumerator.isFrontFacing(it) }
            ?.let { enumerator.createCapturer(it, null) }
            ?: enumerator.createCapturer(enumerator.deviceNames.first(), null)
    }

    private suspend fun createPeer(userId: String): PeerConnection? {
        val factory = webRtcManager.sharedFactory()
        val iceServers = webRtcManager.fetchIceServersForGroupCall()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        val callId = currentCallId ?: return null
        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signalingClient.sendGroupCallIce(
                    callId, userId, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex,
                )
            }
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    track.setEnabled(true)
                    val current = peers[userId] ?: return
                    peers[userId] = current.copy(remoteVideo = track)
                    publishParticipants()
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    Timber.w("ICE state $state for peer $userId")
                }
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) = Unit
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) = Unit
            override fun onAddStream(p0: MediaStream) = Unit
            override fun onRemoveStream(p0: MediaStream) = Unit
            override fun onDataChannel(p0: org.webrtc.DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
        }) ?: return null

        peers[userId] = PeerState(userId = userId, pc = pc)
        publishParticipants()
        return pc
    }

    private fun attachLocalTracks(pc: PeerConnection) {
        localAudioTrack?.let { pc.addTrack(it) }
        if (isVideoCall) localVideoTrack?.let { pc.addTrack(it) }
    }

    /**
     * Подкручивает RtpEncodingParameters в зависимости от числа peer'ов.
     * При 1 пире (всего 2 человека в звонке) — 1.5 Mbps / 720p трек.
     * При 2-3 пирах (3-4 в звонке) — 600 kbps / 540p трек, чтобы аплоад
     * (мы шлём ту же камеру в КАЖДОЕ соединение) не убил слабую сеть.
     */
    private fun adjustEncodingForGroupSize(pc: PeerConnection) {
        if (!isVideoCall) return
        val totalPeers = peers.size + 1 // включая только что созданный
        val maxBitrateBps = if (totalPeers >= 2) 600_000 else 1_500_000
        val maxFps = if (totalPeers >= 2) 24 else 30

        pc.senders.forEach { sender ->
            val track = sender.track() ?: return@forEach
            if (track.kind() == "video") {
                val params = sender.parameters
                params.encodings.forEach { enc ->
                    enc.maxBitrateBps = maxBitrateBps
                    enc.maxFramerate = maxFps
                }
                params.degradationPreference =
                    RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                sender.parameters = params
            }
        }
    }

    private fun publishParticipants() {
        _participants.value = HashMap(peers)
    }

    /**
     * Периодически читает RTC stats каждого peer'а, ищет inbound-rtp audio
     * и сохраняет userId'ы тех, у кого audioLevel выше порога. UI подсвечивает
     * тайлы этих юзеров зелёной рамкой («активный говорящий»).
     *
     * Порог 0.05 эмпирически — ниже этого начинает срабатывать на дыхании /
     * шумах фона. Период 300 мс — компромисс плавности подсветки и нагрузки
     * (getStats всё-таки делает синхронный обход внутренних очередей WebRTC).
     */
    private fun startAudioLevelPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (currentCallId != null) {
                val speaking = mutableSetOf<String>()
                val snapshot = peers.toMap()
                for ((userId, peer) in snapshot) {
                    peer.pc.getStats { report ->
                        val isLoud = report.statsMap.values.any { stat ->
                            if (stat.type != "inbound-rtp") return@any false
                            val mediaType = stat.members["mediaType"] as? String
                                ?: stat.members["kind"] as? String
                            if (mediaType != "audio") return@any false
                            val level = (stat.members["audioLevel"] as? Double) ?: 0.0
                            level > 0.05
                        }
                        if (isLoud) speaking.add(userId)
                    }
                }
                // getStats async — даём callback'ам отработать перед публикацией.
                delay(50)
                _speakingUserIds.value = speaking
                delay(250)
            }
            _speakingUserIds.value = emptySet()
        }
    }

    // ── Хелпер: SDP-observer без биллборда NPE ────────────────────────────────

    private open class SimpleSdpObs : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(p0: String?) = Unit
        override fun onSetFailure(p0: String?) = Unit
    }
}

enum class GroupCallState { IDLE, CONNECTING, CONNECTED, ENDED }

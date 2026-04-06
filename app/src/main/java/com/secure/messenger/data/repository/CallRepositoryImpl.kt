package com.secure.messenger.data.repository

import com.secure.messenger.data.local.dao.ChatDao
import com.secure.messenger.data.local.dao.MessageDao
import com.secure.messenger.data.local.entities.MessageEntity
import com.secure.messenger.data.remote.api.MessengerApi
import com.secure.messenger.data.remote.webrtc.WebRtcCallState
import com.secure.messenger.data.remote.webrtc.WebRtcManager
import com.secure.messenger.data.remote.websocket.SignalingClient
import com.secure.messenger.data.remote.websocket.SignalingEvent
import com.secure.messenger.domain.model.Call
import com.secure.messenger.domain.model.CallState
import com.secure.messenger.domain.model.CallType
import com.secure.messenger.domain.model.MessageStatus
import com.secure.messenger.domain.model.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

class CallRepositoryImpl @Inject constructor(
    private val api: MessengerApi,
    private val webRtcManager: WebRtcManager,
    private val signalingClient: SignalingClient,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
) : com.secure.messenger.domain.repository.CallRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _activeCall = MutableStateFlow<Call?>(null)
    override val activeCall: Flow<Call?> = _activeCall.asStateFlow()

    // Время начала соединённого звонка (для подсчёта длительности)
    private var connectedAt: Long? = null

    init {
        // Заполняем _activeCall при входящем звонке
        scope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.IncomingCall -> _activeCall.value = Call(
                        id = event.callId,
                        chatId = "",
                        callerId = event.callerId,
                        calleeId = "me",
                        type = if (event.isVideo) CallType.VIDEO else CallType.AUDIO,
                        state = CallState.RINGING,
                        startedAt = null,
                        endedAt = null,
                    )
                    is SignalingEvent.CallEnded -> {
                        val call = _activeCall.value
                        if (call != null && call.state == CallState.RINGING) {
                            // Звонящий повесил трубку до ответа — пропущенный звонок
                            insertCallMessage(call.callerId, call.type, "📵 Пропущенный звонок")
                            _activeCall.value = null
                        }
                    }
                    else -> Unit
                }
            }
        }
        // Отслеживаем WebRTC-состояние для фиксации момента соединения
        scope.launch {
            webRtcManager.callState.collect { state ->
                if (state == WebRtcCallState.CONNECTED) {
                    connectedAt = System.currentTimeMillis()
                }
            }
        }
    }

    override suspend fun initiateCall(userId: String, type: CallType): Result<Call> = runCatching {
        val callId = UUID.randomUUID().toString()
        val call = Call(
            id = callId, chatId = "", callerId = "me", calleeId = userId,
            type = type, state = CallState.CALLING,
            startedAt = System.currentTimeMillis(), endedAt = null,
        )
        _activeCall.value = call
        webRtcManager.startOutgoingCall(callId, peerId = userId, isVideo = type == CallType.VIDEO)
        call
    }

    override suspend fun acceptCall(callId: String): Result<Unit> = runCatching {
        val call = _activeCall.value ?: error("No active call")
        _activeCall.value = call.copy(state = CallState.CONNECTING)
        webRtcManager.acceptIncomingCall(callId, isVideo = call.type == CallType.VIDEO)
    }

    override suspend fun declineCall(callId: String): Result<Unit> = runCatching {
        val call = _activeCall.value
        signalingClient.sendEndCall(callId)
        _activeCall.value = call?.copy(state = CallState.DECLINED)
        webRtcManager.endCall()
        // Системное сообщение: отклонённый звонок
        if (call != null) {
            val icon = if (call.type == CallType.VIDEO) "📹" else "📞"
            insertCallMessage(call.callerId, call.type, "$icon Отклонённый звонок")
        }
        connectedAt = null
        _activeCall.value = null
    }

    override suspend fun hangUp(callId: String): Result<Unit> = runCatching {
        signalingClient.sendEndCall(callId)
        val call = _activeCall.value
        if (call != null) {
            val duration = connectedAt?.let { ((System.currentTimeMillis() - it) / 1000).toInt() } ?: 0
            _activeCall.value = call.copy(state = CallState.ENDED, durationSeconds = duration)

            // Системное сообщение в чат
            val peerId = if (call.callerId == "me") call.calleeId else call.callerId
            val icon = if (call.type == CallType.VIDEO) "📹" else "📞"
            val text = if (duration > 0) {
                val min = duration / 60
                val sec = duration % 60
                "$icon Звонок · ${String.format("%d:%02d", min, sec)}"
            } else {
                "$icon Исходящий звонок"
            }
            insertCallMessage(peerId, call.type, text)
        }
        webRtcManager.endCall()
        connectedAt = null
        _activeCall.value = null
    }

    // ── Вставка системного сообщения о звонке в чат ────────────────────────

    /**
     * Создаёт системное сообщение о звонке в чате с указанным пользователем.
     * Если чата ещё нет — сообщение не создаётся (звонок без чата).
     */
    private suspend fun insertCallMessage(peerUserId: String, callType: CallType, text: String) {
        val chat = chatDao.getByOtherUserId(peerUserId) ?: return
        messageDao.upsert(MessageEntity(
            id = "call_${UUID.randomUUID()}",
            chatId = chat.id,
            senderId = "system",
            encryptedContent = "",
            decryptedContent = text,
            type = MessageType.SYSTEM.name,
            status = MessageStatus.READ.name,
            timestamp = System.currentTimeMillis(),
            replyToId = null,
            mediaUrl = null,
            isEdited = false,
        ))
    }

    override suspend fun getCallHistory(chatId: String): Result<List<Call>> = runCatching {
        api.getCallHistory(chatId).data.orEmpty().map { dto ->
            Call(
                id = dto.id, chatId = dto.chatId, callerId = "", calleeId = "",
                type = CallType.valueOf(dto.type),
                state = CallState.valueOf(dto.state),
                startedAt = dto.startedAt, endedAt = null,
                durationSeconds = dto.durationSeconds,
            )
        }
    }
}

package com.secure.messenger.data.repository

import com.secure.messenger.data.remote.api.MessengerApi
import com.secure.messenger.data.remote.webrtc.WebRtcCallState
import com.secure.messenger.data.remote.webrtc.WebRtcManager
import com.secure.messenger.data.remote.websocket.SignalingClient
import com.secure.messenger.data.remote.websocket.SignalingEvent
import com.secure.messenger.domain.model.Call
import com.secure.messenger.domain.model.CallState
import com.secure.messenger.domain.model.CallType
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
) : com.secure.messenger.domain.repository.CallRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _activeCall = MutableStateFlow<Call?>(null)
    override val activeCall: Flow<Call?> = _activeCall.asStateFlow()

    // Время начала соединённого звонка (для подсчёта длительности)
    private var connectedAt: Long? = null

    init {
        // Заполняем _activeCall при входящем звонке.
        //
        // Системные сообщения о звонках («Пропущенный», «Звонок завершён · 1:23»)
        // создаются сервером в БД при обработке end_call и приходят сюда обычным
        // путём — через IncomingMessageHandler как SYSTEM-сообщения. Локально
        // ничего не вставляем, иначе будет дублирование.
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
                        connectedAt = null
                        _activeCall.value = null
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
        // Системное сообщение об отклонённом звонке создаст сервер
        // (forwardAsCallEnded → InsertSystemMessage) и пришлёт обоим участникам.
        connectedAt = null
        _activeCall.value = null
    }

    override suspend fun hangUp(callId: String): Result<Unit> = runCatching {
        signalingClient.sendEndCall(callId)
        val call = _activeCall.value
        if (call != null) {
            val duration = connectedAt?.let { ((System.currentTimeMillis() - it) / 1000).toInt() } ?: 0
            _activeCall.value = call.copy(state = CallState.ENDED, durationSeconds = duration)
            // Системное сообщение о завершённом звонке создаст сервер
        }
        webRtcManager.endCall()
        connectedAt = null
        _activeCall.value = null
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

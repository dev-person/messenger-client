package com.secure.messenger.data.remote.websocket

import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** События, приходящие из сигнального WebSocket в остальные части приложения. */
sealed class SignalingEvent {
    data class Offer(val callId: String, val from: String, val sdp: String) : SignalingEvent()
    data class Answer(val sdp: String) : SignalingEvent()
    data class IceCandidate(
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int,
    ) : SignalingEvent()
    data class IncomingCall(val callId: String, val callerId: String, val isVideo: Boolean) : SignalingEvent()
    data class CallEnded(val callId: String) : SignalingEvent()
    data class NewMessage(val json: String) : SignalingEvent()
    data class MessageDeleted(val messageId: String, val chatId: String) : SignalingEvent()
    data class MessageEdited(val messageId: String, val chatId: String, val encryptedContent: String) : SignalingEvent()
    data class MessagesRead(val chatId: String, val readerId: String) : SignalingEvent()
    data class UserStatus(val userId: String, val isOnline: Boolean) : SignalingEvent()
    data class Typing(val chatId: String, val userId: String) : SignalingEvent()
    object Connected : SignalingEvent()
    object Disconnected : SignalingEvent()
}

/**
 * Постоянное WebSocket-подключение к сигнальному / сообщательному серверу.
 *
 * Все сообщения — JSON-конверты: { "type": "...", "payload": { ... } }
 * Подключение аутентифицируется через Bearer-токен в HTTP-заголовке.
 *
 * Маршрутизация: каждое сигнальное сообщение (offer/answer/ice_candidate/end_call)
 * содержит поле "to" с ID целевого пользователя. Сервер пересылает сообщение и добавляет "from".
 * [peerUserId] устанавливается автоматически при отправке или получении offer.
 */
@Singleton
class SignalingClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi,
) {
    private var webSocket: WebSocket? = null

    // Пользователь, с которым идёт текущий звонок. Устанавливается при отправке offer
    // (вызывающая сторона) или при получении offer / incoming_call (принимающая сторона).
    private var peerUserId: String? = null
    private var currentCallId: String? = null

    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    // Состояние подключения — StateFlow с replay, доступен сразу при подписке
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val adapter = moshi.adapter(SignalingMessage::class.java)

    // ── Жизненный цикл подключения ────────────────────────────────────────────

    fun connect(serverUrl: String, authToken: String) {
        if (webSocket != null) return

        val request = Request.Builder()
            .url(serverUrl)
            .addHeader("Authorization", "Bearer $authToken")
            .build()

        webSocket = okHttpClient.newWebSocket(request, createListener())
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }

    private fun createListener() = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
            _isConnected.value = true
            _events.tryEmit(SignalingEvent.Connected)
            Timber.d("Signaling WebSocket connected")
        }

        override fun onMessage(ws: WebSocket, text: String) {
            parseAndEmit(text)
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
            Timber.e(t, "Signaling WebSocket failure")
            webSocket = null
            _isConnected.value = false
            _events.tryEmit(SignalingEvent.Disconnected)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            webSocket = null
            _isConnected.value = false
            _events.tryEmit(SignalingEvent.Disconnected)
        }
    }

    // ── Разбор входящих сообщений ──────────────────────────────────────────────

    private fun parseAndEmit(json: String) {
        val msg = runCatching { adapter.fromJson(json) }.getOrNull() ?: return
        val payload = msg.payload ?: return

        val event = when (msg.type) {
            "offer" -> {
                // Сервер добавляет "from" = callerId при пересылке offer
                val from = payload["from"] as? String ?: ""
                if (from.isNotEmpty()) peerUserId = from
                SignalingEvent.Offer(
                    callId = payload["callId"] as? String ?: "",
                    from = from,
                    sdp = payload["sdp"] as? String ?: return,
                )
            }
            "answer" -> SignalingEvent.Answer(
                sdp = payload["sdp"] as? String ?: return,
            )
            "ice_candidate" -> SignalingEvent.IceCandidate(
                candidate = payload["candidate"] as? String ?: return,
                sdpMid = payload["sdpMid"] as? String,
                sdpMLineIndex = (payload["sdpMLineIndex"] as? Double)?.toInt() ?: 0,
            )
            "incoming_call" -> {
                // Устанавливаем пира, чтобы answer/ICE маршрутизировались даже до прихода offer
                val callerId = payload["callerId"] as? String ?: ""
                if (callerId.isNotEmpty()) peerUserId = callerId
                SignalingEvent.IncomingCall(
                    callId = payload["callId"] as? String ?: "",
                    callerId = callerId,
                    isVideo = payload["isVideo"] as? Boolean ?: false,
                )
            }
            "call_ended" -> SignalingEvent.CallEnded(
                callId = payload["callId"] as? String ?: "",
            )
            "message" -> SignalingEvent.NewMessage(json)
            "message_deleted" -> SignalingEvent.MessageDeleted(
                messageId = payload["messageId"] as? String ?: return,
                chatId = payload["chatId"] as? String ?: return,
            )
            "message_edited" -> SignalingEvent.MessageEdited(
                messageId = payload["id"] as? String ?: return,
                chatId = payload["chatId"] as? String ?: return,
                encryptedContent = payload["encryptedContent"] as? String ?: return,
            )
            "messages_read" -> SignalingEvent.MessagesRead(
                chatId = payload["chatId"] as? String ?: return,
                readerId = payload["readerId"] as? String ?: return,
            )
            "user_status" -> SignalingEvent.UserStatus(
                userId = payload["userId"] as? String ?: return,
                isOnline = payload["isOnline"] as? Boolean ?: false,
            )
            "typing" -> SignalingEvent.Typing(
                chatId = payload["chatId"] as? String ?: return,
                userId = payload["userId"] as? String ?: return,
            )
            else -> {
                Timber.w("Unknown signaling type: ${msg.type}")
                return
            }
        }
        _events.tryEmit(event)
    }

    // ── Отправка ──────────────────────────────────────────────────────────────

    private fun send(type: String, payload: Map<String, Any?>) {
        val msg = SignalingMessage(type = type, payload = payload)
        val json = adapter.toJson(msg)
        webSocket?.send(json)
    }

    /**
     * Отправляет WebRTC offer пользователю [toUserId]. Сохраняет [toUserId] как текущего пира,
     * чтобы последующие answer/ICE/end_call маршрутизировались правильно.
     */
    fun sendOffer(callId: String, toUserId: String, isVideo: Boolean, sdp: String) {
        currentCallId = callId
        peerUserId = toUserId
        send("offer", mapOf(
            "to" to toUserId,
            "callId" to callId,
            "isVideo" to isVideo,
            "sdp" to sdp,
        ))
    }

    fun sendAnswer(sdp: String) {
        val peer = peerUserId ?: run { Timber.w("sendAnswer: peerUserId not set"); return }
        send("answer", mapOf("to" to peer, "sdp" to sdp))
    }

    fun sendIceCandidate(callId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val peer = peerUserId ?: run { Timber.w("sendIceCandidate: peerUserId not set"); return }
        send("ice_candidate", mapOf(
            "to" to peer,
            "callId" to callId,
            "candidate" to candidate,
            "sdpMid" to sdpMid,
            "sdpMLineIndex" to sdpMLineIndex,
        ))
    }

    fun sendEndCall(callId: String) {
        val peer = peerUserId
        peerUserId = null
        currentCallId = null
        if (peer != null) {
            send("end_call", mapOf("to" to peer, "callId" to callId))
        }
    }

    fun sendChatMessage(chatId: String, encryptedContent: String, messageId: String) {
        send("message", mapOf(
            "chatId" to chatId,
            "messageId" to messageId,
            "encryptedContent" to encryptedContent,
        ))
    }
}

data class SignalingMessage(
    val type: String,
    val payload: Map<String, Any?>?,
)

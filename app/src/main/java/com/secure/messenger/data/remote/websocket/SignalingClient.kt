package com.secure.messenger.data.remote.websocket

import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Events emitted from the signaling WebSocket to the rest of the app. */
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
    object Connected : SignalingEvent()
    object Disconnected : SignalingEvent()
}

/**
 * Persistent WebSocket connection to the signaling / messaging server.
 *
 * All messages are JSON envelopes: { "type": "...", "payload": { ... } }
 * The connection is authenticated via Bearer token in the HTTP header.
 *
 * Routing: every signaling message (offer/answer/ice_candidate/end_call) must include
 * a "to" field with the target user ID. The server forwards the message and injects "from".
 * [peerUserId] is set automatically when sending an offer or receiving one.
 */
@Singleton
class SignalingClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi,
) {
    private var webSocket: WebSocket? = null

    // The user we are currently in a call with. Set when sending an offer (caller side)
    // or when receiving an offer / incoming_call (callee side).
    private var peerUserId: String? = null
    private var currentCallId: String? = null

    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    private val adapter = moshi.adapter(SignalingMessage::class.java)

    // ── Connection lifecycle ──────────────────────────────────────────────────

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
            _events.tryEmit(SignalingEvent.Connected)
            Timber.d("Signaling WebSocket connected")
        }

        override fun onMessage(ws: WebSocket, text: String) {
            Timber.d("Signaling ← $text")
            parseAndEmit(text)
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
            Timber.e(t, "Signaling WebSocket failure")
            webSocket = null
            _events.tryEmit(SignalingEvent.Disconnected)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            webSocket = null
            _events.tryEmit(SignalingEvent.Disconnected)
        }
    }

    // ── Message parsing ───────────────────────────────────────────────────────

    private fun parseAndEmit(json: String) {
        val msg = runCatching { adapter.fromJson(json) }.getOrNull() ?: return
        val payload = msg.payload ?: return

        val event = when (msg.type) {
            "offer" -> {
                // Server injects "from" = callerId when forwarding the offer
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
                // Set peer so answer/ICE can be routed back even before the offer arrives
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
            else -> {
                Timber.w("Unknown signaling type: ${msg.type}")
                return
            }
        }
        _events.tryEmit(event)
    }

    // ── Sending ───────────────────────────────────────────────────────────────

    private fun send(type: String, payload: Map<String, Any?>) {
        val msg = SignalingMessage(type = type, payload = payload)
        val json = adapter.toJson(msg)
        Timber.d("Signaling → $json")
        webSocket?.send(json)
    }

    /**
     * Send a WebRTC offer to [toUserId]. Stores [toUserId] as the current peer so that
     * subsequent answer/ICE/end_call messages are routed to the correct recipient.
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

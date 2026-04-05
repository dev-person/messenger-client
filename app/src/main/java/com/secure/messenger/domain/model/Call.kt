package com.secure.messenger.domain.model

data class Call(
    val id: String,
    val chatId: String,
    val callerId: String,
    val calleeId: String,
    val type: CallType,
    val state: CallState,
    val startedAt: Long?,
    val endedAt: Long?,
    val durationSeconds: Int = 0,
)

enum class CallType { AUDIO, VIDEO }

enum class CallState {
    IDLE,
    CALLING,      // Outgoing — waiting for answer
    RINGING,      // Incoming — ringing
    CONNECTING,   // ICE negotiation
    CONNECTED,    // Active call
    ENDED,        // Normal hang up
    MISSED,
    DECLINED,
    FAILED,
}

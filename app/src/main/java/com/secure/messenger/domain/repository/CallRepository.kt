package com.secure.messenger.domain.repository

import com.secure.messenger.domain.model.Call
import com.secure.messenger.domain.model.CallType
import kotlinx.coroutines.flow.Flow

interface CallRepository {
    val activeCall: Flow<Call?>

    suspend fun initiateCall(userId: String, type: CallType): Result<Call>
    suspend fun acceptCall(callId: String): Result<Unit>
    suspend fun declineCall(callId: String): Result<Unit>
    suspend fun hangUp(callId: String): Result<Unit>

    suspend fun getCallHistory(chatId: String): Result<List<Call>>
}

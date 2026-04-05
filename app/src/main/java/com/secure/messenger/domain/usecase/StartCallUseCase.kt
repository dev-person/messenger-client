package com.secure.messenger.domain.usecase

import com.secure.messenger.domain.model.Call
import com.secure.messenger.domain.model.CallType
import com.secure.messenger.domain.repository.CallRepository
import javax.inject.Inject

class StartCallUseCase @Inject constructor(
    private val callRepository: CallRepository,
) {
    suspend operator fun invoke(userId: String, type: CallType): Result<Call> =
        callRepository.initiateCall(userId, type)
}

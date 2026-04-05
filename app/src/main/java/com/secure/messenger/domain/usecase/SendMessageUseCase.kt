package com.secure.messenger.domain.usecase

import com.secure.messenger.domain.model.Message
import com.secure.messenger.domain.repository.ChatRepository
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
) {
    suspend operator fun invoke(chatId: String, content: String): Result<Message> {
        if (content.isBlank()) return Result.failure(IllegalArgumentException("Message cannot be empty"))
        return chatRepository.sendMessage(chatId, content.trim())
    }
}

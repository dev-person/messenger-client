package com.secure.messenger.domain.repository

import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun observeChats(): Flow<List<Chat>>
    fun observeMessages(chatId: String): Flow<List<Message>>

    suspend fun getOrCreateDirectChat(userId: String): Result<Chat>
    suspend fun createGroupChat(title: String, memberIds: List<String>): Result<Chat>

    suspend fun sendMessage(chatId: String, content: String): Result<Message>
    suspend fun deleteMessage(messageId: String): Result<Unit>
    suspend fun editMessage(messageId: String, newContent: String): Result<Message>

    suspend fun syncChats(myUserId: String): Result<Unit>
    suspend fun fetchMessages(chatId: String): Result<Unit>

    suspend fun markAsRead(chatId: String): Result<Unit>
    suspend fun pinChat(chatId: String): Result<Unit>
    suspend fun muteChat(chatId: String, mutedUntil: Long?): Result<Unit>
    suspend fun deleteChat(chatId: String): Result<Unit>
}

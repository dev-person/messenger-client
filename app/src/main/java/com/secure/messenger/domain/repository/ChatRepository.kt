package com.secure.messenger.domain.repository

import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun observeChats(): Flow<List<Chat>>
    fun observeMessages(chatId: String, limit: Int = 50): Flow<List<Message>>

    suspend fun getOrCreateDirectChat(userId: String): Result<Chat>
    suspend fun createGroupChat(title: String, memberIds: List<String>): Result<Chat>

    suspend fun sendMessage(chatId: String, content: String, replyToId: String? = null): Result<Message>

    /**
     * Отправляет голосовое сообщение. Файлы не хранятся на сервере отдельно — байты
     * шифруются и передаются в encryptedContent (base64) обычным сообщением с типом AUDIO.
     * Параметр [waveform] — амплитуды для отображения волнограммы в плеере.
     */
    suspend fun sendVoiceMessage(
        chatId: String,
        audioBytes: ByteArray,
        durationSeconds: Int,
        waveform: IntArray = IntArray(0),
    ): Result<Message>

    /**
     * Отправляет картинку. Аналогично голосовому: байты ужимаются (JPEG ≤ 1280px),
     * упаковываются в JSON, шифруются и идут как обычное сообщение с типом IMAGE.
     * На сервере как файл не хранится.
     */
    suspend fun sendImageMessage(chatId: String, imageData: com.secure.messenger.utils.ImageCodec.ImageData): Result<Message>
    suspend fun deleteMessage(messageId: String): Result<Unit>
    suspend fun editMessage(messageId: String, newContent: String): Result<Message>

    suspend fun syncChats(myUserId: String): Result<Unit>
    suspend fun fetchMessages(chatId: String): Result<Unit>
    suspend fun fetchOlderMessages(chatId: String, beforeTimestamp: Long): Result<Boolean>

    suspend fun markAsRead(chatId: String): Result<Unit>
    suspend fun pinChat(chatId: String): Result<Unit>
    suspend fun muteChat(chatId: String, mutedUntil: Long?): Result<Unit>
    suspend fun deleteChat(chatId: String): Result<Unit>
}

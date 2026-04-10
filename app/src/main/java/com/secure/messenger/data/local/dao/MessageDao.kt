package com.secure.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.secure.messenger.data.local.entities.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /** Последние N сообщений — для начальной загрузки и пагинации */
    @Query("SELECT * FROM (SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT :limit) sub ORDER BY timestamp ASC")
    fun observeMessages(chatId: String, limit: Int): Flow<List<MessageEntity>>

    /** Сообщения старше указанного timestamp — для подгрузки истории */
    @Query("SELECT * FROM (SELECT * FROM messages WHERE chatId = :chatId AND timestamp < :before ORDER BY timestamp DESC LIMIT :limit) sub ORDER BY timestamp ASC")
    suspend fun loadBefore(chatId: String, before: Long, limit: Int): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessage(chatId: String): MessageEntity?

    /** Помечает исходящие сообщения как прочитанные собеседником */
    @Query("UPDATE messages SET status = 'READ' WHERE chatId = :chatId AND senderId != :readerId AND status IN ('SENT', 'DELIVERED')")
    suspend fun markChatMessagesRead(chatId: String, readerId: String)

    /** Все ID не-системных сообщений чата с timestamp >= sinceTimestamp */
    @Query("SELECT id FROM messages WHERE chatId = :chatId AND timestamp >= :sinceTimestamp AND type != 'SYSTEM'")
    suspend fun getMessageIdsSince(chatId: String, sinceTimestamp: Long): List<String>

    /** Все ID не-системных сообщений чата */
    @Query("SELECT id FROM messages WHERE chatId = :chatId AND type != 'SYSTEM'")
    suspend fun getAllMessageIds(chatId: String): List<String>

    /** Обновляет расшифрованный контент и помечает сообщение как отредактированное */
    @Query("UPDATE messages SET encryptedContent = :encrypted, decryptedContent = :decrypted, isEdited = 1 WHERE id = :messageId")
    suspend fun updateContent(messageId: String, encrypted: String, decrypted: String)
}

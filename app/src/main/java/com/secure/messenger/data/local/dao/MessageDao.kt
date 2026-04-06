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

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun observeMessages(chatId: String): Flow<List<MessageEntity>>

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

    /** Обновляет расшифрованный контент и помечает сообщение как отредактированное */
    @Query("UPDATE messages SET encryptedContent = :encrypted, decryptedContent = :decrypted, isEdited = 1 WHERE id = :messageId")
    suspend fun updateContent(messageId: String, encrypted: String, decrypted: String)
}

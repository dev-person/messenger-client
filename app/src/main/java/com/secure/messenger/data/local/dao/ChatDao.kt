package com.secure.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.secure.messenger.data.local.entities.ChatEntity
import com.secure.messenger.data.local.entities.ChatWithLastMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM chats ORDER BY isPinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("""
        SELECT c.*,
               m.id        AS lastMsgId,
               m.senderId  AS lastMsgSenderId,
               m.decryptedContent AS lastMsgContent,
               m.type      AS lastMsgType,
               m.status    AS lastMsgStatus,
               m.timestamp AS lastMsgTimestamp,
               m.isEdited  AS lastMsgIsEdited
        FROM chats c
        LEFT JOIN messages m ON m.id = (
            SELECT id FROM messages WHERE chatId = c.id ORDER BY timestamp DESC LIMIT 1
        )
        ORDER BY c.isPinned DESC, c.updatedAt DESC
    """)
    fun observeAllWithLastMessage(): Flow<List<ChatWithLastMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chat: ChatEntity)

    @Update
    suspend fun update(chat: ChatEntity)

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getById(chatId: String): ChatEntity?

    @Query("UPDATE chats SET unreadCount = 0 WHERE id = :chatId")
    suspend fun clearUnread(chatId: String)

    @Query("UPDATE chats SET isPinned = :pinned WHERE id = :chatId")
    suspend fun setPinned(chatId: String, pinned: Boolean)

    @Query("UPDATE chats SET isMuted = :muted WHERE id = :chatId")
    suspend fun setMuted(chatId: String, muted: Boolean)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteById(chatId: String)

    @Query("SELECT * FROM chats WHERE otherUserId = :userId AND type = 'DIRECT' LIMIT 1")
    suspend fun getByOtherUserId(userId: String): ChatEntity?
}

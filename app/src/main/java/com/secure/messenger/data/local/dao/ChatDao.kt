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

    @Query("SELECT * FROM chats WHERE isHidden = 0 ORDER BY isPinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    // Список чатов: + последнее сообщение через LEFT JOIN на messages,
    // + онлайн-статус собеседника через LEFT JOIN на users (otherUserId).
    // Скрытые чаты (isHidden = 1) исключаем — они остаются в БД как «помнить
    // что юзер скрыл», но в UI не показываются.
    // Для списка чатов НЕ тянем полный decryptedContent медиа-сообщений —
    // картинка в JSON base64 может весить 500-800KB, и Flow при нескольких
    // чатах с фото-last-message эмитит десятки мегабайт, что приводит к
    // OOM-крашу. В UI для IMAGE/AUDIO/VIDEO/FILE мы всё равно показываем
    // плашку «Фото»/«Голосовое сообщение» (см. messagePreviewText).
    @Query("""
        SELECT c.*,
               m.id        AS lastMsgId,
               m.senderId  AS lastMsgSenderId,
               CASE WHEN m.type IN ('IMAGE','AUDIO','VIDEO','FILE')
                    THEN ''
                    ELSE m.decryptedContent
               END         AS lastMsgContent,
               m.type      AS lastMsgType,
               m.status    AS lastMsgStatus,
               m.timestamp AS lastMsgTimestamp,
               m.isEdited  AS lastMsgIsEdited,
               u.isOnline  AS otherIsOnline
        FROM chats c
        LEFT JOIN messages m ON m.id = (
            SELECT id FROM messages WHERE chatId = c.id
            ORDER BY timestamp DESC LIMIT 1
        )
        LEFT JOIN users u ON u.id = c.otherUserId
        WHERE c.isHidden = 0
        ORDER BY c.isPinned DESC, c.updatedAt DESC
    """)
    fun observeAllWithLastMessage(): Flow<List<ChatWithLastMessage>>

    /**
     * Снимок всех видимых чатов — для фонового префетча сообщений.
     * Не реактивный (не Flow): VM сам решает когда дёрнуть.
     */
    @Query("SELECT * FROM chats WHERE isHidden = 0 ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getAllSync(): List<ChatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chat: ChatEntity)

    @Update
    suspend fun update(chat: ChatEntity)

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getById(chatId: String): ChatEntity?

    @Query("UPDATE chats SET unreadCount = 0 WHERE id = :chatId")
    suspend fun clearUnread(chatId: String)

    @Query("UPDATE chats SET unreadCount = unreadCount + 1, updatedAt = :timestamp WHERE id = :chatId")
    suspend fun incrementUnread(chatId: String, timestamp: Long)

    /**
     * Обновляет ТОЛЬКО updatedAt — нужно когда сам отправил сообщение
     * (свой счётчик непрочитанных трогать нельзя, но чат должен подняться
     * в списке наверх). Без этого собственное только что отправленное
     * сообщение оставляло чат на старой позиции до следующего syncChats.
     */
    @Query("UPDATE chats SET updatedAt = :timestamp WHERE id = :chatId")
    suspend fun touchUpdatedAt(chatId: String, timestamp: Long)

    @Query("UPDATE chats SET isPinned = :pinned WHERE id = :chatId")
    suspend fun setPinned(chatId: String, pinned: Boolean)

    @Query("UPDATE chats SET isMuted = :muted WHERE id = :chatId")
    suspend fun setMuted(chatId: String, muted: Boolean)

    /**
     * Soft-delete: помечаем чат скрытым вместо физического удаления. Это нужно
     * чтобы syncChats() не «оживлял» чат при следующей загрузке списка с сервера.
     * Запись в БД остаётся, при приходе нового сообщения чат можно «разспрятать»
     * (см. unhide ниже).
     */
    @Query("UPDATE chats SET isHidden = 1 WHERE id = :chatId")
    suspend fun hideById(chatId: String)

    /** Разспрятать чат — вызывается при приходе нового сообщения в скрытый чат. */
    @Query("UPDATE chats SET isHidden = 0 WHERE id = :chatId")
    suspend fun unhideById(chatId: String)

    /** Полное физическое удаление — оставляем для совместимости/тестов. */
    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteById(chatId: String)

    @Query("SELECT * FROM chats WHERE otherUserId = :userId AND type = 'DIRECT' LIMIT 1")
    suspend fun getByOtherUserId(userId: String): ChatEntity?
}

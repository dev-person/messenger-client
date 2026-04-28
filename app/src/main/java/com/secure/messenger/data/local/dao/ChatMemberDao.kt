package com.secure.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.secure.messenger.data.local.entities.ChatMemberEntity
import com.secure.messenger.data.local.entities.UserEntity
import kotlinx.coroutines.flow.Flow

/**
 * Связка чата с его участниками. JOIN с users — нужен и displayName,
 * и avatarUrl, и isOnline для индикатора. Возвращаем целиком UserEntity +
 * role, ViewModel мапит в domain User.
 */
data class ChatMemberWithUser(
    val chatId: String,
    val role: String,
    val id: String,
    val phone: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String?,
    val isOnline: Boolean,
    val lastSeen: Long,
    val publicKey: String,
    val isContact: Boolean,
    val appVersionCode: Int,
)

@Dao
interface ChatMemberDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(member: ChatMemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(members: List<ChatMemberEntity>)

    @Query("DELETE FROM chat_members WHERE chatId = :chatId AND userId = :userId")
    suspend fun delete(chatId: String, userId: String)

    @Query("DELETE FROM chat_members WHERE chatId = :chatId")
    suspend fun deleteAllForChat(chatId: String)

    /**
     * Полная замена состава чата на сервер-снимок: удаляем всё, что не в новом
     * списке (кика на сервере мы могли пропустить), и upsert'им свежее.
     * Делаем в одной транзакции — иначе UI на доли секунды показал бы оба
     * списка склеенными.
     */
    @Transaction
    suspend fun replaceForChat(chatId: String, members: List<ChatMemberEntity>) {
        deleteAllForChat(chatId)
        if (members.isNotEmpty()) upsertAll(members)
    }

    /**
     * Для GroupInfoViewModel: реактивный поток состава с присоединённым
     * UserEntity (имя/аватар/онлайн). Сортировка стабильная по displayName,
     * чтобы при upsert одного юзера весь список не прыгал.
     */
    @Query("""
        SELECT cm.chatId        AS chatId,
               cm.role          AS role,
               u.id             AS id,
               u.phone          AS phone,
               u.username       AS username,
               u.displayName    AS displayName,
               u.avatarUrl      AS avatarUrl,
               u.bio            AS bio,
               u.isOnline       AS isOnline,
               u.lastSeen       AS lastSeen,
               u.publicKey      AS publicKey,
               u.isContact      AS isContact,
               u.appVersionCode AS appVersionCode
        FROM chat_members cm
        INNER JOIN users u ON u.id = cm.userId
        WHERE cm.chatId = :chatId
        ORDER BY u.displayName COLLATE NOCASE
    """)
    fun observeMembersWithUser(chatId: String): Flow<List<ChatMemberWithUser>>

    @Query("SELECT COUNT(*) FROM chat_members WHERE chatId = :chatId")
    fun observeMemberCount(chatId: String): Flow<Int>

    @Query("SELECT userId FROM chat_members WHERE chatId = :chatId")
    suspend fun memberIds(chatId: String): List<String>
}

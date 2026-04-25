package com.secure.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.secure.messenger.data.local.entities.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Query("""
        SELECT * FROM users WHERE isContact = 1
        OR id IN (SELECT otherUserId FROM chats WHERE type = 'DIRECT' AND otherUserId IS NOT NULL)
        ORDER BY displayName ASC
    """)
    fun observeContacts(): Flow<List<UserEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(users: List<UserEntity>)

    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getById(userId: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :userId")
    fun observeById(userId: String): Flow<UserEntity?>

    /**
     * Реактивный список пользователей по списку id — нужен для шапки группы:
     * считаем онлайн-участников, реагируя на WS-события user_status, которые
     * обновляют колонку isOnline.
     */
    @Query("SELECT * FROM users WHERE id IN (:userIds)")
    fun observeByIds(userIds: List<String>): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE phone = :phone")
    suspend fun getByPhone(phone: String): UserEntity?

    @Query("UPDATE users SET isOnline = :online, lastSeen = :lastSeen WHERE id = :userId")
    suspend fun updateOnlineStatus(userId: String, online: Boolean, lastSeen: Long)
}

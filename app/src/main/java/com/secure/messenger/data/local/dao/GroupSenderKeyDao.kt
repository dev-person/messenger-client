package com.secure.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.secure.messenger.data.local.entities.GroupSenderKeyEntity

@Dao
interface GroupSenderKeyDao {

    /**
     * Upsert — при повторной загрузке того же ключа (chat/owner/epoch)
     * перезаписываем. Идемпотентно.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: GroupSenderKeyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(keys: List<GroupSenderKeyEntity>)

    /**
     * Получить sender key конкретного владельца в конкретном epoch'е.
     * Используется и при отправке (ownerId = myId), и при расшифровке
     * входящего (ownerId = senderId).
     */
    @Query("""
        SELECT * FROM group_sender_keys
        WHERE chatId = :chatId AND ownerId = :ownerId AND epoch = :epoch
        LIMIT 1
    """)
    suspend fun get(chatId: String, ownerId: String, epoch: Int): GroupSenderKeyEntity?

    /**
     * Самый свежий sender key данного владельца (без фильтра по epoch).
     * Используется в UI для отображения, но при шифровании всегда берём
     * тот epoch, который сейчас у чата — см. ChatEntity.currentEpoch.
     */
    @Query("""
        SELECT * FROM group_sender_keys
        WHERE chatId = :chatId AND ownerId = :ownerId
        ORDER BY epoch DESC LIMIT 1
    """)
    suspend fun getLatest(chatId: String, ownerId: String): GroupSenderKeyEntity?

    /**
     * Все sender keys одного владельца в чате (по всем epoch'ам, новые
     * сначала). Нужно для расшифровки сообщения, когда мы не знаем под
     * каким epoch'ом оно было зашифровано — пробуем ключи по очереди.
     */
    @Query("""
        SELECT * FROM group_sender_keys
        WHERE chatId = :chatId AND ownerId = :ownerId
        ORDER BY epoch DESC
    """)
    suspend fun listByOwner(chatId: String, ownerId: String): List<GroupSenderKeyEntity>

    /** Все sender keys группы — для диагностики/очистки. */
    @Query("SELECT * FROM group_sender_keys WHERE chatId = :chatId")
    suspend fun listByChat(chatId: String): List<GroupSenderKeyEntity>

    @Query("DELETE FROM group_sender_keys WHERE chatId = :chatId")
    suspend fun deleteByChat(chatId: String)

    @Query("DELETE FROM group_sender_keys WHERE chatId = :chatId AND ownerId = :ownerId")
    suspend fun deleteByOwner(chatId: String, ownerId: String)
}

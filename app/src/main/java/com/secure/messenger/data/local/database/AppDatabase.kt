package com.secure.messenger.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.secure.messenger.data.local.dao.ChatDao
import com.secure.messenger.data.local.dao.MessageDao
import com.secure.messenger.data.local.dao.UserDao
import com.secure.messenger.data.local.entities.ChatEntity
import com.secure.messenger.data.local.entities.MessageEntity
import com.secure.messenger.data.local.entities.UserEntity

@Database(
    entities = [UserEntity::class, ChatEntity::class, MessageEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chats ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v2 → v3: очищаем encryptedContent для медиа-сообщений — он дублирует
        // тяжёлый base64-блоб из decryptedContent и переполняет CursorWindow,
        // из-за чего observeMessages падал с SQLiteBlobTooBigException на
        // чатах с несколькими фото/голосовыми.
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE messages SET encryptedContent = '' " +
                    "WHERE type IN ('IMAGE','AUDIO','VIDEO')"
                )
            }
        }
    }
}

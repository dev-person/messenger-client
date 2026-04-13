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
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao

    companion object {
        // v1 → v2: добавили колонку chats.isHidden для soft-delete чатов.
        // Без миграции пользователи потеряли бы все локальные сообщения и чаты
        // (fallbackToDestructiveMigration), а это для альфа-релиза недопустимо
        // когда у юзеров уже накоплена история.
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chats ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}

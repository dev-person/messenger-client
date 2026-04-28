package com.secure.messenger.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.secure.messenger.data.local.dao.ChatDao
import com.secure.messenger.data.local.dao.ChatMemberDao
import com.secure.messenger.data.local.dao.GroupSenderKeyDao
import com.secure.messenger.data.local.dao.MessageDao
import com.secure.messenger.data.local.dao.UserDao
import com.secure.messenger.data.local.entities.ChatEntity
import com.secure.messenger.data.local.entities.ChatMemberEntity
import com.secure.messenger.data.local.entities.GroupSenderKeyEntity
import com.secure.messenger.data.local.entities.MessageEntity
import com.secure.messenger.data.local.entities.UserEntity

@Database(
    entities = [
        UserEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        GroupSenderKeyEntity::class,
        ChatMemberEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun groupSenderKeyDao(): GroupSenderKeyDao
    abstract fun chatMemberDao(): ChatMemberDao

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

        // v3 → v4: групповые чаты 1.0.68.
        // 1. К ChatEntity добавлены два поля — роль пользователя и epoch ротации
        //    sender-ключей. Для существующих DIRECT-чатов дефолты безопасны.
        // 2. Новая таблица group_sender_keys хранит расшифрованные sender-ключи
        //    членов группы — свой и чужие.
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chats ADD COLUMN myRole TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chats ADD COLUMN currentEpoch INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS group_sender_keys (
                        chatId    TEXT    NOT NULL,
                        ownerId   TEXT    NOT NULL,
                        epoch     INTEGER NOT NULL,
                        senderKey TEXT    NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY (chatId, ownerId, epoch)
                    )
                    """.trimIndent()
                )
            }
        }

        // v4 → v5: локальный кеш состава чата (1.0.71).
        // Новая таблица chat_members — раньше состав группы тянулся из сети
        // на каждом открытии GroupInfoScreen, и UI флешил спиннер. Теперь
        // храним (chatId, userId, role) локально, UI читает реактивно из
        // Flow, сетевой fetch обновляет данные «поверх» без визуального сброса.
        // Без FK на users (см. ChatMemberEntity.kt) — иначе WS-events для
        // юзера, которого ещё нет в users, роняли обработчик по FK constraint.
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_members (
                        chatId TEXT NOT NULL,
                        userId TEXT NOT NULL,
                        role   TEXT NOT NULL,
                        PRIMARY KEY (chatId, userId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_members_chatId ON chat_members(chatId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_members_userId ON chat_members(userId)")
            }
        }

        // v6 → v7: составной индекс (chatId, timestamp) на messages.
        // Без него подзапрос «последнее сообщение чата» в observeAllWithLastMessage
        // делал full sort по timestamp на каждом чате при каждом emit Flow —
        // основная причина «блокировки скролла» на ~секунду при возврате
        // из чата на список (особенно у юзеров с большой историей).
        // ВАЖНО: дропаем старый одноколоночный index_messages_chatId. Без
        // этого Room сверял схему после миграции и крашил приложение
        // (Migration didn't properly handle: messages — лишний индекс).
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_messages_chatId")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_chatId_timestamp " +
                    "ON messages(chatId, timestamp)"
                )
            }
        }

        // v7 → v8: добавляем appVersionCode в users.
        // Раньше эта колонка хранилась в UserDto (с сервера) и попадала в
        // domain User напрямую. После того как состав группы перевели на
        // local cache (chat_members JOIN users), значение терялось — JOIN
        // тянет только колонки UserEntity. Из-за этого плашка «кто-то не
        // обновил приложение» в групповых чатах перестала появляться.
        //
        // Заодно подчищаем лишний индекс index_messages_chatId, который мог
        // остаться у юзеров с промежуточной v7 (до фикса MIGRATION_6_7).
        // Без этого Room после миграции валидирует схему, видит лишний
        // index и крашит приложение по IllegalStateException — у одного
        // юзера на SGS21U это случилось при обновлении до v8.
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_messages_chatId")
                db.execSQL("ALTER TABLE users ADD COLUMN appVersionCode INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v5 → v6: пересоздаём chat_members без FK на users.
        // На устройствах, у которых v5 уже создалась со старой схемой
        // (с FOREIGN KEY ... CASCADE), Room валидирует схему при открытии и
        // вылетает с IllegalStateException «schema does not match». Дроп +
        // ре-create приведёт схему к актуальной без FK и без потери данных
        // (chat_members перестрахуется при ближайшем syncChats).
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS chat_members")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_members (
                        chatId TEXT NOT NULL,
                        userId TEXT NOT NULL,
                        role   TEXT NOT NULL,
                        PRIMARY KEY (chatId, userId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_members_chatId ON chat_members(chatId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_members_userId ON chat_members(userId)")
            }
        }
    }
}

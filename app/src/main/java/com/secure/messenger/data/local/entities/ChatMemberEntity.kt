package com.secure.messenger.data.local.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * Локальный кеш состава группы. Раньше список членов брался только из сети
 * на каждом открытии GroupInfoScreen — отсюда у юзера флешился спиннер /
 * пересчитывалось «N участников». Теперь сохраняем (chatId, userId, role)
 * в local DB и UI читает их реактивно из Flow, а сетевой fetch обновляет
 * данные «поверх» без визуального сброса.
 *
 * Намеренно БЕЗ FK на users — иначе любой WS-event (group_role_changed,
 * group_member_added) для юзера, которого ещё нет в локальной таблице
 * users, ронял весь обработчик по FOREIGN KEY constraint, и приложение
 * могло потерять часть события или крашнуться. Orphan-записи безопасны:
 * INNER JOIN с users в [observeMembersWithUser] просто не вернёт строку,
 * пока пользователь не появится в users (через ближайший syncChats или
 * upsert профиля).
 */
@Entity(
    tableName = "chat_members",
    primaryKeys = ["chatId", "userId"],
    indices = [Index("chatId"), Index("userId")],
)
data class ChatMemberEntity(
    val chatId: String,
    val userId: String,
    /** CREATOR / ADMIN / MEMBER — для DIRECT всегда MEMBER, не используется. */
    val role: String,
)

package com.secure.messenger.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.secure.messenger.domain.model.User

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val phone: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String?,
    val isOnline: Boolean,
    val lastSeen: Long,
    val publicKey: String,
    val isContact: Boolean,
    /**
     * Версия клиента собеседника. 0 — неизвестно. Используется чтобы
     * показывать плашку «кто-то не обновил приложение» в групповых чатах
     * (если version < MIN_GROUP_VERSION_CODE — у юзера старая версия и
     * он не сможет расшифровать sender-keys нового формата).
     */
    val appVersionCode: Int = 0,
) {
    fun toDomain() = User(
        id = id, phone = phone, username = username, displayName = displayName,
        avatarUrl = avatarUrl, bio = bio, isOnline = isOnline, lastSeen = lastSeen,
        publicKey = publicKey, isContact = isContact, appVersionCode = appVersionCode,
    )

    companion object {
        fun fromDomain(user: User) = UserEntity(
            id = user.id, phone = user.phone, username = user.username,
            displayName = user.displayName, avatarUrl = user.avatarUrl, bio = user.bio,
            isOnline = user.isOnline, lastSeen = user.lastSeen, publicKey = user.publicKey,
            isContact = user.isContact, appVersionCode = user.appVersionCode,
        )
    }
}

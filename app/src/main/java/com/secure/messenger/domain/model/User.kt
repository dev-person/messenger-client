package com.secure.messenger.domain.model

data class User(
    val id: String,
    val phone: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String?,
    val isOnline: Boolean,
    val lastSeen: Long,
    // Public key for E2E encryption (X25519)
    val publicKey: String,
    val isContact: Boolean = false,
    // ── Группы (1.0.68) ──────────────────────────────────────────────────
    // Роль пользователя в контексте конкретной группы. Заполняется только
    // когда User возвращается как участник Chat.members; в других местах null.
    val groupRole: ChatRole? = null,
    // Последняя версия приложения, которой пользователь отметился. 0 = неизвестно
    // (новый пользователь без /app-version, или сервер старый и поле не вернул).
    // Используется в GroupInfoScreen чтобы пометить «замок» у участников
    // на устаревших клиентах, которые не могут расшифровать sender-keys.
    val appVersionCode: Int = 0,
)

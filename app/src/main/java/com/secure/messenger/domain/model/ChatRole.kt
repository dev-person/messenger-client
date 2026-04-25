package com.secure.messenger.domain.model

/**
 * Роль участника в групповом чате (1.0.68).
 *
 * - [CREATOR] — изначальный создатель или тот, кому права передались
 *   каскадом при уходе предыдущего. Не может быть кикнут, не может
 *   снизить свою роль — только `leave`, после чего права передаются.
 * - [ADMIN] — назначен CREATOR'ом. Может кикать/добавлять, редактировать
 *   название и аватар. Не может менять роли других.
 * - [MEMBER] — обычный участник. Только читает/пишет.
 *
 * Для DIRECT-чатов роль не используется — [None] обозначает «неизвестно»
 * или «не применимо».
 */
enum class ChatRole {
    CREATOR,
    ADMIN,
    MEMBER;

    companion object {
        /** Безопасный парсинг: пустая/неизвестная строка → null. */
        fun parse(raw: String?): ChatRole? = when (raw) {
            "CREATOR" -> CREATOR
            "ADMIN"   -> ADMIN
            "MEMBER"  -> MEMBER
            else      -> null
        }
    }
}

/** Может ли пользователь с данной ролью управлять группой (info/members/roles). */
fun ChatRole?.canManage(): Boolean = this == ChatRole.CREATOR || this == ChatRole.ADMIN

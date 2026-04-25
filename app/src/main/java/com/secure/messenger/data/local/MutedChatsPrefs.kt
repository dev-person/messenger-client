package com.secure.messenger.data.local

import android.content.Context

/**
 * Лёгкая обёртка над SharedPreferences для списка приглушённых чатов.
 * Хранится отдельно от Room, чтобы FcmService мог читать состояние без Hilt
 * и без открытия Room-БД (FcmService запускается из убитого приложения,
 * когда DI ещё не инициализирован).
 *
 * Ключ — chatId, значение — Boolean (приглушён/нет). Отсутствие ключа
 * трактуется как «не приглушён», что совпадает с поведением по умолчанию.
 */
object MutedChatsPrefs {
    private const val PREFS_NAME = "muted_chats"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isMuted(context: Context, chatId: String): Boolean =
        prefs(context).getBoolean(chatId, false)

    fun setMuted(context: Context, chatId: String, muted: Boolean) {
        prefs(context).edit().apply {
            if (muted) putBoolean(chatId, true) else remove(chatId)
            apply()
        }
    }
}

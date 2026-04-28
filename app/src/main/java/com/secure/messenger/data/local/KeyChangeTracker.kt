package com.secure.messenger.data.local

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Отслеживает изменения публичных ключей собеседников.
 *
 * Сценарий: сервер по какой-то причине отдал нам новый публичный ключ Боба.
 * Это может быть законная смена пароля Боба — а может быть атака подмены
 * ключа (MITM на скомпрометированном сервере). В любом случае пользователь
 * должен иметь возможность увидеть факт смены, чтобы при необходимости
 * сверить safety code.
 *
 * Хранится отдельно от Room — простой SharedPreferences. Объём небольшой
 * (≤ числа контактов × ~64 байта на запись + N записей в истории) и не
 * требует миграции БД.
 *
 * Структура prefs:
 *   "last_pubkey:<userId>"   → base64 публичного ключа, который мы видели в прошлый раз
 *   "key_changed:<userId>"   → 1 если с прошлой проверки ключ менялся
 *   "history:<userId>"       → JSON-массив [{"ts": unixMs, "fp": fingerprint}, ...]
 *                               где fp — короткий отпечаток нового ключа (8 hex)
 *   "verified:<safetyCode>"  → 1 если юзер вручную подтвердил этот код через UI
 */
object KeyChangeTracker {
    private const val PREFS_NAME = "safety_keys"
    private const val PREFIX_LAST = "last_pubkey:"
    private const val PREFIX_CHANGED = "key_changed:"
    private const val PREFIX_HISTORY = "history:"
    private const val PREFIX_VERIFIED = "verified:"
    private const val MAX_HISTORY_ENTRIES = 20

    /** Запись истории смены ключа. */
    data class HistoryEntry(val timestampMs: Long, val fingerprint: String)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Регистрирует publicKey собеседника. При изменении → добавляет запись
     * в историю и выставляет флаг key_changed. При первом вызове (нет
     * прошлого ключа) — только запоминаем, без флага и истории.
     */
    fun observePublicKey(context: Context, userId: String, publicKey: String) {
        if (publicKey.isBlank()) return
        val p = prefs(context)
        val lastKey = "$PREFIX_LAST$userId"
        val previous = p.getString(lastKey, null)
        if (previous == null) {
            p.edit().putString(lastKey, publicKey).apply()
            return
        }
        if (previous != publicKey) {
            val newHistory = appendHistory(p, userId, publicKey)
            p.edit()
                .putString(lastKey, publicKey)
                .putBoolean("$PREFIX_CHANGED$userId", true)
                .putString("$PREFIX_HISTORY$userId", newHistory)
                .apply()
        }
    }

    /** Добавляет новую запись в историю, ограничивая длину MAX_HISTORY_ENTRIES. */
    private fun appendHistory(p: SharedPreferences, userId: String, newPublicKey: String): String {
        val existing = p.getString("$PREFIX_HISTORY$userId", null)
        val arr = runCatching { JSONArray(existing ?: "[]") }.getOrDefault(JSONArray())
        val entry = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("fp", shortFingerprint(newPublicKey))
        // Newest first: вставляем в начало, обрезаем хвост
        val combined = JSONArray()
        combined.put(entry)
        val keepCount = (MAX_HISTORY_ENTRIES - 1).coerceAtLeast(0)
        for (i in 0 until minOf(arr.length(), keepCount)) {
            combined.put(arr.getJSONObject(i))
        }
        return combined.toString()
    }

    /**
     * Короткий человекочитаемый отпечаток ключа: первые 8 hex-символов от
     * SHA-256 публичного ключа. Не для безопасности — только для диагностики
     * («раньше был AB12CD34, стал EF56GH78» — в UI видно, что ключи разные).
     */
    private fun shortFingerprint(publicKeyBase64: String): String {
        val bytes = runCatching { android.util.Base64.decode(publicKeyBase64, android.util.Base64.NO_WRAP) }
            .getOrNull() ?: return "?"
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val hash = md.digest(bytes)
        val sb = StringBuilder()
        for (i in 0 until 4) {
            sb.append("%02X".format(hash[i].toInt() and 0xFF))
        }
        return sb.toString() // 8 hex chars
    }

    /** Возвращает список изменений (новые сначала). */
    fun getHistory(context: Context, userId: String): List<HistoryEntry> {
        val raw = prefs(context).getString("$PREFIX_HISTORY$userId", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = mutableListOf<HistoryEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                out.add(HistoryEntry(obj.getLong("ts"), obj.optString("fp", "")))
            }
            out
        }.getOrDefault(emptyList())
    }

    /** true если ключ собеседника поменялся с момента последней проверки. */
    fun hasKeyChanged(context: Context, userId: String): Boolean =
        prefs(context).getBoolean("$PREFIX_CHANGED$userId", false)

    fun acknowledgeKeyChange(context: Context, userId: String) {
        prefs(context).edit().remove("$PREFIX_CHANGED$userId").apply()
    }

    fun isVerified(context: Context, safetyCode: String): Boolean {
        if (safetyCode.isBlank()) return false
        return prefs(context).getBoolean("$PREFIX_VERIFIED$safetyCode", false)
    }

    fun markVerified(context: Context, safetyCode: String) {
        if (safetyCode.isBlank()) return
        prefs(context).edit().putBoolean("$PREFIX_VERIFIED$safetyCode", true).apply()
    }
}

package com.secure.messenger.utils

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Сериализация / десериализация полезной нагрузки голосового сообщения.
 *
 * Формат — компактный JSON:
 * ```
 * {
 *   "d": <секунды>,
 *   "a": "<base64-байты>",
 *   "w": [12, 45, 23, ...]   // амплитуды для waveform-а (опционально)
 * }
 * ```
 *
 * Сервер ничего не знает о структуре — для него это просто строка
 * encryptedContent после шифрования.
 */
object VoiceCodec {

    data class VoiceData(
        val durationSeconds: Int,
        val bytes: ByteArray,
        val waveform: IntArray = IntArray(0),
    )

    /** Сериализация: байты + длительность + амплитуды для waveform-а. */
    fun encode(bytes: ByteArray, durationSeconds: Int, waveform: IntArray): String {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return JSONObject().apply {
            put("d", durationSeconds)
            put("a", base64)
            if (waveform.isNotEmpty()) {
                put("w", JSONArray().apply { waveform.forEach { put(it) } })
            }
        }.toString()
    }

    /**
     * Парсит JSON-полезную нагрузку голосового сообщения.
     * Возвращает null если строка не распарсилась.
     */
    fun decode(payload: String): VoiceData? {
        if (payload.isBlank()) return null
        return runCatching {
            val obj = JSONObject(payload)
            val d = obj.optInt("d", 0)
            val a = obj.optString("a", "")
            if (a.isEmpty()) return null
            val waveform = obj.optJSONArray("w")?.let { arr ->
                IntArray(arr.length()) { i -> arr.optInt(i, 0) }
            } ?: IntArray(0)
            VoiceData(
                durationSeconds = d,
                bytes = Base64.decode(a, Base64.NO_WRAP),
                waveform = waveform,
            )
        }.onFailure { Timber.w(it, "VoiceCodec.decode failed") }.getOrNull()
    }
}

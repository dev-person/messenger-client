package com.secure.messenger.utils

import android.util.Base64
import java.security.MessageDigest

/**
 * Утилита для проверки идентичности собеседника в стиле safety numbers
 * Signal/WhatsApp.
 *
 * Когда вы шифруете сообщение для Боба, ваш клиент берёт его публичный ключ
 * с сервера. Если сервер скомпрометирован, он может подменить ключ Боба на
 * ключ злоумышленника — тогда вы будете шифровать к злоумышленнику, а Бобу
 * будут приходить «нерасшифровываемые» сообщения. Это classic E2E MITM.
 *
 * Защита: дисплей короткого «отпечатка» обоих публичных ключей. Пара
 * пользователей сверяет код вживую (или по SMS, голосом и т.п.). Если коды
 * совпадают — атаки нет. Если отличаются — что-то не то.
 *
 * Также отслеживаем смену ключа собеседника в локальной БД. Если ключ Боба
 * у нас был X, а пришёл Y — показываем баннер «ключ собеседника изменился,
 * сверьте код перед обсуждением чувствительной информации».
 */
object SafetyNumber {

    /**
     * Вычисляет safety code для пары (мой pubKey, его pubKey).
     * Канонический порядок: меньший base64-string первым — чтобы у обеих
     * сторон одинаковый результат, независимо от того кто из них «я».
     *
     * Алгоритм: SHA-512(min(A,B) || max(A,B)) → 64 байта.
     * Берём 48 байт (12 × 4) → 12 групп по 5 десятичных цифр = 60 цифр.
     * Это даёт ~95 бит коллизионной стойкости — атакующему пришлось бы
     * подобрать пару ключей с совпадающими 60 цифрами (2^95 попыток).
     */
    fun compute(myPublicKeyBase64: String, theirPublicKeyBase64: String): String {
        if (myPublicKeyBase64.isBlank() || theirPublicKeyBase64.isBlank()) return ""
        val myBytes = runCatching { Base64.decode(myPublicKeyBase64, Base64.NO_WRAP) }.getOrNull()
            ?: return ""
        val theirBytes = runCatching { Base64.decode(theirPublicKeyBase64, Base64.NO_WRAP) }.getOrNull()
            ?: return ""
        val (first, second) = if (myPublicKeyBase64 <= theirPublicKeyBase64) {
            myBytes to theirBytes
        } else {
            theirBytes to myBytes
        }
        // SHA-512 даёт 64 байта — нам нужно 48 (12 групп × 4 байта).
        val md = MessageDigest.getInstance("SHA-512")
        md.update(first)
        md.update(second)
        val hash = md.digest()

        // 12 групп × 5 цифр = 60 цифр
        val builder = StringBuilder(72) // 60 цифр + 11 разделителей
        for (group in 0 until 12) {
            val offset = group * 4
            val word = ((hash[offset].toLong() and 0xFF) shl 24) or
                ((hash[offset + 1].toLong() and 0xFF) shl 16) or
                ((hash[offset + 2].toLong() and 0xFF) shl 8) or
                (hash[offset + 3].toLong() and 0xFF)
            val fiveDigits = "%05d".format(word % 100_000)
            if (group > 0) builder.append(' ')
            builder.append(fiveDigits)
        }
        return builder.toString()
    }
}

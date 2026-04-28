package com.secure.messenger.utils

/**
 * Утилиты для определения эмодзи-only сообщений.
 *
 * В стиле Telegram/WhatsApp: если юзер отправил только 1-3 эмодзи без текста,
 * показываем их большим размером без bubble — выглядит выразительнее, чем
 * крошечные значки внутри прямоугольника.
 */
object EmojiUtils {

    /**
     * true если сообщение состоит только из 1-3 эмодзи (без обычного текста).
     * Композитные эмодзи (👨‍👩‍👧 и т.п.) учитываются как один символ.
     */
    fun isJumboCandidate(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        var nonEmoji = 0
        var glyphs = 0
        var prevWasJoiner = false
        var i = 0
        while (i < trimmed.length) {
            val cp = trimmed.codePointAt(i)
            when {
                cp == 0x200D /* ZWJ */ || cp == 0xFE0F /* VS-16 */ -> {
                    prevWasJoiner = (cp == 0x200D)
                }
                cp == 0x20 /* space */ -> {
                    prevWasJoiner = false
                }
                isEmojiCodePoint(cp) -> {
                    if (!prevWasJoiner) glyphs++
                    prevWasJoiner = false
                }
                else -> {
                    nonEmoji++
                }
            }
            i += Character.charCount(cp)
        }
        return nonEmoji == 0 && glyphs in 1..3
    }

    /**
     * Грубая проверка кодпоинта на «эмодзи». Покрывает основные блоки Unicode:
     *   - Misc Symbols & Pictographs / Emoji blocks (U+1F000..U+1FFFF)
     *   - Misc Symbols / Dingbats (U+2600..U+27BF)
     *   - Misc Technical (U+2300..U+23FF)
     *   - Misc Symbols and Arrows (U+2B00..U+2BFF)
     *   - Enclosing Keycap (U+20E3)
     *   - © ® ™ legacy символы.
     */
    private fun isEmojiCodePoint(cp: Int): Boolean {
        return cp in 0x1F000..0x1FFFF ||
            cp in 0x2600..0x27BF ||
            cp in 0x2300..0x23FF ||
            cp in 0x2B00..0x2BFF ||
            cp == 0x20E3 ||
            cp in 0xA9..0xAE
    }
}

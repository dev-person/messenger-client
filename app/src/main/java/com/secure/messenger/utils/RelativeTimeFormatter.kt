package com.secure.messenger.utils

/**
 * Формирует короткий текст «был(а) в сети N минут/часов/дней назад» для
 * шапки чата. Шкала уровней разрежается с ростом времени, чтобы не
 * мерцать каждую минуту: 1м, 5м, 10м, 15м, 30м, 1ч, 2ч, 4ч, 8ч, 18ч,
 * затем дни и недели.
 *
 * Возвращает только хвостовую часть («5 минут назад»). Префикс «был(а)
 * в сети» добавляет UI — он зависит от пола (м/ж) или дефолтит на «был(а)».
 */
object RelativeTimeFormatter {
    /**
     * @param lastSeenMs unix-time (мс) когда юзер последний раз был онлайн
     * @param nowMs текущее время (для тестов; в проде всегда [System.currentTimeMillis])
     * @return «только что», «5 минут назад», «вчера», «3 дня назад», «3 недели назад»
     *         или null, если lastSeenMs некорректный (0 / в будущем больше 5 мин запаса)
     */
    fun format(lastSeenMs: Long, nowMs: Long = System.currentTimeMillis()): String? {
        if (lastSeenMs <= 0L) return null
        val deltaMs = nowMs - lastSeenMs
        if (deltaMs < -5 * 60_000L) return null // часы рассинхронизированы — лучше промолчать

        val deltaSec = (deltaMs / 1000L).coerceAtLeast(0L)
        val deltaMin = deltaSec / 60L
        val deltaHour = deltaMin / 60L
        val deltaDay = deltaHour / 24L

        return when {
            deltaSec < 60L -> "только что"
            deltaMin < 5L -> "$deltaMin ${pluralRu(deltaMin.toInt(), "минуту", "минуты", "минут")} назад"
            deltaMin < 10L -> "5 минут назад"
            deltaMin < 15L -> "10 минут назад"
            deltaMin < 30L -> "15 минут назад"
            deltaMin < 60L -> "30 минут назад"
            deltaHour < 2L -> "час назад"
            deltaHour < 4L -> "2 часа назад"
            deltaHour < 8L -> "4 часа назад"
            deltaHour < 18L -> "8 часов назад"
            deltaHour < 24L -> "18 часов назад"
            deltaDay < 2L -> "вчера"
            deltaDay < 7L -> "$deltaDay ${pluralRu(deltaDay.toInt(), "день", "дня", "дней")} назад"
            deltaDay < 30L -> {
                val weeks = (deltaDay / 7L).toInt()
                "$weeks ${pluralRu(weeks, "неделю", "недели", "недель")} назад"
            }
            deltaDay < 365L -> {
                val months = (deltaDay / 30L).toInt()
                "$months ${pluralRu(months, "месяц", "месяца", "месяцев")} назад"
            }
            else -> {
                val years = (deltaDay / 365L).toInt()
                "$years ${pluralRu(years, "год", "года", "лет")} назад"
            }
        }
    }

    private fun pluralRu(count: Int, one: String, few: String, many: String): String {
        val mod10 = count % 10
        val mod100 = count % 100
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }
}

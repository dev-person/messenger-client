package com.secure.messenger.utils

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import timber.log.Timber

/**
 * Улучшение текста: сначала пробует Gemini Nano (на поддерживаемых устройствах),
 * при неудаче — фолбэк на локальные правила.
 */
object TextEnhancer {

    // ── On-device Gemini Nano ───────────────────────────────────────────────

    private var aiModel: GenerativeModel? = null
    private var aiAvailable: Boolean? = null // null = ещё не проверяли

    /** Инициализировать Gemini Nano. Вызвать один раз (например в Application/Activity). */
    fun initAi() {
        if (aiAvailable != null) return
        aiModel = try {
            GenerativeModel(
                modelName = "gemini-nano",
                apiKey = "", // Не нужен для on-device модели
                generationConfig = generationConfig {
                    maxOutputTokens = 256
                    temperature = 0.2f // Минимальная креативность — нам нужна точность
                },
            )
        } catch (e: Exception) {
            Timber.d("Gemini Nano недоступен: ${e.message}")
            null
        }
        aiAvailable = aiModel != null
    }

    /** true если на устройстве есть Gemini Nano */
    val isAiAvailable: Boolean get() = aiAvailable == true

    // ── Главный метод ───────────────────────────────────────────────────────

    /**
     * Улучшает текст. Suspend — потому что AI-вызов асинхронный.
     * @return пара (результат, были ли изменения)
     */
    suspend fun enhance(text: String): Pair<String, Boolean> {
        if (text.isBlank()) return text to false

        // Пробуем Gemini Nano
        aiModel?.let { model ->
            try {
                val prompt = "Исправь орфографические и пунктуационные ошибки в тексте. " +
                        "Верни ТОЛЬКО исправленный текст, без пояснений и кавычек:\n$text"
                val response = model.generateContent(prompt)
                val result = response.text?.trim()
                if (!result.isNullOrBlank() && result != text) {
                    Timber.d("Gemini Nano улучшил текст")
                    return result to true
                }
            } catch (e: Exception) {
                Timber.d("Gemini Nano ошибка, фолбэк на правила: ${e.message}")
                // Помечаем как недоступный чтобы не пробовать снова
                if (e.message?.contains("not found", ignoreCase = true) == true ||
                    e.message?.contains("not available", ignoreCase = true) == true ||
                    e.message?.contains("not supported", ignoreCase = true) == true
                ) {
                    aiModel = null
                    aiAvailable = false
                }
            }
        }

        // Фолбэк на правила
        return enhanceWithRules(text)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Фолбэк: правила для русского / английского текста
    // ══════════════════════════════════════════════════════════════════════════

    fun enhanceWithRules(text: String): Pair<String, Boolean> {
        if (text.isBlank()) return text to false

        var result = text.trim()

        // 1. Множественные пробелы → один
        result = result.replace(Regex(" {2,}"), " ")

        // 2. Убрать пробел перед знаками препинания
        result = result.replace(Regex(" +([.,!?;:…])"), "$1")

        // 3. Добавить пробел после знаков препинания, если следует буква
        result = result.replace(Regex("([.,!?;:])([А-Яа-яA-Za-zЁё])")) {
            "${it.groupValues[1]} ${it.groupValues[2]}"
        }

        // 4. Заглавная буква в начале текста
        result = result.replaceFirstChar { if (it.isLetter()) it.uppercase() else it.toString() }

        // 5. Заглавная буква после .!? + пробел
        result = result.replace(Regex("([.!?…]\\s+)([а-яa-zё])")) {
            "${it.groupValues[1]}${it.groupValues[2].uppercase()}"
        }

        // 6. Убрать пробелы перед переводом строки и после
        result = result.replace(Regex(" *\\n *"), "\n")

        // 7. Заглавная буква в начале каждой новой строки
        result = result.replace(Regex("(\\n)([а-яa-zё])")) {
            "${it.groupValues[1]}${it.groupValues[2].uppercase()}"
        }

        // 8. Исправление ё в распространённых словах
        result = fixYo(result)

        // 9. Убрать повторяющиеся знаки препинания (!!!→!, ???→?)
        result = result.replace(Regex("([!?.]){3,}"), "$1$1")

        // 10. Точка в конце длинных предложений без знака препинания
        if (result.isNotEmpty()
            && result.last() !in ".!?…)»\""
            && !result.contains("\n")
            && result.length > 15
        ) {
            result = "$result."
        }

        return result to (result != text)
    }

    // ── Исправление ё ──────────────────────────────────────────────────────

    private val yoReplacements = mapOf(
        "еще" to "ещё", "ее" to "её", "все" to "всё",
        "идет" to "идёт", "пойдет" to "пойдёт", "найдет" to "найдёт",
        "придет" to "придёт", "берет" to "берёт",
        "зеленый" to "зелёный", "черный" to "чёрный",
        "желтый" to "жёлтый", "тяжелый" to "тяжёлый",
        "теплый" to "тёплый", "твердый" to "твёрдый", "темный" to "тёмный",
        "приведет" to "приведёт",
        "пришел" to "пришёл", "ушел" to "ушёл", "нашел" to "нашёл",
        "подошел" to "подошёл", "зашел" to "зашёл", "вышел" to "вышёл",
        "шел" to "шёл", "несет" to "несёт", "везет" to "везёт",
        "полет" to "полёт", "самолет" to "самолёт",
        "ребенок" to "ребёнок", "ребенка" to "ребёнка",
    )

    private fun fixYo(text: String): String {
        var result = text
        for ((from, to) in yoReplacements) {
            result = result.replace(Regex("(?i)\\b${Regex.escape(from)}\\b")) { match ->
                if (match.value[0].isUpperCase()) to.replaceFirstChar { it.uppercase() } else to
            }
        }
        return result
    }
}

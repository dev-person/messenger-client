package com.secure.messenger.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * Сериализация / десериализация полезной нагрузки картинки.
 *
 * Формат — JSON:
 * ```
 * {
 *   "i": "<base64-bytes>",
 *   "m": "image/png" | "image/jpeg" | "image/gif" | "image/webp",
 *   "w": <width>, "h": <height>,
 *   "s": true   // опциональный флаг "это стикер" — рендерим без bubble и меньше
 * }
 * ```
 *
 * Файлы на сервере не хранятся — байты идут через encryptedContent ровно как
 * текст, шифруясь существующим E2E пайплайном (X25519 ECDH + AES-256-GCM).
 *
 * Логика обработки входных данных:
 * - Анимированные форматы (GIF, animated WebP) — байты сохраняются как есть, без
 *   перекодирования через BitmapFactory (иначе теряется анимация).
 * - PNG с прозрачностью — сохраняется как PNG (JPEG не поддерживает alpha).
 * - Стикеры (≤ 512px по короткой стороне И с прозрачностью) помечаются `s: true`.
 * - Обычные большие картинки → ужимаются и кодируются в JPEG quality 80.
 */
object ImageCodec {

    private const val MAX_DIMENSION = 1280
    private const val JPEG_QUALITY = 80

    /** Размер ≤ этого считается стикером (если PNG/WebP/GIF). Запас для крупных стикеров Gboard. */
    private const val STICKER_MAX_DIMENSION = 768

    /**
     * Жёсткий лимит на размер сырых байтов passthrough-формата (GIF/animated WebP/sticker).
     * Учитывает overhead base64 (~33%) + AES-GCM шифрование + JSON-обёртка → итоговая
     * encryptedContent должна влезать в Android SQLite CursorWindow (~2 МБ на строку).
     * 600 КБ raw → ~830 КБ base64 → ~870 КБ после шифрования = безопасно.
     */
    private const val MAX_RAW_BYTES = 600_000

    data class ImageData(
        val bytes: ByteArray,
        val mime: String,
        val width: Int,
        val height: Int,
        val isSticker: Boolean = false,
        /**
         * Идентификатор группы — одинаковый для всех картинок, отправленных
         * одним вызовом (например, пользователь выбрал 5 фото из галереи).
         * Клиент рендерит сообщения с одинаковым groupId одного отправителя
         * подряд как плитку (как в Telegram). Сервер про groupId не знает —
         * он зашифрован внутри encryptedContent вместе с картинкой.
         */
        val groupId: String? = null,
        /**
         * Номер картинки в группе (0-based). Нужен для стабильной сортировки
         * внутри плитки — иначе порядок зависит от order-of-arrival по WS.
         */
        val groupIndex: Int = 0,
        /**
         * Размер группы (сколько всего картинок отправлено вместе). Нужен UI
         * для правильного расчёта layout-а плитки и чтобы понять — пришла ли
         * вся группа целиком или нужно ждать остальных.
         */
        val groupSize: Int = 1,
    )

    /**
     * Загружает медиа-контент из URI и решает что с ним делать.
     * Анимированные/стикерные форматы передаются как есть; обычные фото — ужимаются.
     *
     * @param maxDim    максимальная сторона после ужатия (по умолчанию 1280)
     * @param quality   качество JPEG при перекодировке (по умолчанию 80)
     */
    fun loadAndCompress(
        context: Context,
        uri: Uri,
        maxDim: Int = MAX_DIMENSION,
        quality: Int = JPEG_QUALITY,
    ): ImageData? {
        return runCatching {
            // 1. Читаем размеры и реальный MIME без полной декодировки
            val resolver = context.contentResolver
            val detectedMime = resolver.getType(uri) ?: "image/*"

            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, boundsOptions)
            }
            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            val sniffedMime = boundsOptions.outMimeType ?: detectedMime
            if (origWidth <= 0 || origHeight <= 0) {
                Timber.w("ImageCodec: bounds decode failed for $uri (mime=$detectedMime)")
                return null
            }

            // 2. Читаем сырые байты — нужны для magic-byte детекта формата
            //    и для случая когда передаём «как есть» (стикер/гиф/анимация)
            val rawBytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null

            val isGif = sniffedMime == "image/gif" || isGifMagic(rawBytes)
            val isWebp = sniffedMime == "image/webp" || isWebpMagic(rawBytes)
            val isAnimatedWebp = isWebp && isAnimatedWebp(rawBytes)
            val isPng = sniffedMime == "image/png" || isPngMagic(rawBytes)

            Timber.d(
                "ImageCodec: input mime=$sniffedMime size=${origWidth}x${origHeight} " +
                        "bytes=${rawBytes.size} gif=$isGif webp=$isWebp animWebp=$isAnimatedWebp png=$isPng"
            )

            val maxSide = maxOf(origWidth, origHeight)
            val withinRawLimit = rawBytes.size <= MAX_RAW_BYTES
            val withinStickerSize = maxSide <= STICKER_MAX_DIMENSION

            // Для анимаций нет fallback — перекодирование убьёт анимацию.
            // Если файл слишком большой — отказываемся, лучше чем краш CursorWindow.
            if ((isGif || isAnimatedWebp) && !withinRawLimit) {
                Timber.w(
                    "ImageCodec: animated file too large (${rawBytes.size} bytes > $MAX_RAW_BYTES), skipping"
                )
                return null
            }

            // ── Анимированные GIF / WebP — passthrough всегда (любого размера до лимита)
            // Перекодирование через BitmapFactory убило бы анимацию.
            if ((isGif || isAnimatedWebp) && withinRawLimit) {
                val mime = if (isGif) "image/gif" else "image/webp"
                Timber.d("ImageCodec: passthrough animated $mime ${origWidth}x${origHeight}")
                return@runCatching ImageData(
                    bytes = rawBytes,
                    mime = mime,
                    width = origWidth,
                    height = origHeight,
                    // Анимированное всегда показываем без bubble — стикер или маленький GIF
                    isSticker = withinStickerSize,
                )
            }

            // ── Маленький PNG/WebP/GIF — стикер, сохраняем как есть (прозрачность)
            if (withinStickerSize && withinRawLimit && (isPng || isWebp || isGif)) {
                val mime = when {
                    isPng -> "image/png"
                    isGif -> "image/gif"
                    else -> "image/webp"
                }
                Timber.d("ImageCodec: passthrough sticker $mime ${origWidth}x${origHeight}")
                return@runCatching ImageData(
                    bytes = rawBytes,
                    mime = mime,
                    width = origWidth,
                    height = origHeight,
                    isSticker = true,
                )
            }

            // ── Большая картинка — декодируем и ужимаем в JPEG (или PNG если прозрачная)
            val maxOrig = maxOf(origWidth, origHeight)
            var sampleSize = 1
            while (maxOrig / sampleSize > maxDim * 2) sampleSize *= 2

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
                ?: return null

            // Финальное масштабирование до maxDim
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaled = if (scale < 1f) {
                val w = (bitmap.width * scale).toInt()
                val h = (bitmap.height * scale).toInt()
                Bitmap.createScaledBitmap(bitmap, w, h, true).also {
                    if (it != bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }

            // PNG если есть прозрачность (например, скриншот с alpha), иначе JPEG
            val hasAlpha = scaled.hasAlpha()
            val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val mime = if (hasAlpha) "image/png" else "image/jpeg"
            val out = ByteArrayOutputStream()
            scaled.compress(format, quality, out)
            val result = ImageData(
                bytes = out.toByteArray(),
                mime = mime,
                width = scaled.width,
                height = scaled.height,
                isSticker = false,
            )
            scaled.recycle()
            result
        }.onFailure { Timber.e(it, "ImageCodec.loadAndCompress failed") }.getOrNull()
    }

    /** Сериализует картинку в JSON для encryptedContent. */
    fun encode(data: ImageData): String {
        val base64 = Base64.encodeToString(data.bytes, Base64.NO_WRAP)
        return JSONObject().apply {
            put("i", base64)
            put("m", data.mime)
            put("w", data.width)
            put("h", data.height)
            if (data.isSticker) put("s", true)
            // Группа — пишем только когда сообщение входит в альбом (groupSize > 1)
            if (data.groupId != null && data.groupSize > 1) {
                put("g", data.groupId)
                put("gi", data.groupIndex)
                put("gn", data.groupSize)
            }
        }.toString()
    }

    /**
     * Лёгкая выжимка groupId / groupIndex / groupSize без декодирования
     * base64-байтов картинки — для группировки сообщений в альбомы в UI.
     * Не аллоцирует большой ByteArray, читает только несколько полей.
     */
    data class GroupInfo(val groupId: String, val index: Int, val size: Int)

    fun extractGroupInfo(payload: String): GroupInfo? {
        if (payload.isBlank()) return null
        // Fast-path: если в payload нет маркера группы — сразу null, без
        // парсинга JSON. Без него каждое IMAGE-сообщение грузило бы
        // JSONObject(500KB+ base64) только чтобы вернуть null.
        if (!payload.contains("\"g\":\"")) return null
        return runCatching {
            val obj = JSONObject(payload)
            val gId = obj.optString("g", "")
            if (gId.isEmpty()) return@runCatching null
            GroupInfo(
                groupId = gId,
                index = obj.optInt("gi", 0),
                size = obj.optInt("gn", 1),
            )
        }.getOrNull()
    }

    /** Десериализует payload — null если строка не парсится. */
    fun decode(payload: String): ImageData? {
        if (payload.isBlank()) return null
        return runCatching {
            val obj = JSONObject(payload)
            val base64 = obj.optString("i", "")
            if (base64.isEmpty()) return null
            val gId = obj.optString("g", "")
            ImageData(
                bytes = Base64.decode(base64, Base64.NO_WRAP),
                mime = obj.optString("m", "image/jpeg"),
                width = obj.optInt("w", 0),
                height = obj.optInt("h", 0),
                isSticker = obj.optBoolean("s", false),
                groupId = gId.ifEmpty { null },
                groupIndex = obj.optInt("gi", 0),
                groupSize = obj.optInt("gn", 1),
            )
        }.onFailure { Timber.w(it, "ImageCodec.decode failed") }.getOrNull()
    }

    // ── Magic bytes / format detection ────────────────────────────────────

    private fun isGifMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()

    private fun isPngMagic(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()

    private fun isWebpMagic(bytes: ByteArray): Boolean =
        bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()

    /**
     * Проверка анимированного WebP по структуре RIFF контейнера.
     *
     * Layout WebP:
     * ```
     *   0..11: RIFF header (RIFF + size + WEBP)
     *  12..15: первый chunk FourCC (для extended формата это "VP8X")
     *  16..19: chunk size (LE)
     *      20: байт флагов VP8X. Бит ANIMATION (значение 0x02) — признак анимации.
     * ```
     * Если первый chunk не VP8X — анимация невозможна (это VP8/VP8L = одиночный кадр).
     */
    private fun isAnimatedWebp(bytes: ByteArray): Boolean {
        if (!isWebpMagic(bytes)) return false
        if (bytes.size < 21) return false
        val isVp8x = bytes[12] == 'V'.code.toByte() && bytes[13] == 'P'.code.toByte() &&
                bytes[14] == '8'.code.toByte() && bytes[15] == 'X'.code.toByte()
        if (!isVp8x) return false
        // Бит animation = 0x02 в первом байте VP8X payload (offset 20)
        return (bytes[20].toInt() and 0x02) != 0
    }
}

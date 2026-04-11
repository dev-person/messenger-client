package com.secure.messenger.presentation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

/**
 * Стилизованная волнограмма (waveform) для голосового сообщения.
 *
 * Принимает массив амплитуд (любой длины), пересэмплирует под фиксированное
 * количество баров и рисует их через Canvas. Бары до точки воспроизведения
 * закрашиваются [activeColor], после — [inactiveColor].
 *
 * @param amplitudes исходные значения амплитуд (например, MediaRecorder.maxAmplitude)
 * @param progress   доля воспроизведения [0f..1f]
 * @param barCount   сколько баров рисовать (если исходных меньше — масштабируется)
 */
@Composable
fun WaveformBars(
    amplitudes: IntArray,
    progress: Float,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 42,
) {
    // Один раз пересэмплируем массив амплитуд под нужное число баров и нормализуем к [0..1]
    val normalized = remember(amplitudes, barCount) {
        resampleAndNormalize(amplitudes, barCount)
    }

    Canvas(modifier = modifier) {
        if (normalized.isEmpty()) return@Canvas

        val n = normalized.size
        val totalWidth = size.width
        val totalHeight = size.height
        val gap = 2f
        val barWidth = ((totalWidth - gap * (n - 1)) / n).coerceAtLeast(1f)
        val centerY = totalHeight / 2f
        val minBarHeight = 3f
        val maxBarHeight = totalHeight

        val activeBars = (n * progress).toInt().coerceIn(0, n)

        for (i in 0 until n) {
            val height = (minBarHeight + (maxBarHeight - minBarHeight) * normalized[i])
                .coerceIn(minBarHeight, maxBarHeight)
            val x = i * (barWidth + gap)
            val color = if (i < activeBars) activeColor else inactiveColor

            drawLine(
                color = color,
                start = Offset(x + barWidth / 2f, centerY - height / 2f),
                end = Offset(x + barWidth / 2f, centerY + height / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Пересэмплирует [src] до [target] баров и приводит значения к диапазону [0..1].
 *
 * Поддерживает оба случая:
 *  - Downsampling (исходных значений больше [target]): берём максимум по каждому бакету
 *  - Upsampling   (исходных меньше): линейная интерполяция между ближайшими точками
 *    чтобы для коротких записей всё равно было визуальное разнообразие.
 *
 * После нормализации применяется sqrt-шкалирование — это усиливает визуальную
 * разницу между тихими и громкими участками (восприятие звука человеком тоже
 * нелинейное). Без sqrt тихие записи сжимаются в плоскую низкую полоску.
 */
private fun resampleAndNormalize(src: IntArray, target: Int): FloatArray {
    if (target <= 0) return FloatArray(0)

    // Нет данных (старые сообщения без waveform) — псевдо-синусоидный плейсхолдер
    if (src.isEmpty()) {
        return FloatArray(target) { i ->
            val phase = i * 0.5
            (0.35f + 0.4f * kotlin.math.abs(kotlin.math.sin(phase)).toFloat())
        }
    }

    val out = FloatArray(target)

    if (src.size >= target) {
        // ── Downsampling: бакетируем и берём максимум каждой группы ────
        val perBucket = src.size.toDouble() / target
        for (i in 0 until target) {
            val from = (i * perBucket).toInt()
            val to = ((i + 1) * perBucket).toInt().coerceAtMost(src.size).coerceAtLeast(from + 1)
            var bucketMax = 0
            for (j in from until to) {
                if (src[j] > bucketMax) bucketMax = src[j]
            }
            out[i] = bucketMax.toFloat()
        }
    } else {
        // ── Upsampling: линейная интерполяция между соседними сэмплами ─
        // Без этого короткие записи давали повторяющиеся одинаковые бары.
        for (i in 0 until target) {
            val pos = i.toDouble() * (src.size - 1) / (target - 1).coerceAtLeast(1)
            val lo = pos.toInt().coerceIn(0, src.size - 1)
            val hi = (lo + 1).coerceAtMost(src.size - 1)
            val t = (pos - lo).toFloat()
            out[i] = src[lo] * (1f - t) + src[hi] * t
        }
    }

    // Нормализация + sqrt для нелинейного восприятия амплитуды
    val maxValue = out.maxOrNull() ?: 0f
    if (maxValue <= 0f) {
        for (i in out.indices) out[i] = 0.25f
    } else {
        for (i in out.indices) {
            val normalized = out[i] / maxValue
            out[i] = kotlin.math.sqrt(normalized).coerceIn(0f, 1f)
        }
    }
    return out
}

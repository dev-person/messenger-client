package com.secure.messenger.presentation.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Анимированный shimmer-brush для скелетон-плейсхолдеров.
 *
 * Возвращает [Brush] с линейным градиентом, который «бежит» слева направо
 * по диагонали — стандартный эффект мигающего шиммера. Цвета берутся из темы:
 *  - база: surfaceVariant (не очень контрастный фон)
 *  - блик: surfaceContainerHigh (чуть светлее)
 *
 * Использование:
 * ```
 * Box(
 *     modifier = Modifier
 *         .size(200.dp, 24.dp)
 *         .clip(RoundedCornerShape(6.dp))
 *         .background(shimmerBrush())
 * )
 * ```
 */
@Composable
fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-translate",
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surfaceContainerHigh
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(translate - 300f, translate - 300f),
        end = Offset(translate, translate),
    )
}

/**
 * Удобный плейсхолдер: прямоугольник со скруглением и шиммер-фоном.
 * Используется как замена реальному элементу пока данные грузятся.
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(shimmerBrush()),
    )
}

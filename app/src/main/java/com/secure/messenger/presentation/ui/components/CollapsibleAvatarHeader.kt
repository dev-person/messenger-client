package com.secure.messenger.presentation.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Карточка-аватар с inline-раскрытием в стиле Telegram. Карточка живёт в
 * обычном layout-потоке (не overlay): при тапе её высота анимируется до
 * половины экрана, ширина — до полной, углы — до 0.
 *
 * Сама карточка реагирует ТОЛЬКО на тап. Жест «свайп-вверх для свёртывания»
 * вешается на весь экран отдельным модификатором [collapsibleAvatarDismissOnSwipe] —
 * так юзер может смахнуть аватар откуда угодно, а не только попадая в саму
 * картинку.
 *
 * При expanded автоматически переключаются иконки status-bar в светлый
 * вариант — фото обычно тёмное и без этого иконки сливаются.
 *
 * Чтобы карточка действительно занимала всю ширину при expanded, родитель
 * должен убирать свой horizontal padding в этом состоянии.
 */
@Composable
fun CollapsibleAvatarHeader(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    collapsedSize: Dp = 96.dp,
    expandedHeightFraction: Float = 0.5f,
    collapsedShape: androidx.compose.ui.graphics.Shape =
        androidx.compose.foundation.shape.CircleShape,
    content: @Composable (Modifier) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val expandedHeightDp = (configuration.screenHeightDp * expandedHeightFraction).dp
    val expandedWidthDp = configuration.screenWidthDp.dp

    val animSpec = tween<Dp>(durationMillis = 320, easing = FastOutSlowInEasing)
    val height by animateDpAsState(
        targetValue = if (expanded) expandedHeightDp else collapsedSize,
        animationSpec = animSpec,
        label = "collapsible-avatar-height",
    )
    val width by animateDpAsState(
        targetValue = if (expanded) expandedWidthDp else collapsedSize,
        animationSpec = animSpec,
        label = "collapsible-avatar-width",
    )
    val cornerProgress by animateDpAsState(
        // Используем dp как «прогресс» 0..100 — потом из неё считаем percent
        // для RoundedCornerShape, чтобы плавно перейти от круга к прямоугольнику.
        targetValue = if (expanded) 0.dp else 50.dp,
        animationSpec = animSpec,
        label = "collapsible-avatar-corner",
    )

    // System back при expanded — свернуть, не уходить с экрана.
    BackHandler(enabled = expanded) { onExpandedChange(false) }

    // Status-bar icons → светлые когда expanded.
    val view = LocalView.current
    val isDarkAppTheme = androidx.compose.material3.MaterialTheme.colorScheme
        .background.luminance() < 0.5f
    DisposableEffect(expanded) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = if (expanded) false else !isDarkAppTheme
        onDispose { previous?.let { controller?.isAppearanceLightStatusBars = it } }
    }

    val shape = if (cornerProgress.value >= 50f) collapsedShape
                else RoundedCornerShape(percent = cornerProgress.value.toInt())

    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(shape)
            // Только тап — раскрыть/свернуть. Свайп вверх обрабатывается на
            // уровне всего экрана (см. collapsibleAvatarDismissOnSwipe).
            .clickable { onExpandedChange(!expanded) },
    ) {
        content(Modifier.fillMaxWidth().height(height))
    }
}

/**
 * Модификатор для контейнера экрана: при `expanded == true` ловит свайп
 * ВВЕРХ в любой точке экрана и сворачивает аватар. Когда не expanded —
 * никаких событий не перехватывает, скролл и тапы работают как обычно.
 *
 * Применяется к самому внешнему layout экрана (Column, Box, Scaffold-content
 * и т.п.) ПЕРЕД verticalScroll, чтобы перехватить жест до того как он
 * уедет в скролл.
 *
 * Важно: при свайпе ВНИЗ (delta > 0) этот модификатор не консьюмит event —
 * нисходящий скролл страницы продолжает работать.
 */
fun Modifier.collapsibleAvatarDismissOnSwipe(
    expanded: Boolean,
    onCollapse: () -> Unit,
): Modifier = composed {
    if (!expanded) return@composed this
    val density = LocalDensity.current
    val thresholdPx = with(density) { 60.dp.toPx() }
    pointerInput(Unit) {
        var totalDrag = 0f
        detectVerticalDragGestures(
            onVerticalDrag = { change, delta ->
                // Только восходящие движения. Нисходящие пусть обработает
                // скролл страницы (если есть).
                if (delta < 0 || totalDrag < 0) {
                    totalDrag = (totalDrag + delta).coerceAtMost(0f)
                    change.consume()
                }
            },
            onDragEnd = {
                if (-totalDrag > thresholdPx) onCollapse()
                totalDrag = 0f
            },
            onDragCancel = { totalDrag = 0f },
        )
    }
}

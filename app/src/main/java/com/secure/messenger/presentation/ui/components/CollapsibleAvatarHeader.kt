package com.secure.messenger.presentation.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch

/**
 * Карточка-аватар с inline-раскрытием в стиле Telegram. Карточка живёт в
 * обычном layout-потоке (не overlay): при тапе её высота анимируется до
 * половины экрана, ширина — до полной, углы — до 0. При втором тапе или
 * свайпе вниз — обратно. Свайп ВВЕРХ игнорируется.
 *
 * Чтобы карточка действительно занимала всю ширину при expanded, родитель
 * должен убирать свой horizontal padding в этом состоянии (см.
 * [containerHorizontalPad] в вызывающих экранах).
 *
 * При expanded автоматически переключаются иконки status-bar в светлый
 * вариант — фото обычно тёмное и без этого иконки сливаются.
 *
 * @param expanded     текущее состояние (true → раскрыт)
 * @param onExpandedChange колбэк изменения состояния (тап / back / свайп)
 * @param collapsedSize размер карточки в свёрнутом виде (квадрат)
 * @param expandedHeightFraction доля высоты экрана при expanded (0..1, по умолчанию 0.5)
 * @param collapsedShape форма карточки в свёрнутом виде (по умолчанию круг)
 * @param content       содержимое карточки (фото / заглушка). Получает
 *                      Modifier который сам берёт fillMaxSize нужного размера.
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
    val density = LocalDensity.current
    val expandedHeightDp = (configuration.screenHeightDp * expandedHeightFraction).dp
    val expandedWidthDp = configuration.screenWidthDp.dp
    val dismissThresholdPx = with(density) { (expandedHeightDp.toPx() * 0.30f) }

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
        // Используем dp как «прогресс» в виде 0..100 — потом из неё считаем
        // percent для RoundedCornerShape, чтобы плавно перейти от круга к
        // прямоугольнику без перепрыгивания формы.
        targetValue = if (expanded) 0.dp else 50.dp,
        animationSpec = animSpec,
        label = "collapsible-avatar-corner",
    )

    // System back при expanded — свернуть, не уходить.
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

    // Visual feedback свайпа: карточка едет за пальцем по Y. Без этого
    // пользователю казалось, что свайп вообще не реагирует.
    val dragY = remember { Animatable(0f) }
    val swipeScope = rememberCoroutineScope()
    LaunchedEffect(expanded) {
        if (!expanded) dragY.snapTo(0f)
    }

    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .graphicsLayer { translationY = dragY.value }
            .clip(shape)
            .clickable { onExpandedChange(!expanded) }
            // Свайп ВНИЗ свернёт карточку. Активен только когда expanded —
            // в свёрнутом состоянии вертикальные жесты на крошечной аватарке
            // мешали бы скроллу страницы.
            .pointerInput(expanded) {
                if (!expanded) return@pointerInput
                var totalDrag = 0f
                detectVerticalDragGestures(
                    onVerticalDrag = { change, delta ->
                        if (delta > 0 || totalDrag > 0) {
                            totalDrag = (totalDrag + delta).coerceAtLeast(0f)
                            swipeScope.launch { dragY.snapTo(totalDrag) }
                            change.consume()
                        }
                    },
                    onDragEnd = {
                        if (totalDrag > dismissThresholdPx) {
                            onExpandedChange(false)
                        } else {
                            swipeScope.launch { dragY.animateTo(0f, tween(180)) }
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = {
                        swipeScope.launch { dragY.animateTo(0f, tween(180)) }
                        totalDrag = 0f
                    },
                )
            },
    ) {
        content(Modifier.fillMaxWidth().height(height))
    }
}

package com.secure.messenger.presentation.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Telegram-style разворот аватара **прямо внутри текущего экрана**, без
 * Dialog — чтобы можно было заехать поверх системного статус-бара (Dialog
 * получает свой window-decor и не выходит выше этого декора).
 *
 * Использование: положить компонент в **корневой** Box(fillMaxSize) экрана,
 * после основного контента — он сам растянется на весь Box, нарисует
 * затемнение и поверх — увеличенный аватар (квадрат ~½ высоты экрана,
 * заезжающий поверх статус-бара).
 *
 * Анимация:
 *  - открытие: alpha 0→1, scaleY 0→1 (карточка как будто разворачивается
 *    сверху вниз);
 *  - свайп вниз: карточка едет за пальцем, alpha фона уменьшается; после
 *    порога ≈25% высоты экрана — close с обратной анимацией, иначе
 *    snap-back в исходное состояние;
 *  - тап вне карточки или back — close.
 */
@Composable
fun ExpandedAvatarOverlay(
    imageUrl: String?,
    fallbackName: String,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val screenHeightPx = with(density) { screenHeightDp.toPx() }
    val expandedHeightDp = screenHeightDp * 0.5f
    val dismissThresholdPx = screenHeightPx * 0.25f

    val openProgress = remember { Animatable(0f) }
    val dragY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var dismissing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        openProgress.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
    }

    fun close() {
        if (dismissing) return
        dismissing = true
        scope.launch {
            openProgress.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
            onDismiss()
        }
    }

    BackHandler { close() }

    val dragRelative = (dragY.value / screenHeightPx).coerceIn(0f, 1f)
    val backdropAlpha = 0.85f * openProgress.value * (1f - dragRelative)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backdropAlpha))
            .pointerInput(Unit) {
                // Тап вне карточки — закрытие.
                detectTapGestures(onTap = { close() })
            },
    ) {
        // Карточка-картинка: fillMaxWidth, высота expandedHeightDp.
        // Top-aligned, рисуется ПОВЕРХ status bar (никакого statusBarsPadding —
        // это и есть желаемый эффект «заехать выше»).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(expandedHeightDp)
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    // scaleY от 0.05 до 1 — эффект «выпрыгивания» из верхнего
                    // края (transformOrigin = top center).
                    val openScaleY = 0.05f + 0.95f * openProgress.value
                    scaleY = openScaleY
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    translationY = dragY.value
                    alpha = openProgress.value * (1f - dragRelative * 0.6f)
                }
                .background(Color.Black)
                .pointerInput(Unit) {
                    var totalDrag = 0f
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, delta ->
                            // Свайпом вверх не даём поднять — Telegram так же.
                            if (delta > 0 || totalDrag > 0) {
                                totalDrag = (totalDrag + delta).coerceAtLeast(0f)
                                scope.launch { dragY.snapTo(totalDrag) }
                            }
                        },
                        onDragEnd = {
                            if (totalDrag > dismissThresholdPx) {
                                close()
                            } else {
                                scope.launch { dragY.animateTo(0f, tween(180)) }
                            }
                            totalDrag = 0f
                        },
                        onDragCancel = {
                            scope.launch { dragY.animateTo(0f, tween(180)) }
                            totalDrag = 0f
                        },
                    )
                },
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = "Аватар",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AvatarPlaceholder(fallbackName)
            }
        }
    }
}

@Composable
private fun AvatarPlaceholder(name: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull()?.toString()?.uppercase() ?: "?",
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 96.sp,
        )
    }
}

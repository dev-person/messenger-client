package com.secure.messenger.presentation.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas as ComposeCanvas
import kotlin.math.min

/**
 * Полноэкранный оверлей обрезки аватара круглой областью.
 *
 * Не Dialog — обычный Composable. Используется как overlay в ProfileEditScreen-е
 * (рисуется как «верхний» Box внутри родительского Box.fillMaxSize). Раньше был
 * сделан как Dialog с usePlatformDefaultWidth=false + decorFitsSystemWindows=false,
 * но на некоторых устройствах диалог отказывался становиться действительно
 * полноэкранным — кнопки уезжали за экран, фон не накрывал статус/нав-бары.
 *
 * Перехватывает системную кнопку «назад» через [BackHandler] чтобы закрыть
 * только себя, а не всю экран профиля.
 */
@Composable
fun AvatarCropDialog(
    sourceBitmap: Bitmap,
    onConfirm: (Bitmap) -> Unit,
    onCancel: () -> Unit,
) {
    // Параметры пользовательской трансформации картинки
    var userScale by remember { mutableStateOf(1f) }
    var userOffset by remember { mutableStateOf(Offset.Zero) }
    // Размер view (квадратной области с картинкой) в пикселях — нужен для
    // обратного пересчёта координат круга в координаты исходного bitmap-а.
    var viewSizePx by remember { mutableStateOf(IntPair(0, 0)) }

    androidx.activity.compose.BackHandler { onCancel() }

    // Корневой Box — занимает весь родительский размер. Родитель в
    // ProfileEditScreen — это Box.fillMaxSize, так что cropper тоже full-screen.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Гасим тапы по фону — чтобы они не доходили до экрана профиля
            // под нами (иначе можно было бы случайно нажать что-то «через» оверлей).
            .pointerInput(Unit) {
                detectTransformGestures { _, _, _, _ -> }
            },
        contentAlignment = Alignment.Center,
    ) {
            // ── Квадратная зона кропа по центру экрана ──────────────────
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                // Сторона квадрата — min(width, height) с запасом под
                // заголовок сверху и кнопки снизу (~280dp)
                val side = if (maxWidth < (maxHeight - 280.dp)) maxWidth - 32.dp
                           else (maxHeight - 280.dp).coerceAtLeast(160.dp)

                Box(
                    modifier = Modifier
                        .size(side)
                        // ВАЖНО: graphicsLayer на Image не клипит вылеты при
                        // pinch-zoom / pan — масштабированная картинка вылезала
                        // за пределы квадрата за пределы маски. clipToBounds
                        // обрезает всё что выходит за границы Box-а.
                        .clipToBounds()
                        .background(Color(0xFF101010))
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                userScale = (userScale * zoom).coerceIn(1f, 6f)
                                userOffset += pan
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = sourceBitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                viewSizePx = IntPair(size.width.toInt(), size.height.toInt())
                                scaleX = userScale
                                scaleY = userScale
                                translationX = userOffset.x
                                translationY = userOffset.y
                            },
                    )
                    // Тёмная вуаль с круглой «дыркой» + рамка круга.
                    // ВАЖНО: BlendMode.Clear работает только если Canvas
                    // рисуется в собственный offscreen-layer. Без него Clear
                    // прорезает дыру до родительского фона (= чёрный),
                    // а нам надо чтобы дыра показывала картинку под нею.
                    ComposeCanvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                            },
                    ) {
                        val sideSizePx = min(size.width, size.height)
                        val radius = sideSizePx / 2f - 8f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        // Тёмное затемнение поверх картинки. Alpha 0.7 — видно
                        // даже на светлых фотографиях, в Telegram примерно так же.
                        drawRect(color = Color.Black.copy(alpha = 0.7f))
                        // Прорезаем круглое прозрачное окно (BlendMode.Clear),
                        // чтобы внутри круга картинка показывалась без затемнения.
                        drawCircle(
                            color = Color.Black,
                            radius = radius,
                            center = center,
                            blendMode = BlendMode.Clear,
                        )
                        // Тонкая белая рамка по краю круга
                        drawCircle(
                            color = Color.White,
                            radius = radius,
                            center = center,
                            style = Stroke(width = 3f),
                        )
                    }
                }
            }

            // ── Заголовок сверху ────────────────────────────────────────
            Text(
                text = "Выберите область",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                textAlign = TextAlign.Center,
            )

            // ── Подсказка + кнопки снизу ───────────────────────────────
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Двумя пальцами увеличьте, пальцем переместите",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    textAlign = TextAlign.Center,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Отмена")
                    }
                    Button(
                        onClick = {
                            val (vw, vh) = viewSizePx
                            if (vw > 0 && vh > 0) {
                                val cropped = cropAroundCircle(
                                    source = sourceBitmap,
                                    viewW = vw.toFloat(),
                                    viewH = vh.toFloat(),
                                    userScale = userScale,
                                    userOffset = userOffset,
                                    outputSize = OUTPUT_SIZE,
                                )
                                onConfirm(cropped)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Готово")
                    }
                }
            }
    }
}

/**
 * Вырезает квадратный участок исходного [source] по координатам круга в
 * view-области и масштабирует до [outputSize]×[outputSize] пикселей.
 *
 * Алгоритм:
 *  1. Считаем «базовый scale» — как Compose ContentScale.Fit вписывает
 *     bitmap в квадрат view размером [viewW]×[viewH].
 *  2. Финальный scale картинки в view = baseScale * userScale.
 *  3. Центр круга в view = (viewW/2, viewH/2). Сдвиг bitmap-а от центра view
 *     — это userOffset (graphicsLayer применяет translation к уже отрендерённому
 *     bitmap, а не к bitmap-source).
 *  4. Координаты центра круга в системе bitmap получаем обратной трансформацией.
 *  5. Создаём новый Bitmap [outputSize]×[outputSize] и через Canvas + Matrix
 *     рисуем нужный регион исходного bitmap-а в него.
 */
private fun cropAroundCircle(
    source: Bitmap,
    viewW: Float,
    viewH: Float,
    userScale: Float,
    userOffset: Offset,
    outputSize: Int,
): Bitmap {
    val srcW = source.width.toFloat()
    val srcH = source.height.toFloat()

    val baseScale = min(viewW / srcW, viewH / srcH)
    val totalScale = baseScale * userScale

    // Центр view (=центр bitmap-а до пользовательских трансформаций)
    val fittedCenterX = viewW / 2f
    val fittedCenterY = viewH / 2f

    val viewSide = min(viewW, viewH)
    val circleRadiusView = viewSide / 2f - 8f

    // Центр круга — тоже центр view (визуальный центр экрана кропа)
    val circleCx = viewW / 2f
    val circleCy = viewH / 2f

    // Центр bitmap-а после трансформ = fittedCenter + userOffset
    val bitmapCenterViewX = fittedCenterX + userOffset.x
    val bitmapCenterViewY = fittedCenterY + userOffset.y

    // Дельта от центра bitmap до центра круга в view-координатах
    val dxView = circleCx - bitmapCenterViewX
    val dyView = circleCy - bitmapCenterViewY

    // Переводим в координаты source bitmap (делим на totalScale)
    val srcCenterX = srcW / 2f + dxView / totalScale
    val srcCenterY = srcH / 2f + dyView / totalScale
    val srcRadius = circleRadiusView / totalScale
    val srcSide = 2f * srcRadius

    val srcLeft = srcCenterX - srcRadius
    val srcTop = srcCenterY - srcRadius

    val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    canvas.drawColor(android.graphics.Color.BLACK) // фон под «вылетом» за границы

    val matrix = Matrix()
    val outScale = outputSize / srcSide
    matrix.postTranslate(-srcLeft, -srcTop)
    matrix.postScale(outScale, outScale)

    val paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    canvas.drawBitmap(source, matrix, paint)
    return output
}

/** Маленький value-class для пары int-ов. */
private data class IntPair(val first: Int, val second: Int)

private const val OUTPUT_SIZE = 512

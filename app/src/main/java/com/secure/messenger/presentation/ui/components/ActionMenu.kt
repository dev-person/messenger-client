package com.secure.messenger.presentation.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * Элемент контекстного меню действий.
 *
 * @param label    Текст пункта
 * @param icon     Иконка слева (опционально)
 * @param danger   Если true — пункт окрашивается в цвет ошибки (для деструктивных действий)
 * @param onClick  Колбэк при выборе
 */
data class ActionMenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Симпатичное контекстное меню действий, появляется на месте касания.
 *
 * Используется для всех долгих нажатий на сообщения / чаты / другие элементы.
 * Положение задаётся через [anchorOffset] в пикселях относительно родительского Box.
 * Меню автоматически закрывается при тапе вне или после выбора пункта.
 *
 * Кастомный [PopupPositionProvider] адаптирует положение: если popup не помещается
 * под точкой касания (например, последнее сообщение в чате) — рисует его выше точки.
 */
@Composable
fun ActionMenu(
    visible: Boolean,
    anchorOffset: IntOffset,
    onDismiss: () -> Unit,
    actions: List<ActionMenuItem>,
) {
    if (!visible || actions.isEmpty()) return

    val positionProvider = remember(anchorOffset) {
        TouchAnchoredPopupPositionProvider(anchorOffset)
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 220.dp, max = 300.dp)
                    .padding(vertical = 6.dp),
            ) {
                actions.forEach { action ->
                    val itemColor = if (action.danger) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                action.onClick()
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (action.icon != null) {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                                tint = itemColor,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                        }
                        Text(
                            text = action.label,
                            color = itemColor,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Modifier-обработчик долгого нажатия с возвращением точки касания.
 *
 * Поддерживает обычный клик и ripple-эффект вместе с long-press, в отличие от
 * [androidx.compose.foundation.combinedClickable], который не отдаёт координаты.
 *
 * @param onClick      Обычный одиночный тап
 * @param onLongPress  Долгое нажатие — получает Offset в координатах родителя
 */
fun Modifier.longPressActionable(
    onClick: (() -> Unit)? = null,
    onLongPress: (Offset) -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val rippleIndication = ripple()
    this
        .indication(interactionSource, rippleIndication)
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = { offset ->
                    val press = PressInteraction.Press(offset)
                    interactionSource.tryEmit(press)
                    val released = tryAwaitRelease()
                    if (released) {
                        interactionSource.tryEmit(PressInteraction.Release(press))
                    } else {
                        interactionSource.tryEmit(PressInteraction.Cancel(press))
                    }
                },
                onLongPress = onLongPress,
                onTap = onClick?.let { { _ -> it() } },
            )
        }
}

/**
 * Запоминает позицию касания в формате [IntOffset] для передачи в [ActionMenu].
 * Удобно использовать вместе с [longPressActionable].
 */
fun Offset.toIntOffset(): IntOffset = IntOffset(x.roundToInt(), y.roundToInt())

/**
 * Provider, который ставит popup в точку касания (anchorBounds top-left + [touchOffset]).
 * Если popup не помещается снизу — переключается на «расти вверх» (отрисовка над точкой).
 * Аналогично — если не помещается справа, выравнивается по правому краю.
 *
 * Это решает проблему когда последнее сообщение в чате близко к нижнему краю
 * экрана и стандартный popup обрезался.
 */
private class TouchAnchoredPopupPositionProvider(
    private val touchOffset: IntOffset,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // Стартовая точка — позиция касания внутри родителя
        var x = anchorBounds.left + touchOffset.x
        var y = anchorBounds.top + touchOffset.y

        // По горизонтали: если не влезает справа — сдвигаем влево чтобы вписать
        if (x + popupContentSize.width > windowSize.width) {
            x = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        }
        if (x < 0) x = 0

        // По вертикали: если не влезает снизу — рисуем выше точки касания (anchor bottom)
        if (y + popupContentSize.height > windowSize.height) {
            val above = y - popupContentSize.height
            y = if (above >= 0) above
                else (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        }

        return IntOffset(x, y)
    }
}

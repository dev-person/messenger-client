package com.secure.messenger.presentation.theme

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.view.View
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Координатор анимации смены темы через круговой reveal.
 *
 * Реализация через снимок текущего View в Bitmap. Compose-вариант через
 * `rememberGraphicsLayer` + `record` на полноэкранном Composable оказался
 * нестабильным (слой оставался пустым при определённом вложении Modifier-ов),
 * поэтому вернулись к проверенному классическому подходу: прямо перед сменой
 * темы вызываем `view.draw(Canvas)` на битмапе размером с View — получаем
 * пиксели СТАРОГО UI, а Theme затем рисует их поверх нового содержимого
 * с растущим круговым вырезом.
 *
 * Контракт использования:
 *  1. В настройках при клике на схему:
 *     - получить корневой View из LocalView
 *     - вызвать [startReveal] (передать View и целевой scheme)
 *     - сразу применить новую схему через `ThemePreferences.setColorScheme`
 *  2. `SecureMessengerTheme` подписан на [activeReveal] и рендерит overlay.
 *  3. По окончании анимации overlay вызывает [endReveal] — снимок
 *     освобождается.
 */
object ThemeTransition {

    /**
     * Параметры активного reveal. Храним [ImageBitmap] снимка старого UI —
     * чтобы overlay мог рисовать его через `DrawScope.drawImage`.
     */
    data class Spec(
        val snapshot: ImageBitmap,
        /**
         * Уникальный nonce — позволяет LaunchedEffect(spec?.nonce) перезапустить
         * анимацию даже если пользователь выбрал ту же схему (маловероятно,
         * но полезно для отладки).
         */
        val nonce: Long,
    )

    private val _activeReveal = MutableStateFlow<Spec?>(null)
    val activeReveal: StateFlow<Spec?> = _activeReveal.asStateFlow()

    /**
     * Захватывает текущий [view] в битмап и активирует reveal. Захват
     * синхронный (`view.draw` на main thread) — занимает обычно < 10 мс
     * на современных устройствах. Выполнять ДО применения новой темы,
     * иначе в снимке окажется уже новый UI.
     */
    fun startReveal(view: View) {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) {
            Timber.w("ThemeTransition.startReveal: view has zero size (${width}x${height})")
            return
        }
        runCatching {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = AndroidCanvas(bitmap)
            view.draw(canvas)
            _activeReveal.value = Spec(
                snapshot = bitmap.asImageBitmap(),
                nonce = System.currentTimeMillis(),
            )
        }.onFailure { e ->
            Timber.e(e, "ThemeTransition.startReveal: failed to capture view")
        }
    }

    /** Вызывается оверлеем после завершения анимации — освобождает снимок. */
    fun endReveal() {
        _activeReveal.value = null
    }
}

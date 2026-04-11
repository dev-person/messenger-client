package com.secure.messenger.presentation.ui.components

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatEditText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.OnReceiveContentListener
import androidx.core.view.ViewCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import timber.log.Timber

/**
 * Custom EditText, который при подключении IME явно объявляет что принимает картинки/гифы.
 *
 * Compose `BasicTextField.contentReceiver` для этого не подходит — он работает только
 * через AndroidX content API на уровне View и для legacy `BasicTextField` (TextFieldValue)
 * не пробрасывает MIME-types в `EditorInfo.contentMimeTypes`. Из-за этого Gboard не
 * показывает кнопку «отправить стикер» — приложение для него выглядит как чисто текстовое.
 *
 * Здесь мы наследуемся от стандартного AppCompatEditText, в onCreateInputConnection
 * вызываем EditorInfoCompat.setContentMimeTypes и оборачиваем connection через
 * InputConnectionCompat. Колбэк ловится через ViewCompat.setOnReceiveContentListener
 * (его навешиваем снаружи).
 */
class RichInputEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatEditText(context, attrs) {

    /** MIME-типы, которые этот инпут принимает от клавиатуры. */
    private val acceptedMimeTypes = arrayOf(
        "image/png",
        "image/gif",
        "image/jpeg",
        "image/webp",
    )

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic: InputConnection = super.onCreateInputConnection(outAttrs) ?: return null
        // Явно говорим клавиатуре, что принимаем картинки/гифы.
        // Без этой строки Gboard прячет кнопку отправки стикера для нашего поля.
        EditorInfoCompat.setContentMimeTypes(outAttrs, acceptedMimeTypes)
        return InputConnectionCompat.createWrapper(this, ic, outAttrs)
    }
}

/**
 * Compose-обёртка над [RichInputEditText] для использования в `MessageInputBar`.
 * Поддерживает то же API что и BasicTextField, плюс реальный приём стикеров/гифов
 * через системную клавиатуру.
 */
@Composable
fun RichInputField(
    text: String,
    placeholder: String,
    onTextChange: (String) -> Unit,
    onContentReceived: (android.net.Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val cursorTint = MaterialTheme.colorScheme.primary.toArgb()

    // Сохраняем актуальный callback в remember чтобы listener не пересоздавался
    val latestCallback = remember(onContentReceived) { onContentReceived }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = RichInputEditText(ctx)
            view.background = null
            view.setTextColor(textColor)
            view.setHintTextColor(hintColor)
            view.hint = placeholder
            view.isSingleLine = false
            view.maxLines = 6
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.isClickable = true
            view.inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            view.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            view.setPadding(0, 0, 0, 0)
            // По центру вертикали — иначе на устройствах с min-height родителя
            // (heightIn(min=46.dp) у Compose-обёртки) hint и одна строка текста
            // прилипают к верхнему краю.
            view.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            // Убираем стандартный шрифтовой паддинг — он добавляет ~3-5dp сверху
            // и снизу, из-за чего визуально hint смещается вверх.
            view.includeFontPadding = false
            // Курсор того же цвета что primary темы (доступно с API 29)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                runCatching { view.textCursorDrawable?.setTint(cursorTint) }
            }

            // Compose AndroidView не пробрасывает softInput автоматически —
            // явно вызываем клавиатуру при получении фокуса и при тапе
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            view.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.post { imm?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT) }
                }
            }
            view.setOnClickListener {
                if (!view.hasFocus()) view.requestFocus()
                imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }

            view.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val newText = s?.toString().orEmpty()
                    // Защита от рекурсии — обновляем только если реально изменилось
                    if (newText != view.tag) {
                        view.tag = newText
                        latestCallback // no-op чтобы захватить
                        onTextChange(newText)
                    }
                }
            })

            // Слушатель приёма медиа-контента (картинки/гифы из Gboard)
            ViewCompat.setOnReceiveContentListener(
                view,
                arrayOf("image/*"),
                OnReceiveContentListener { _, payload ->
                    val partition = payload.partition { item -> item.uri != null }
                    val withUri = partition.first
                    val remaining = partition.second
                    if (withUri != null) {
                        val clip = withUri.clip
                        Timber.d("RichInput: received items=${clip.itemCount}")
                        for (i in 0 until clip.itemCount) {
                            val uri = clip.getItemAt(i).uri ?: continue
                            Timber.d("RichInput: uri=$uri")
                            latestCallback(uri)
                        }
                    }
                    // Текст и прочее — пропускаем дальше в стандартную обработку EditText
                    remaining
                },
            )
            view
        },
        update = { editText ->
            // Синхронизация значения извне (например, при cancelEditing / sendMessage)
            if (editText.text?.toString() != text) {
                editText.tag = text
                editText.setText(text)
                editText.setSelection(text.length)
            }
        },
    )
}

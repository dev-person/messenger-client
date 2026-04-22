package com.secure.messenger.presentation.theme

import android.content.Context
import android.content.SharedPreferences
import com.secure.messenger.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Варианты обоев для диалогов. NONE — показывается сгенерированный
 * паттерн на основе цветовой схемы (поведение по умолчанию).
 * Остальные — готовые картинки из res/drawable-nodpi/chat_wallpaper_*.
 */
enum class ChatWallpaper(val label: String, val drawableRes: Int?) {
    NONE("Без фона",    null),
    IMG_1("Вариант 1",  R.drawable.chat_wallpaper_1),
    IMG_2("Вариант 2",  R.drawable.chat_wallpaper_2),
    IMG_3("Вариант 3",  R.drawable.chat_wallpaper_3),
    IMG_4("Вариант 4",  R.drawable.chat_wallpaper_4),
    IMG_5("Вариант 5",  R.drawable.chat_wallpaper_5),
    IMG_6("Вариант 6",  R.drawable.chat_wallpaper_6),
    IMG_7("Вариант 7",  R.drawable.chat_wallpaper_7),
}

/**
 * Хранит выбранную цветовую схему и обои в SharedPreferences.
 * Предоставляет StateFlow для реактивного обновления темы и фона чата.
 */
object ThemePreferences {

    private const val PREFS_NAME = "app_settings"
    private const val KEY_COLOR_SCHEME = "color_scheme"
    private const val KEY_CHAT_WALLPAPER = "chat_wallpaper"

    private val _colorScheme = MutableStateFlow(AppColorScheme.MATERIAL_DARK)
    val colorScheme: StateFlow<AppColorScheme> = _colorScheme.asStateFlow()

    private val _wallpaper = MutableStateFlow(ChatWallpaper.NONE)
    val wallpaper: StateFlow<ChatWallpaper> = _wallpaper.asStateFlow()

    private lateinit var prefs: SharedPreferences

    /** Вызвать один раз в Application.onCreate() или в Activity до setContent */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedScheme = prefs.getString(KEY_COLOR_SCHEME, null)
        _colorScheme.value = savedScheme?.let { name ->
            runCatching { AppColorScheme.valueOf(name) }.getOrDefault(AppColorScheme.MATERIAL_DARK)
        } ?: AppColorScheme.MATERIAL_DARK

        val savedWp = prefs.getString(KEY_CHAT_WALLPAPER, null)
        _wallpaper.value = savedWp?.let { name ->
            runCatching { ChatWallpaper.valueOf(name) }.getOrDefault(ChatWallpaper.NONE)
        } ?: ChatWallpaper.NONE
    }

    fun setColorScheme(scheme: AppColorScheme) {
        prefs.edit().putString(KEY_COLOR_SCHEME, scheme.name).apply()
        _colorScheme.value = scheme
    }

    fun setWallpaper(wp: ChatWallpaper) {
        prefs.edit().putString(KEY_CHAT_WALLPAPER, wp.name).apply()
        _wallpaper.value = wp
    }
}

package com.secure.messenger.presentation.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Хранит выбранную цветовую схему в SharedPreferences.
 * Предоставляет StateFlow для реактивного обновления темы.
 */
object ThemePreferences {

    private const val PREFS_NAME = "app_settings"
    private const val KEY_COLOR_SCHEME = "color_scheme"

    private val _colorScheme = MutableStateFlow(AppColorScheme.CLASSIC)
    val colorScheme: StateFlow<AppColorScheme> = _colorScheme.asStateFlow()

    private lateinit var prefs: SharedPreferences

    /** Вызвать один раз в Application.onCreate() или в Activity до setContent */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_COLOR_SCHEME, null)
        _colorScheme.value = saved?.let { name ->
            runCatching { AppColorScheme.valueOf(name) }.getOrDefault(AppColorScheme.CLASSIC)
        } ?: AppColorScheme.CLASSIC
    }

    fun setColorScheme(scheme: AppColorScheme) {
        prefs.edit().putString(KEY_COLOR_SCHEME, scheme.name).apply()
        _colorScheme.value = scheme
    }
}

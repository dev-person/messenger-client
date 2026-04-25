package com.secure.messenger.utils

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Composable-обёртка над [getMissingCriticalPermissions], которая ре-проверяет
 * список **при каждом возвращении приложения в foreground** (ON_RESUME). Это
 * нужно потому что пользователь уходит в системные настройки, что-то меняет
 * и возвращается — нам нужно тут же перерендерить UI без пересборки.
 */
@Composable
fun rememberMissingCriticalPermissions(): State<List<CriticalPermission>> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(getMissingCriticalPermissions(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        state.value = getMissingCriticalPermissions(context)
    }
    return state
}

/**
 * Простое key-value хранилище для флага «больше не напоминать о разрешениях».
 * Намеренно plain-SharedPreferences: данные не секретны, их потеря после
 * переустановки приложения — приемлема.
 */
class PermissionsPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "permissions_prefs", Context.MODE_PRIVATE,
    )

    var warningDismissed: Boolean
        get() = prefs.getBoolean(KEY_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(KEY_DISMISSED, value).apply()

    companion object {
        private const val KEY_DISMISSED = "warning_dismissed"
    }
}

/** Простая обёртка для использования из Composable. */
@Composable
fun rememberPermissionsPrefs(): PermissionsPrefs {
    val context = LocalContext.current
    return remember(context) { PermissionsPrefs(context) }
}

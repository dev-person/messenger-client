package com.secure.messenger.utils

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Системные разрешения, без которых **входящие звонки не будут видны**.
 * Каждое из них нужно проверять и показывать пользователю чек-лист с
 * прямыми ссылками в системные настройки (Android даже не запросит их
 * автоматически — для full-screen intent и battery-exemption нужен
 * отдельный пользовательский переход в Settings).
 *
 * Намеренно НЕ включает чисто-функциональные разрешения вроде CAMERA /
 * RECORD_AUDIO — они запрашиваются runtime-ом по ходу действий и без них
 * приложение и так не работает (а уведомление всё равно придёт).
 */
enum class CriticalPermission(
    val title: String,
    val description: String,
) {
    /**
     * POST_NOTIFICATIONS (Android 13+). Без этого OS дропает все push-и.
     * До API 33 возвращаем true автоматически — runtime-permission там не было.
     */
    NOTIFICATIONS(
        title = "Уведомления",
        description = "Без них вы не увидите входящие сообщения и звонки",
    ),

    /**
     * USE_FULL_SCREEN_INTENT (Android 14+). Special permission, надо
     * отправить пользователя в специальный экран Settings.
     */
    FULL_SCREEN_INTENT(
        title = "Поверх блокировки экрана",
        description = "Чтобы экран входящего звонка появился, даже если телефон заблокирован",
    ),

    /**
     * Освобождение от battery optimization. Без него Android после небольшого
     * idle-периода убивает MessagingService → WS-соединение пропадает →
     * входящие звонки/сообщения не доходят пока приложение в фоне.
     */
    BATTERY_OPTIMIZATIONS(
        title = "Работа в фоне",
        description = "Без неё Android выключает приложение через несколько минут и звонки до вас не доходят",
    ),

    /**
     * Автозапуск / запуск приложений у Xiaomi/Huawei/Samsung/Vivo/OPPO/Honor —
     * сверх стандартной Doze у этих производителей собственная система убийства
     * фона. Программно проверить статус нельзя (производители не дают API),
     * поэтому пункт показывается всегда на «своём» устройстве и помечен ⚠ —
     * пользователь сам решает.
     */
    VENDOR_AUTOSTART(
        title = "Автозапуск (для производителя)",
        description = "Xiaomi/Huawei/Samsung/Vivo/OPPO без этого убивают приложение даже при разрешённой батарее",
    );

    /** Возвращает Intent, который ведёт пользователя в системные настройки этого разрешения. */
    fun systemSettingsIntent(context: Context): Intent = when (this) {
        NOTIFICATIONS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        FULL_SCREEN_INTENT ->
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        BATTERY_OPTIMIZATIONS ->
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        VENDOR_AUTOSTART ->
            VendorAutostartIntents.resolveIntent(context)
    }
}

/**
 * Статус одного разрешения. Для большинства можно точно определить
 * (granted/denied), а у [CriticalPermission.VENDOR_AUTOSTART] статус
 * Android не даёт — мы помечаем UNKNOWN, пользователь сам подтверждает
 * нажатием кнопки.
 */
enum class PermissionGrantStatus {
    GRANTED,
    DENIED,
    /** Не проверяемо программно (vendor-specific) — показываем нейтрально. */
    UNKNOWN,
    /** Не применимо к этой версии Android (например, FULL_SCREEN_INTENT на API <34). */
    NOT_APPLICABLE,
}

/** Текущий статус разрешения [permission] для данного [context]. */
fun statusOf(context: Context, permission: CriticalPermission): PermissionGrantStatus = when (permission) {
    CriticalPermission.NOTIFICATIONS -> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            PermissionGrantStatus.NOT_APPLICABLE
        } else if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
        ) PermissionGrantStatus.GRANTED else PermissionGrantStatus.DENIED
    }
    CriticalPermission.FULL_SCREEN_INTENT -> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PermissionGrantStatus.NOT_APPLICABLE
        } else {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm?.canUseFullScreenIntent() == true) PermissionGrantStatus.GRANTED
            else PermissionGrantStatus.DENIED
        }
    }
    CriticalPermission.BATTERY_OPTIMIZATIONS -> {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(context.packageName) == true)
            PermissionGrantStatus.GRANTED
        else PermissionGrantStatus.DENIED
    }
    CriticalPermission.VENDOR_AUTOSTART -> {
        if (VendorAutostartIntents.isVendorAutostartRequired())
            PermissionGrantStatus.UNKNOWN
        else
            PermissionGrantStatus.NOT_APPLICABLE
    }
}

/**
 * Возвращает список критичных разрешений, которых сейчас не хватает.
 * VENDOR_AUTOSTART сюда НЕ включается (status=UNKNOWN — мы не можем
 * проверить и не хотим спамить пользователя), он показывается отдельно
 * только в Diagnostics-экране.
 */
fun getMissingCriticalPermissions(context: Context): List<CriticalPermission> =
    CriticalPermission.values()
        .filter { it != CriticalPermission.VENDOR_AUTOSTART }
        .filter { statusOf(context, it) == PermissionGrantStatus.DENIED }

package com.secure.messenger.utils

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Intent'ы в системные настройки автозапуска / фоновой работы у разных
 * производителей Android. У них собственные системы убийства фоновых
 * процессов сверх стандартной Doze, и публичного API чтобы спросить
 * «разрешён ли автозапуск» — нет: можно только направить пользователя
 * вручную в настройки производителя.
 *
 * Список известных ComponentName собран из открытых источников (см.
 * dontkillmyapp.com и наблюдения в продакшне). Для каждого ниже:
 *  1. пытаемся открыть точный экран автозапуска / запуска приложений
 *     (вендорная activity);
 *  2. если её на устройстве нет — фолбэк на общий экран настроек
 *     приложения (через [appDetailsIntent]) — оттуда пользователь
 *     найдёт нужный пункт сам.
 */
object VendorAutostartIntents {

    /** Краткий «человеческий» лейбл бренда для отображения в UI. */
    fun manufacturerLabel(): String? {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return when {
            m.contains("xiaomi") || b.contains("redmi") || b.contains("poco") -> "Xiaomi/MIUI"
            m.contains("huawei") || b.contains("huawei") -> "Huawei"
            m.contains("honor") || b.contains("honor") -> "Honor"
            m.contains("samsung") -> "Samsung"
            m.contains("vivo") || b.contains("iqoo") -> "Vivo"
            m.contains("oppo") || b.contains("oppo") -> "OPPO"
            m.contains("realme") -> Realme
            m.contains("oneplus") -> "OnePlus"
            m.contains("asus") -> "ASUS"
            else -> null
        }
    }

    private const val Realme = "realme"

    /** true если для этого производителя нужен ручной поход в настройки автозапуска. */
    fun isVendorAutostartRequired(): Boolean = manufacturerLabel() != null

    /**
     * Возвращает Intent для перехода в настройки автозапуска производителя.
     * Если первый «глубокий» Intent не резолвится (пользователь сменил оболочку,
     * производитель убрал activity) — отдаём fallback на общий screen настроек
     * приложения, чтобы кнопка не превращалась в no-op.
     */
    fun resolveIntent(context: Context): Intent {
        val candidates = candidateIntents()
        val pm = context.packageManager
        for (intent in candidates) {
            if (intent.resolveActivity(pm) != null) {
                return intent
            }
        }
        return appDetailsIntent(context)
    }

    private fun candidateIntents(): List<Intent> {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return when {
            m.contains("xiaomi") || b.contains("redmi") || b.contains("poco") -> listOf(
                componentIntent(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            )
            m.contains("huawei") || b.contains("huawei") -> listOf(
                componentIntent(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                componentIntent(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity",
                ),
            )
            m.contains("honor") || b.contains("honor") -> listOf(
                componentIntent(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
            )
            m.contains("samsung") -> listOf(
                componentIntent(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                ),
                componentIntent(
                    "com.samsung.android.sm_cn",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                ),
            )
            m.contains("vivo") || b.contains("iqoo") -> listOf(
                componentIntent(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
                componentIntent(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                ),
            )
            m.contains("oppo") || b.contains("oppo") || m.contains(Realme) -> listOf(
                componentIntent(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
                componentIntent(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity",
                ),
                componentIntent(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity",
                ),
            )
            m.contains("oneplus") -> listOf(
                componentIntent(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                ),
            )
            m.contains("asus") -> listOf(
                componentIntent(
                    "com.asus.mobilemanager",
                    "com.asus.mobilemanager.entry.FunctionActivity",
                ),
            )
            else -> emptyList()
        }
    }

    private fun componentIntent(pkg: String, cls: String): Intent =
        Intent().apply { setClassName(pkg, cls) }

    /** Стандартный «о приложении» — Settings → Apps → Grizzly. */
    private fun appDetailsIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}

package com.secure.messenger.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Гасит экран, когда телефон поднесён к уху во время аудио-звонка.
 *
 * Двухслойная защита от того что разные устройства по-разному поддерживают
 * физическое выключение экрана из приложения:
 *
 *  1. **OS-level**: пытаемся захватить [PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK].
 *     Если устройство его поддерживает, система сама гасит дисплей физически
 *     при срабатывании датчика близости — это самый надёжный способ.
 *
 *  2. **App-level fallback**: параллельно подписываемся на сырой
 *     [Sensor.TYPE_PROXIMITY] и публикуем результат в [isNear] Flow. UI экрана
 *     звонка наблюдает этот флаг и показывает поверх всего чёрный оверлей,
 *     блокирующий тачи. Это нужно для устройств (Xiaomi/MIUI, некоторые
 *     китайские прошивки), где PROXIMITY_SCREEN_OFF_WAKE_LOCK молча
 *     игнорируется.
 *
 * Только для аудио-звонков: при видеозвонке гасить экран не нужно — пользователь
 * смотрит на собеседника, и подносить телефон к уху не должен.
 */
class ProximityScreenLock(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * true если в данный момент рядом с датчиком есть препятствие (телефон у уха).
     * UI наблюдает этот флаг чтобы показать чёрный оверлей в случае если OS
     * почему-то не выключила экран сама.
     */
    private val _isNear = MutableStateFlow(false)
    val isNear: StateFlow<Boolean> = _isNear.asStateFlow()

    /** true если в системе есть PROXIMITY_SCREEN_OFF_WAKE_LOCK. */
    val isWakeLockSupported: Boolean
        get() = powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_PROXIMITY) return
            // У большинства датчиков значение = расстояние в см. Многие
            // устройства возвращают только два значения: 0 (близко) и max (далеко).
            // Берём порог = меньше maxRange — это «близко».
            val maxRange = event.sensor.maximumRange
            val near = event.values.firstOrNull()?.let { it < maxRange } ?: false
            if (_isNear.value != near) {
                _isNear.value = near
                Timber.d("ProximityScreenLock: sensor near=$near (value=${event.values.firstOrNull()}, max=$maxRange)")
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Активирует обе подсистемы — wake lock + sensor listener. */
    @Suppress("WakelockTimeout")
    fun acquire() {
        // 1) Системный wake lock
        if (isWakeLockSupported && wakeLock?.isHeld != true) {
            runCatching {
                val wl = powerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "messenger:proximity_call",
                )
                wl.setReferenceCounted(false)
                wl.acquire()
                wakeLock = wl
                Timber.d("ProximityScreenLock: wake lock acquired")
            }.onFailure { Timber.e(it, "ProximityScreenLock: wake lock acquire failed") }
        } else if (!isWakeLockSupported) {
            Timber.w("ProximityScreenLock: PROXIMITY_SCREEN_OFF_WAKE_LOCK не поддерживается, fallback на manual sensor")
        }

        // 2) Manual sensor listener (всегда регистрируем — он только источник
        // данных для UI-оверлея, ничего не «гасит» сам)
        if (proximitySensor != null) {
            sensorManager.registerListener(
                sensorListener,
                proximitySensor,
                SensorManager.SENSOR_DELAY_UI,
            )
            Timber.d("ProximityScreenLock: sensor listener registered")
        } else {
            Timber.w("ProximityScreenLock: устройство не имеет датчика приближения")
        }
    }

    /** Освобождает wake lock + отписывает слушателя датчика. */
    fun release() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            wakeLock = null
            Timber.d("ProximityScreenLock: wake lock released")
        }.onFailure { Timber.e(it, "ProximityScreenLock: wake lock release failed") }

        runCatching {
            sensorManager.unregisterListener(sensorListener)
            _isNear.value = false
            Timber.d("ProximityScreenLock: sensor listener unregistered")
        }.onFailure { Timber.e(it, "ProximityScreenLock: sensor unregister failed") }
    }
}

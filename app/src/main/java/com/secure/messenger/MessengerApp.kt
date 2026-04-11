package com.secure.messenger

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.secure.messenger.service.MessagingService
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class MessengerApp : Application(), ImageLoaderFactory {

    companion object {
        /**
         * Глобальный флаг: приложение на переднем плане (Activity видима).
         * Устанавливается из MainActivity.onStart() / onStop().
         * Используется в FcmService и MessagingService для подавления уведомлений
         * когда пользователь уже смотрит на экран.
         */
        @Volatile
        var isInForeground: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        createNotificationChannels()
    }

    /**
     * Глобальный Coil ImageLoader с поддержкой анимированных GIF/WebP.
     * Без явного декодера AsyncImage показывает только первый кадр гифа —
     * этим методом включаем встроенный системный декодер (Android 9+) или
     * fallback GifDecoder для более старых версий.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .build()

    /**
     * Создаём каналы уведомлений при старте приложения — до любого сервиса.
     * FcmService может показывать уведомления даже когда приложение убито,
     * поэтому каналы должны существовать независимо от MessagingService.
     * Вызов идемпотентен — повторное создание существующего канала безопасно.
     */
    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // Канал входящих звонков — максимальный приоритет, рингтон
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        NotificationChannel(
            MessagingService.CHANNEL_CALL,
            "Входящие звонки",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Уведомления о входящих аудио- и видеозвонках"
            setSound(
                ringtoneUri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 300, 500, 300, 500)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }.also { nm.createNotificationChannel(it) }

        // Канал новых сообщений — высокий приоритет, вибрация
        NotificationChannel(
            MessagingService.CHANNEL_MESSAGE,
            "Сообщения",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Уведомления о новых сообщениях"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250)
        }.also { nm.createNotificationChannel(it) }
    }
}

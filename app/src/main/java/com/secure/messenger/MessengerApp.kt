package com.secure.messenger

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.secure.messenger.data.remote.websocket.SignalingClient
import com.secure.messenger.di.AuthTokenProvider
import com.secure.messenger.service.MessagingService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

@HiltAndroidApp
class MessengerApp : Application(), ImageLoaderFactory {

    companion object {
        /**
         * Глобальный флаг: приложение на переднем плане (есть хотя бы одна
         * видимая Activity). Обновляется через ProcessLifecycleOwner — это
         * надёжнее чем Activity.onStart/onStop, потому что охватывает весь
         * процесс и гарантированно срабатывает на смене foreground/background
         * (включая случаи когда Samsung-lifecycle ведёт себя странно).
         */
        @Volatile
        var isInForeground: Boolean = false
    }

    /** Hilt-EntryPoint для доступа к Singleton-зависимостям из Application,
     *  где конструкторный @Inject не поддерживается. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppEntryPoint {
        fun signalingClient(): SignalingClient
        fun authTokenProvider(): AuthTokenProvider
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        createNotificationChannels()
        registerProcessLifecycleObserver()
    }

    /**
     * Подписываемся на жизненный цикл процесса (а не отдельной Activity).
     * Срабатывает когда первая Activity процесса попадает в foreground
     * (onStart) и когда последняя уходит в background (onStop, ~700мс
     * debounce от Lifecycle).
     *
     * Раньше presence слался из MainActivity.onStart/onStop, и на Samsung
     * One UI бывало что Activity «зависала» в onStarted при выключении
     * экрана — сервер видел юзера online хотя телефон лежал в кармане.
     * ProcessLifecycleOwner это гарантированно ловит на уровне процесса.
     */
    private fun registerProcessLifecycleObserver() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isInForeground = true
                val entryPoint = EntryPointAccessors.fromApplication(
                    this@MessengerApp, AppEntryPoint::class.java,
                )
                if (entryPoint.authTokenProvider().hasToken()) {
                    val ws = entryPoint.signalingClient()
                    ws.isAppForeground = true
                    ws.sendPresence(true)
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                isInForeground = false
                val entryPoint = EntryPointAccessors.fromApplication(
                    this@MessengerApp, AppEntryPoint::class.java,
                )
                if (entryPoint.authTokenProvider().hasToken()) {
                    val ws = entryPoint.signalingClient()
                    ws.isAppForeground = false
                    ws.sendPresence(false)
                }
            }
        })
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
        // Ограничиваем кеш декодированных bitmap-ов 20% свободной памяти.
        // При дефолтных 40% в чате с большим числом картинок Coil держал
        // одновременно десятки полноразмерных bitmap-ов и приложение падало по OOM.
        .memoryCache {
            coil.memory.MemoryCache.Builder(this)
                .maxSizePercent(0.20)
                .build()
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

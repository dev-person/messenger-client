package com.secure.messenger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.secure.messenger.BuildConfig
import com.secure.messenger.data.remote.websocket.SignalingClient
import com.secure.messenger.data.remote.websocket.SignalingEvent
import com.secure.messenger.di.AuthTokenProvider
import com.secure.messenger.presentation.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground-сервис, поддерживающий WebSocket-соединение для доставки сообщений
 * и уведомлений о входящих звонках когда приложение свёрнуто или экран заблокирован.
 *
 * Должен запускаться через startForegroundService() из MainActivity.
 */
@AndroidEntryPoint
class MessagingService : Service() {

    @Inject lateinit var signalingClient: SignalingClient
    @Inject lateinit var tokenProvider: AuthTokenProvider
    @Inject lateinit var incomingMessageHandler: IncomingMessageHandler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nm get() = getSystemService(NotificationManager::class.java)

    // Не даёт WiFi засыпать → поддерживает WebSocket при выключенном экране.
    // Без этого радиомодуль засыпает ~15 мин после выключения экрана на большинстве устройств,
    // WebSocket тихо умирает, входящие звонки и сообщения перестают приходить.
    private var wifiLock: WifiManager.WifiLock? = null

    // Защита от запуска нескольких параллельных collect-корутин
    private var observingEvents = false
    // Текущая задержка перед переподключением (экспоненциальный backoff)
    private var reconnectDelayMs = 1_000L

    companion object {
        // Notification channels
        const val CHANNEL_BG      = "bg_service_channel"  // silent persistent (foreground)
        const val CHANNEL_CALL    = "call_channel"         // incoming call — max priority
        const val CHANNEL_MESSAGE = "message_channel"      // new message

        // Notification IDs
        private const val NOTIF_ID_FG   = 1000  // persistent foreground notification
        const val NOTIF_ID_CALL = 1001  // incoming call (single, replaced each time)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Обязателен вызов startForeground() в течение 5 сек после onCreate()
        startForeground(NOTIF_ID_FG, buildForegroundNotification())
        acquireWifiLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = tokenProvider.token ?: return START_NOT_STICKY
        signalingClient.connect(BuildConfig.WS_BASE_URL, token)
        observeEvents()
        return START_STICKY   // Android перезапустит сервис если его убьют
    }

    // ── WiFi lock ─────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "messenger:wifi_lock")
        wifiLock?.acquire()
    }

    // ── Обработка событий ─────────────────────────────────────────────────────

    private fun observeEvents() {
        if (observingEvents) return
        observingEvents = true
        scope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.Connected -> {
                        Timber.d("WS connected")
                        reconnectDelayMs = 1_000L   // сбрасываем backoff при успешном подключении
                    }
                    is SignalingEvent.IncomingCall    -> scope.launch { showCallNotification(event) }
                    is SignalingEvent.CallEnded       -> {
                        // Убираем уведомление о входящем звонке
                        nm.cancel(NOTIF_ID_CALL)
                    }
                    is SignalingEvent.NewMessage      -> scope.launch { handleMessage(event.json) }
                    is SignalingEvent.MessageDeleted  -> scope.launch {
                        incomingMessageHandler.handleDelete(event.messageId)
                    }
                    is SignalingEvent.MessageEdited   -> scope.launch {
                        incomingMessageHandler.handleEdit(event.messageId, event.chatId, event.encryptedContent)
                    }
                    is SignalingEvent.MessagesRead    -> scope.launch {
                        incomingMessageHandler.handleMessagesRead(event.chatId, event.readerId)
                    }
                    is SignalingEvent.Disconnected -> {
                        Timber.w("WS disconnected — reconnect in ${reconnectDelayMs}ms")
                        scheduleReconnect()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun scheduleReconnect() {
        scope.launch {
            kotlinx.coroutines.delay(reconnectDelayMs)
            reconnectDelayMs = minOf(reconnectDelayMs * 2, 30_000L)   // max 30 сек
            val token = tokenProvider.token
            if (token == null) {
                // Пользователь разлогинился — останавливаем сервис
                stopSelf()
                return@launch
            }
            signalingClient.connect(BuildConfig.WS_BASE_URL, token)
        }
    }

    private suspend fun handleMessage(json: String) {
        val info = incomingMessageHandler.handle(json) ?: return
        showMessageNotification(senderName = info.first, content = info.second)
    }

    // ── Уведомления ───────────────────────────────────────────────────────────

    /** Тихое постоянное уведомление, требуемое для foreground-сервиса. */
    private fun buildForegroundNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_BG)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Secure Messenger")
            .setContentText("Ожидание сообщений и звонков")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    /**
     * Полноэкранное уведомление о звонке — будит экран при заблокированном устройстве.
     *
     * Двухэтапное пробуждение экрана:
     *  1. WakeLock с ACQUIRE_CAUSES_WAKEUP физически включает экран.
     *  2. setFullScreenIntent запускает MainActivity с setShowWhenLocked/setTurnScreenOn,
     *     и UI звонка появляется поверх экрана блокировки.
     */
    @Suppress("DEPRECATION")
    private suspend fun showCallNotification(event: SignalingEvent.IncomingCall) {
        // Определяем имя звонящего до создания уведомления
        val callerName = incomingMessageHandler.getDisplayName(event.callerId)
        // Включаем экран — необходимо для отображения fullscreen intent на экране блокировки.
        // FULL_WAKE_LOCK устарел, но остаётся единственным надёжным кросс-версионным способом
        // физически разбудить экран из фонового сервиса.
        val pm = getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "messenger:incoming_call_wake",
        )
        wl.acquire(45_000L) // auto-release matches notification timeout

        val openIntent = PendingIntent.getActivity(
            this, NOTIF_ID_CALL,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        val builder = NotificationCompat.Builder(this, CHANNEL_CALL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(if (event.isVideo) "Входящий видеозвонок" else "Входящий звонок")
            .setContentText(callerName)
            .setContentIntent(openIntent)
            .setFullScreenIntent(openIntent, true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVibrate(longArrayOf(0, 500, 300, 500, 300, 500))
            .setSound(ringtoneUri)
            .setAutoCancel(true)
            .setTimeoutAfter(45_000L)

        nm.notify(NOTIF_ID_CALL, builder.build())
    }

    /** Всплывающее уведомление о новом сообщении с вибрацией. */
    private fun showMessageNotification(senderName: String, content: String) {
        val openIntent = PendingIntent.getActivity(
            this, senderName.hashCode(),
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGE)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(senderName)
            .setContentText(content)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVibrate(longArrayOf(0, 250))
            .build()

        nm.notify(senderName.hashCode(), notification)
    }

    // ── Создание каналов уведомлений (идемпотентно) ────────────────────────────

    private fun createNotificationChannels() {
        // 1. Тихий канал для постоянного foreground-уведомления
        NotificationChannel(
            CHANNEL_BG,
            "Фоновое соединение",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Поддерживает соединение для сообщений и звонков"
            setSound(null, null)
            enableVibration(false)
        }.also { nm.createNotificationChannel(it) }

        // 2. Канал максимального приоритета для входящих звонков (рингтон + вибрация)
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        NotificationChannel(
            CHANNEL_CALL,
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

        // 3. Канал высокого приоритета для сообщений (только вибрация)
        NotificationChannel(
            CHANNEL_MESSAGE,
            "Сообщения",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Уведомления о новых сообщениях"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250)
        }.also { nm.createNotificationChannel(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wifiLock?.release()
        signalingClient.disconnect()
        scope.cancel()
        super.onDestroy()
    }
}

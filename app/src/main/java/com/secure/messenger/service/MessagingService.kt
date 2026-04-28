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
import com.secure.messenger.R
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
    @Inject lateinit var localKeyStore: com.secure.messenger.utils.LocalKeyStore
    @Inject lateinit var db: com.secure.messenger.data.local.database.AppDatabase

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
        const val ACTION_FORCE_LOGOUT = "com.secure.messenger.FORCE_LOGOUT"
        const val CHANNEL_CALL    = "call_channel"    // входящий звонок — максимальный приоритет
        const val CHANNEL_MESSAGE = "message_channel" // новое сообщение
        const val NOTIF_ID_CALL       = 1001          // уведомление о 1-1 звонке
        const val NOTIF_ID_GROUP_CALL = 1002          // уведомление о групповом звонке

        /** Extra-ключи для Intent уведомления группового звонка. */
        const val EXTRA_GROUP_CALL_CHAT_ID = "group_call_chat_id"
        const val EXTRA_GROUP_CALL_ID      = "group_call_id"
        const val EXTRA_GROUP_CALL_VIDEO   = "group_call_video"
    }

    override fun onCreate() {
        super.onCreate()
        // Каналы создаются в MessengerApp.onCreate() — здесь не нужно
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
                        // Отправляем presence сразу после подключения — сервер не ставит online автоматически
                        signalingClient.sendPresence(signalingClient.isAppForeground)
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
                    is SignalingEvent.UserStatus -> scope.launch {
                        incomingMessageHandler.handleUserStatus(event.userId, event.isOnline)
                    }
                    is SignalingEvent.UserUpdated -> scope.launch {
                        incomingMessageHandler.handleUserUpdated(event.userId, event.payload)
                    }
                    is SignalingEvent.ForceLogout -> {
                        val mySessionId = tokenProvider.sessionId
                        if (mySessionId != null && mySessionId == event.excludeSessionId) {
                            Timber.d("Force logout excluded my session $mySessionId — ignoring")
                        } else {
                            Timber.w("Force logout: ${event.reason}")
                            handleForceLogout()
                        }
                    }
                    is SignalingEvent.Disconnected -> {
                        Timber.w("WS disconnected — reconnect in ${reconnectDelayMs}ms")
                        scheduleReconnect()
                    }
                    is SignalingEvent.GroupInfoUpdated -> scope.launch {
                        incomingMessageHandler.handleGroupInfoUpdated(event.chatId, event.title, event.avatarUrl)
                    }
                    is SignalingEvent.GroupMemberAdded -> scope.launch {
                        incomingMessageHandler.handleGroupMemberAdded(event.chatId, event.userId, event.epoch)
                    }
                    is SignalingEvent.GroupMemberRemoved -> scope.launch {
                        incomingMessageHandler.handleGroupMemberRemoved(event.chatId, event.userId, event.epoch)
                    }
                    is SignalingEvent.GroupRoleChanged -> scope.launch {
                        incomingMessageHandler.handleGroupRoleChanged(event.chatId, event.userId, event.role)
                    }
                    is SignalingEvent.GroupDeleted -> scope.launch {
                        incomingMessageHandler.handleGroupDeleted(event.chatId)
                    }
                    is SignalingEvent.GroupSenderKeyReady -> scope.launch {
                        incomingMessageHandler.handleGroupSenderKeyReady(event.chatId, event.ownerId, event.epoch)
                    }
                    is SignalingEvent.GroupCallInvite -> scope.launch {
                        showGroupCallNotification(event)
                    }
                    is SignalingEvent.GroupCallEnded -> {
                        // Убираем notification если она ещё висит
                        nm.cancel(NOTIF_ID_GROUP_CALL)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun handleForceLogout() {
        tokenProvider.clearToken()
        localKeyStore.clear()
        signalingClient.disconnect()
        // Очищаем БД — чтобы расшифрованные сообщения не остались доступны
        scope.launch { db.clearAllTables() }
        sendBroadcast(Intent(ACTION_FORCE_LOGOUT).setPackage(packageName))
        stopSelf()
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
        // Не показываем уведомление когда приложение на переднем плане —
        // пользователь уже видит сообщения в UI
        if (com.secure.messenger.MessengerApp.isInForeground) return
        // Приглушённый чат — пуш не показываем (FcmService применяет ту же
        // логику для FCM-пути; тут симметрично для WS-пути).
        if (com.secure.messenger.data.local.MutedChatsPrefs.isMuted(this, info.third)) return
        showMessageNotification(senderName = info.first, content = info.second, chatId = info.third)
    }

    // ── Уведомления ───────────────────────────────────────────────────────────

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
        wl.acquire(5 * 60_000L) // auto-release matches notification timeout (5 min)

        // Кладём данные звонка в extras, чтобы MainActivity при тапе на
        // notification могла открыть CallScreen напрямую (без зависимости от
        // того, успел ли CallRepository подхватить WS-event incoming_call).
        val callIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(FcmService.EXTRA_INCOMING_CALL_ID, event.callId)
            putExtra(FcmService.EXTRA_INCOMING_CALLER_ID, event.callerId)
            putExtra(FcmService.EXTRA_INCOMING_CALL_VIDEO, event.isVideo)
            putExtra(FcmService.EXTRA_INCOMING_CALLER_NAME, callerName)
        }
        val openIntent = PendingIntent.getActivity(
            this, NOTIF_ID_CALL, callIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        val builder = NotificationCompat.Builder(this, CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (event.isVideo) "Входящий видеозвонок" else "Входящий звонок")
            .setContentText(callerName)
            .setContentIntent(openIntent)
            .setFullScreenIntent(openIntent, true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVibrate(longArrayOf(0, 500, 300, 500, 300, 500))
            .setSound(ringtoneUri)
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)

        nm.notify(NOTIF_ID_CALL, builder.build())
    }

    /**
     * Уведомление о групповом звонке. Тап → MainActivity с extras → она
     * пушит на GroupCallScreen с existingCallId. Показываем full-screen
     * intent (как у 1-1 звонка), чтобы экран загорелся даже из заблокированного
     * состояния.
     *
     * [event.invitedUserIds] управляет рингтоном: если меня нет в списке —
     * звонок инициировался не для меня (инициатор выбрал других участников
     * группы), full-screen / звук пропускаем. Плашку «Идёт звонок» в чате
     * пользователь всё равно увидит — она строится из active-call поллинга,
     * а не из этой нотификации.
     */
    @Suppress("DEPRECATION")
    private suspend fun showGroupCallNotification(event: SignalingEvent.GroupCallInvite) {
        val myId = incomingMessageHandler.currentUserIdSync()
        // Если инициатор явно перечислил приглашённых и меня там нет — не звоним.
        // Пустой список = «всем» (бэкап для старых клиентов или старт без выбора).
        if (event.invitedUserIds.isNotEmpty() && myId != null && myId !in event.invitedUserIds) {
            return
        }

        val isVideo = event.type == "VIDEO"

        // Если приложение уже открыто — не показываем notification со
        // звонком: она скрывается под текущим экраном и выглядит как обычный
        // пуш. Вместо этого сразу запускаем MainActivity с extras — UI
        // навигирует на GroupCallScreen и показывает pre-join overlay
        // (большие кнопки «Принять/Отклонить»). Background-кейс остаётся
        // как был — full-screen notification + рингтон.
        if (com.secure.messenger.MessengerApp.isInForeground) {
            val launchIntent = Intent(
                this, com.secure.messenger.presentation.ui.main.MainActivity::class.java,
            ).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_GROUP_CALL_CHAT_ID, event.chatId)
                putExtra(EXTRA_GROUP_CALL_ID, event.callId)
                putExtra(EXTRA_GROUP_CALL_VIDEO, isVideo)
            }
            startActivity(launchIntent)
            return
        }

        val starterName = incomingMessageHandler.getDisplayName(event.startedBy)

        // Wake lock — как для 1-1 звонка, чтобы экран загорелся
        val pm = getSystemService(android.os.PowerManager::class.java)
        val wl = pm.newWakeLock(
            android.os.PowerManager.FULL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "messenger:incoming_group_call_wake",
        )
        wl.acquire(5 * 60_000L)

        val openIntent = android.app.PendingIntent.getActivity(
            this, NOTIF_ID_GROUP_CALL,
            Intent(this, com.secure.messenger.presentation.ui.main.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_GROUP_CALL_CHAT_ID, event.chatId)
                putExtra(EXTRA_GROUP_CALL_ID, event.callId)
                putExtra(EXTRA_GROUP_CALL_VIDEO, isVideo)
            },
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val ringtoneUri = android.media.RingtoneManager.getDefaultUri(
            android.media.RingtoneManager.TYPE_RINGTONE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (isVideo) "Групповой видеозвонок" else "Групповой звонок")
            .setContentText("$starterName приглашает в групповой звонок")
            .setContentIntent(openIntent)
            .setFullScreenIntent(openIntent, true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVibrate(longArrayOf(0, 500, 300, 500, 300, 500))
            .setSound(ringtoneUri)
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)
            .build()

        nm.notify(NOTIF_ID_GROUP_CALL, notification)
    }

    /** Всплывающее уведомление о новом сообщении с вибрацией. */
    private fun showMessageNotification(senderName: String, content: String, chatId: String) {
        // Уникальный requestCode и SINGLE_TOP — иначе разные чаты делят один
        // PendingIntent и тап открывает не тот чат.
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(FcmService.EXTRA_OPEN_CHAT_ID, chatId)
        }
        val openIntent = PendingIntent.getActivity(
            this, chatId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Public version shown on lock screen — hides actual message content
        val publicNotification = NotificationCompat.Builder(this, CHANNEL_MESSAGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Новое сообщение")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(senderName)
            .setContentText(content)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVibrate(longArrayOf(0, 250))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .build()

        // ID на чат, а не на отправителя — иначе два разных чата от одного
        // и того же юзера перезаписывали бы друг друга, а в группе все
        // сообщения летят под одним «отправителем» = группой.
        nm.notify(chatId.hashCode(), notification)
    }

    // ── Создание каналов уведомлений (идемпотентно) ────────────────────────────

    private fun createNotificationChannels() {
        // 1. Канал максимального приоритета для входящих звонков (рингтон + вибрация)
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

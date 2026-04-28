package com.secure.messenger.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.secure.messenger.R
import com.secure.messenger.presentation.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Firebase Cloud Messaging сервис.
 *
 * НЕ использует Hilt — при старте из убитого приложения DI может не инициализироваться.
 * Для показа уведомлений DI не нужен. Для регистрации токена используем OkHttp напрямую.
 *
 * Типы входящих push (поле "type" в data):
 *   "new_message"   — новое сообщение
 *   "incoming_call" — входящий звонок
 *   "missed_call"   — пропущенный звонок (звонящий завершил, абонент не успел ответить)
 */
class FcmService : FirebaseMessagingService() {

    companion object {
        /** Extra-ключ Intent'а уведомления — chatId, который надо открыть после тапа. */
        const val EXTRA_OPEN_CHAT_ID = "open_chat_id"

        /** Extras для тапа по FCM-уведомлению о входящем звонке. */
        const val EXTRA_INCOMING_CALL_ID    = "incoming_call_id"
        const val EXTRA_INCOMING_CALLER_ID  = "incoming_caller_id"
        const val EXTRA_INCOMING_CALL_VIDEO = "incoming_call_video"
        const val EXTRA_INCOMING_CALLER_NAME = "incoming_caller_name"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // EncryptedSharedPreferences для чтения auth-токена без DI
    // Должны совпадать с AuthTokenProvider: PREFS_NAME="encrypted_auth", KEY_TOKEN="auth_token"
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(this, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            this,
            "encrypted_auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ── Обновление токена ─────────────────────────────────────────────────────

    /**
     * Вызывается когда FCM выдаёт новый device token (первый запуск или ротация).
     * Регистрируем на сервере через OkHttp напрямую — без Retrofit и Hilt.
     */
    override fun onNewToken(token: String) {
        val authToken = prefs.getString("auth_token", null) ?: return
        scope.launch {
            runCatching {
                val client = OkHttpClient()
                val body = """{"token":"$token"}"""
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(com.secure.messenger.BuildConfig.API_BASE_URL + "users/me/fcm-token")
                    .put(body)
                    .addHeader("Authorization", "Bearer $authToken")
                    .build()
                client.newCall(request).execute().close()
            }
        }
    }

    // ── Входящие push ─────────────────────────────────────────────────────────

    /**
     * Вызывается когда приходит data-only push (без notification-блока).
     * Работает даже когда приложение убито.
     *
     * Если приложение на переднем плане — уведомления не показываем:
     * сообщения уже видны в UI, а дублирование раздражает пользователя.
     * Звонки показываем всегда — пользователь может быть на другом экране.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        when (data["type"]) {
            "new_message" -> {
                if (!com.secure.messenger.MessengerApp.isInForeground) {
                    // Чат приглушён — пуш получили, но шторку не показываем.
                    val chatId = data["chatId"]
                    if (chatId != null &&
                        com.secure.messenger.data.local.MutedChatsPrefs.isMuted(this, chatId)) {
                        return
                    }
                    showMessageNotification(data)
                }
            }
            "incoming_call" -> {
                // Дополнительная защита от «фантомных» звонков: даже если
                // Google FCM проигнорировал TTL=60s и доставил старый push,
                // мы сами отбрасываем всё что старше 60 секунд по timestamp
                // в payload (sentAt). Фантомы случаются когда юзер был долго
                // оффлайн и Google копил пуши.
                if (!isFreshPush(data, maxAgeSeconds = 60)) {
                    return
                }
                showCallNotification(data)
            }
            "missed_call" -> {
                if (!com.secure.messenger.MessengerApp.isInForeground) {
                    // Пропущенные звонки актуальны до часа — старше уже мусор.
                    if (!isFreshPush(data, maxAgeSeconds = 3600)) {
                        return
                    }
                    showMissedCallNotification(data)
                }
            }
        }
    }

    /**
     * Проверяет, что push не «протух»: разница между сейчас и `sentAt`
     * в payload меньше [maxAgeSeconds]. Если поля sentAt нет — считаем
     * push свежим (для совместимости со старыми клиентами/серверами).
     */
    private fun isFreshPush(data: Map<String, String>, maxAgeSeconds: Long): Boolean {
        val sentAt = data["sentAt"]?.toLongOrNull() ?: return true
        val ageSeconds = (System.currentTimeMillis() / 1000L) - sentAt
        return ageSeconds in 0..maxAgeSeconds
    }

    // ── Показ уведомлений ─────────────────────────────────────────────────────

    private fun showMessageNotification(data: Map<String, String>) {
        val senderName = data["senderName"]?.takeIf { it.isNotBlank() } ?: "Grizzly Messenger"
        val chatId = data["chatId"]
        // Уникальный requestCode на каждый чат — иначе FLAG_UPDATE_CURRENT ломает
        // extras: уведомления разных чатов делят один и тот же PendingIntent и
        // открывали бы один и тот же чат.
        val requestCode = chatId?.hashCode() ?: 0
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (chatId != null) putExtra(EXTRA_OPEN_CHAT_ID, chatId)
        }
        val openIntent = PendingIntent.getActivity(
            this, requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Публичная версия для экрана блокировки — скрываем имя отправителя
        val publicNotif = NotificationCompat.Builder(this, MessagingService.CHANNEL_MESSAGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Новое сообщение")
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notification = NotificationCompat.Builder(this, MessagingService.CHANNEL_MESSAGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(senderName)
            .setContentText("Новое сообщение")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 250))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotif)
            .build()

        NotificationManagerCompat.from(this)
            .notify(data["chatId"].hashCode(), notification)
    }

    /**
     * Уведомление о пропущенном звонке. В отличие от incoming_call,
     * не использует full-screen intent и рингтон — это уже свершившееся событие,
     * звонящий завершил вызов, отображаем как обычное уведомление.
     */
    private fun showMissedCallNotification(data: Map<String, String>) {
        val callerName = data["callerName"]?.takeIf { it.isNotBlank() } ?: "Grizzly Messenger"
        val isVideo = data["isVideo"] == "true"
        val title = if (isVideo) "Пропущенный видеозвонок" else "Пропущенный звонок"
        val callId = data["callId"] ?: ""

        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, MessagingService.CHANNEL_MESSAGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(callerName)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setVibrate(longArrayOf(0, 250))
            .build()

        // Уникальный ID на каждый пропущенный звонок — чтобы не перезаписывать предыдущий
        NotificationManagerCompat.from(this)
            .notify("missed_call_$callId".hashCode(), notification)
    }

    private fun showCallNotification(data: Map<String, String>) {
        val callerName = data["callerName"]?.takeIf { it.isNotBlank() } ?: "Grizzly Messenger"
        val isVideo = data["isVideo"] == "true"
        val callId = data["callId"].orEmpty()
        val callerId = data["callerId"].orEmpty()
        val title = if (isVideo) "Входящий видеозвонок" else "Входящий звонок"

        // ВАЖНО: кладём данные звонка в extras. Без них тап по уведомлению
        // открывает MainActivity «голым» — экран звонка не появляется
        // (а WS-event incoming_call мог не дойти если процесс был убит,
        // FCM-fallback это и должен покрывать). MainActivity по этим
        // extras сам поднимет CallScreen в RINGING-состоянии.
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_INCOMING_CALL_ID, callId)
            putExtra(EXTRA_INCOMING_CALLER_ID, callerId)
            putExtra(EXTRA_INCOMING_CALL_VIDEO, isVideo)
            putExtra(EXTRA_INCOMING_CALLER_NAME, callerName)
        }
        val openIntent = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, MessagingService.CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(callerName)
            .setContentIntent(openIntent)
            .setFullScreenIntent(openIntent, true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)
            .build()

        NotificationManagerCompat.from(this)
            .notify(MessagingService.NOTIF_ID_CALL, notification)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

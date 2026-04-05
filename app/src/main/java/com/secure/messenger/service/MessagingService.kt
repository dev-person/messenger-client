package com.secure.messenger.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
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
 * Background service that maintains the WebSocket connection for real-time
 * message delivery and incoming call notifications when the app is in the background.
 */
@AndroidEntryPoint
class MessagingService : Service() {

    @Inject lateinit var signalingClient: SignalingClient
    @Inject lateinit var tokenProvider: AuthTokenProvider
    @Inject lateinit var incomingMessageHandler: IncomingMessageHandler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelId = "messages_channel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = tokenProvider.token ?: return START_NOT_STICKY
        signalingClient.connect(BuildConfig.WS_BASE_URL, token)
        observeEvents()
        return START_STICKY
    }

    private fun observeEvents() {
        scope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.IncomingCall -> showIncomingCallNotification(event)
                    is SignalingEvent.NewMessage -> scope.launch { incomingMessageHandler.handle(event.json) }
                    is SignalingEvent.Disconnected -> Timber.w("WebSocket disconnected")
                    else -> Unit
                }
            }
        }
    }

    private fun showIncomingCallNotification(event: SignalingEvent.IncomingCall) {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Incoming ${if (event.isVideo) "video" else "audio"} call")
            .setContentText("From ${event.callerId}")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(event.callId.hashCode(), notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "Messages & calls",
            NotificationManager.IMPORTANCE_HIGH,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        signalingClient.disconnect()
        scope.cancel()
        super.onDestroy()
    }
}

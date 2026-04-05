package com.secure.messenger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.secure.messenger.data.remote.webrtc.WebRtcCallState
import com.secure.messenger.data.remote.webrtc.WebRtcManager
import com.secure.messenger.presentation.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the WebRTC call alive when the app is in the background.
 * Android requires a foreground service with camera/microphone foreground type for calls.
 */
@AndroidEntryPoint
class CallService : Service() {

    @Inject lateinit var webRtcManager: WebRtcManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val channelId = "call_channel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown"
                val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
                startForeground(NOTIFICATION_ID, buildCallNotification(callerName, isVideo))
                observeCallState()
            }
            ACTION_HANG_UP -> {
                webRtcManager.endCall()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun observeCallState() {
        scope.launch {
            webRtcManager.callState.collect { state ->
                if (state == WebRtcCallState.ENDED || state == WebRtcCallState.IDLE) {
                    stopSelf()
                }
            }
        }
    }

    private fun buildCallNotification(callerName: String, isVideo: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val hangUpIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CallService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(if (isVideo) "Video call" else "Audio call")
            .setContentText("Call with $callerName")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hang up", hangUpIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "Active calls",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown during active audio/video calls"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.secure.messenger.action.START_CALL"
        const val ACTION_HANG_UP = "com.secure.messenger.action.HANG_UP"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_IS_VIDEO = "is_video"
        private const val NOTIFICATION_ID = 1001
    }
}

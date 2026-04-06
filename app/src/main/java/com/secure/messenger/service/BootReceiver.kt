package com.secure.messenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.secure.messenger.di.AuthTokenProvider
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Restarts MessagingService after the device boots or the app is updated.
 * Without this, the WebSocket connection (and therefore push notifications)
 * would not resume until the user manually opens the app.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var tokenProvider: AuthTokenProvider

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // Only start if the user is logged in
        if (!tokenProvider.hasToken()) return

        context.startForegroundService(Intent(context, MessagingService::class.java))
    }
}

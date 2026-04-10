package com.secure.messenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Слушает входящие SMS и извлекает OTP-код из сообщений от Grizzly.
 * Публикует найденный код через LocalBroadcast, чтобы экран авторизации мог его подставить.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        for (sms in messages) {
            val body = sms.messageBody ?: continue
            val code = extractOtpCode(body) ?: continue

            // Рассылаем код всем слушателям внутри приложения
            val broadcast = Intent(ACTION_OTP_RECEIVED).apply {
                putExtra(EXTRA_OTP_CODE, code)
                setPackage(context.packageName)
            }
            context.sendBroadcast(broadcast)
            return
        }
    }

    companion object {
        const val ACTION_OTP_RECEIVED = "com.secure.messenger.OTP_RECEIVED"
        const val EXTRA_OTP_CODE = "otp_code"

        /** Ищет 6-значный код в тексте SMS от Grizzly. */
        fun extractOtpCode(body: String): String? {
            // Пример текста: "Grizzly: ваш код подтверждения 123456"
            if (!body.contains("Grizzly", ignoreCase = true)) return null
            return Regex("\\b(\\d{6})\\b").find(body)?.value
        }
    }
}

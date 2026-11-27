package com.halo.expressassistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.halo.expressassistant.PackageAutoImport

class PackageSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return
        val content = messages.joinToString("") { it.messageBody.orEmpty() }
        val sentAt = messages.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val pending = goAsync()
        try {
            val operation = PackageAutoImport.receiveSms(context, content, sentAt)
            if (operation == null) pending.finish()
            else operation.result.addListener({ pending.finish() }, java.util.concurrent.Executor { it.run() })
        } catch (_: Exception) {
            pending.finish()
        }
    }
}

package com.halo.expressassistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.halo.expressassistant.PackageAutoImport

class PackageSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val content = Telephony.Sms.Intents.getMessagesFromIntent(intent).joinToString("") { it.messageBody.orEmpty() }
        val pending = goAsync()
        try {
            val operation = PackageAutoImport.receiveSms(context, content)
            if (operation == null) pending.finish()
            else operation.result.addListener({ pending.finish() }, java.util.concurrent.Executor { it.run() })
        } catch (_: Exception) {
            pending.finish()
        }
    }
}

package com.johnc4rl0.smsforwarder.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Exported SMS_RECEIVED receiver protected by android.permission.BROADCAST_SMS.
 *
 * goAsync(): validate action → parse multipart → engine → enqueue → expedited WorkManager.
 * Never logs bodies, PDUs, senders, or full numbers.
 */
class SmsReceivedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            Log.d(TAG, "ignore unexpected action")
            return
        }

        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                TelephonyEntryPoints.inboundSmsProcessor(appContext).process(appContext, intent)
            } catch (e: Exception) {
                Log.e(TAG, "process failed")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SmsReceived"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

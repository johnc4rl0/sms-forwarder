package com.johnc4rl0.smsforwarder.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Non-exported receiver for per-segment SMS sent-result PendingIntents.
 * Aggregates via [SendResultAggregator] → [com.johnc4rl0.smsforwarder.domain.ForwardJobRepository.recordPartResult].
 */
class SendResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        // PendingIntents set ACTION_SMS_SENT; ignore unrelated broadcasts.
        val action = intent.action
        if (action != null && action != ACTION_SMS_SENT) {
            Log.d(TAG, "ignore unexpected action")
            return
        }

        val broadcastResultCode = resultCode
        val pending = goAsync()
        val appContext = context.applicationContext
        val intentCopy = Intent(intent)
        scope.launch {
            try {
                TelephonyEntryPoints.sendResultAggregator(appContext)
                    .handle(appContext, intentCopy, broadcastResultCode)
            } catch (e: Exception) {
                Log.e(TAG, "handle send result failed")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SMS_SENT: String = "com.johnc4rl0.smsforwarder.action.SMS_SENT"
        const val EXTRA_JOB_ID: String = "job_id"
        const val EXTRA_PART_INDEX: String = "part_index"
        const val EXTRA_PART_COUNT: String = "part_count"

        private const val TAG = "SendResult"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

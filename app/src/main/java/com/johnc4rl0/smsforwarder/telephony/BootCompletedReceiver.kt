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
 * Non-exported boot receiver (not direct-boot-aware).
 * After first unlock: revalidate, restore notification hook, schedule cleanup/health, resume jobs.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "ignore unexpected action")
            return
        }

        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                TelephonyEntryPoints.bootRestoreCoordinator(appContext).restore(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "boot restore failed")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootCompleted"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

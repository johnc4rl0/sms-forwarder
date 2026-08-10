package com.johnc4rl0.smsforwarder.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.johnc4rl0.smsforwarder.di.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Non-exported receiver for notification actions (Pause).
 * Pause never requires authentication.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_PAUSE) {
            Log.d(TAG, "ignore unexpected action")
            return
        }

        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                appContext.appContainer().activationCoordinator.pauseManual()
                Log.i(TAG, "manual pause from notification")
            } catch (e: Exception) {
                Log.e(TAG, "pauseManual failed")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE: String = "com.johnc4rl0.smsforwarder.action.PAUSE"
        private const val TAG = "NotifAction"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

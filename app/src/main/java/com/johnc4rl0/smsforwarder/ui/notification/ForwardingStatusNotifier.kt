package com.johnc4rl0.smsforwarder.ui.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.johnc4rl0.smsforwarder.MainActivity
import com.johnc4rl0.smsforwarder.R
import com.johnc4rl0.smsforwarder.domain.model.ForwardingConfig
import com.johnc4rl0.smsforwarder.domain.model.OperationalState
import com.johnc4rl0.smsforwarder.telephony.NotificationActionReceiver
import com.johnc4rl0.smsforwarder.ui.util.maskE164

/**
 * Low-importance ongoing status notification while forwarding is enabled,
 * with masked configuration and a Pause action matching [NotificationActionReceiver].
 */
class ForwardingStatusNotifier(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.notification_channel_status),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = appContext.getString(R.string.notification_channel_status_desc)
            setShowBadge(false)
        }
        notificationManager?.createNotificationChannel(channel)
    }

    /**
     * Shows ongoing status when [OperationalState.Enabled]; cancels otherwise.
     * Call from UI or boot restoration when config changes.
     */
    fun sync(config: ForwardingConfig) {
        ensureChannel()
        when (config.operationalState) {
            OperationalState.Enabled -> showEnabled(config)
            else -> cancel()
        }
    }

    fun showEnabled(config: ForwardingConfig) {
        ensureChannel()
        val source = maskE164(config.source?.effectiveNumberE164)
        val outbound = maskE164(config.outbound?.effectiveNumberE164)
        val destination = maskE164(config.destinationE164)
        val body = appContext.getString(
            R.string.notification_body_config,
            source,
            outbound,
            destination,
        )

        val contentIntent = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val pauseIntent = Intent(appContext, NotificationActionReceiver::class.java).apply {
            action = ACTION_PAUSE
        }
        val pausePending = PendingIntent.getBroadcast(
            appContext,
            1,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(appContext.getString(R.string.notification_title_enabled))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(
                0,
                appContext.getString(R.string.notification_pause),
                pausePending,
            )
            .build()

        try {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS missing — health checks fail closed elsewhere.
        }
    }

    fun cancel() {
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
    }

    companion object {
        const val CHANNEL_ID = "forwarding_status"
        const val NOTIFICATION_ID = 1001
        /** Must match manifest intent-filter on [NotificationActionReceiver]. */
        const val ACTION_PAUSE = "com.johnc4rl0.smsforwarder.action.PAUSE"
    }
}

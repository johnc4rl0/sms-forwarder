package com.johnc4rl0.smsforwarder.telephony

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat

/**
 * Runtime permission and notification health checks used when building [com.johnc4rl0.smsforwarder.domain.model.RuntimeSnapshot].
 */
object PermissionAndNotificationHealth {

    fun hasReceiveSms(context: Context): Boolean =
        isGranted(context, Manifest.permission.RECEIVE_SMS)

    fun hasSendSms(context: Context): Boolean =
        isGranted(context, Manifest.permission.SEND_SMS)

    fun hasReadPhoneState(context: Context): Boolean =
        isGranted(context, Manifest.permission.READ_PHONE_STATE)

    fun hasReadPhoneNumbers(context: Context): Boolean =
        isGranted(context, Manifest.permission.READ_PHONE_NUMBERS)

    /** All permissions required for safe receive + send + subscription identity. */
    fun permissionsOk(context: Context): Boolean =
        hasReceiveSms(context) &&
            hasSendSms(context) &&
            hasReadPhoneState(context) &&
            hasReadPhoneNumbers(context) &&
            postNotificationsOk(context) &&
            smsAppOpsOk(context)

    /**
     * POST_NOTIFICATIONS is runtime on API 33+. Treated as part of permission health
     * so missing grant fails closed together with other hard requirements.
     */
    fun postNotificationsOk(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Notification channels / app-level notification toggle. */
    fun notificationsEnabled(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = nm.getNotificationChannel(STATUS_CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }
        return true
    }

    private const val STATUS_CHANNEL_ID = "forwarding_status"

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Package permission state is not sufficient for SMS: AppOps can deny either operation. */
    private fun smsAppOpsOk(context: Context): Boolean {
        val appOps = context.getSystemService(android.app.AppOpsManager::class.java) ?: return false
        return checkOp(appOps, android.app.AppOpsManager.OPSTR_RECEIVE_SMS, context) &&
            checkOp(appOps, android.app.AppOpsManager.OPSTR_SEND_SMS, context)
    }

    private fun checkOp(
        appOps: android.app.AppOpsManager,
        op: String,
        context: Context,
    ): Boolean = try {
        @Suppress("DEPRECATION")
        appOps.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName) ==
            android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }
}

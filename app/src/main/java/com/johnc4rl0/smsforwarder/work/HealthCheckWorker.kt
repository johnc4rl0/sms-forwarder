package com.johnc4rl0.smsforwarder.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.johnc4rl0.smsforwarder.di.appContainer
import com.johnc4rl0.smsforwarder.domain.model.LineValidation
import com.johnc4rl0.smsforwarder.domain.model.OperationalState
import com.johnc4rl0.smsforwarder.domain.model.PauseReason
import com.johnc4rl0.smsforwarder.telephony.PermissionAndNotificationHealth
import com.johnc4rl0.smsforwarder.telephony.SensitiveSmsPrivilege

/**
 * Periodic health: permissions, notifications, subscription identity.
 * Fail closed with safety pause when previously enabled.
 */
class HealthCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer()
        val config = try {
            container.configRepository.getConfig()
        } catch (e: Exception) {
            return Result.success()
        }

        if (config.operationalState !is OperationalState.Enabled) {
            return Result.success()
        }

        val reason = when {
            !PermissionAndNotificationHealth.permissionsOk(applicationContext) ->
                PauseReason.PERMISSIONS_REVOKED
            !PermissionAndNotificationHealth.notificationsEnabled(applicationContext) ->
                PauseReason.NOTIFICATIONS_DISABLED
            !SensitiveSmsPrivilege.privilegeOk(applicationContext) ->
                PauseReason.SENSITIVE_SMS_PRIVILEGE_MISSING
            config.source == null || config.outbound == null || !config.destinationVerified ->
                PauseReason.CONFIGURATION_INCOMPLETE
            else -> {
                val src = config.source?.let { container.subscriptionCatalog.validate(it) }
                val out = config.outbound?.let { container.subscriptionCatalog.validate(it) }
                when {
                    src is LineValidation.Invalid -> when (src.reason) {
                        PauseReason.SOURCE_IDENTITY_MISMATCH -> PauseReason.SOURCE_IDENTITY_MISMATCH
                        PauseReason.SOURCE_IDENTITY_UNAVAILABLE -> PauseReason.SOURCE_IDENTITY_UNAVAILABLE
                        else -> PauseReason.SOURCE_SUBSCRIPTION_INACTIVE
                    }
                    out is LineValidation.Invalid -> when (out.reason) {
                        PauseReason.SOURCE_IDENTITY_MISMATCH -> PauseReason.OUTBOUND_IDENTITY_MISMATCH
                        PauseReason.SOURCE_IDENTITY_UNAVAILABLE -> PauseReason.OUTBOUND_IDENTITY_UNAVAILABLE
                        else -> PauseReason.OUTBOUND_SUBSCRIPTION_INACTIVE
                    }
                    else -> null
                }
            }
        }

        if (reason != null) {
            Log.w(TAG, "health fail closed reason=$reason")
            try {
                container.activationCoordinator.safetyPause(reason)
            } catch (e: Exception) {
                Log.e(TAG, "safetyPause failed")
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "HealthCheck"
    }
}

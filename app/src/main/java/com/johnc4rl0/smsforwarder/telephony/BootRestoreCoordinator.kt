package com.johnc4rl0.smsforwarder.telephony

import android.content.Context
import android.util.Log
import com.johnc4rl0.smsforwarder.domain.ActivationCoordinator
import com.johnc4rl0.smsforwarder.domain.ConfigRepository
import com.johnc4rl0.smsforwarder.domain.DedupStore
import com.johnc4rl0.smsforwarder.domain.ForwardJobRepository
import com.johnc4rl0.smsforwarder.domain.SubscriptionCatalog
import com.johnc4rl0.smsforwarder.domain.model.ForwardState
import com.johnc4rl0.smsforwarder.domain.model.LineValidation
import com.johnc4rl0.smsforwarder.domain.model.OperationalState
import com.johnc4rl0.smsforwarder.domain.model.PauseReason
import com.johnc4rl0.smsforwarder.work.ForwardWorkScheduler

/**
 * Post-unlock boot path: revalidate lines/permissions, restore notification, schedule cleanup/health,
 * and resume valid queued jobs.
 */
class BootRestoreCoordinator(
    private val configRepository: ConfigRepository,
    private val subscriptionCatalog: SubscriptionCatalog,
    private val activationCoordinator: ActivationCoordinator,
    private val forwardJobRepository: ForwardJobRepository,
    private val dedupStore: DedupStore,
    private val notificationController: NotificationController,
    private val workScheduler: ForwardWorkScheduler,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun restore(context: Context) {
        val now = clock()
        val config = try {
            configRepository.getConfig()
        } catch (e: Exception) {
            Log.e(TAG, "getConfig failed on boot")
            return
        }

        // Revalidate health when previously enabled.
        if (config.operationalState is OperationalState.Enabled) {
            val pause = detectSafetyIssue(context, config.source, config.outbound)
            if (pause != null) {
                Log.w(TAG, "boot revalidate safety pause reason=$pause")
                try {
                    activationCoordinator.safetyPause(pause)
                } catch (e: Exception) {
                    Log.e(TAG, "safetyPause on boot failed")
                }
                notificationController.cancelStatus()
            } else {
                notificationController.showOrUpdateStatus(config)
            }
        } else {
            notificationController.cancelStatus()
        }

        try {
            dedupStore.purgeExpired(now)
        } catch (_: Exception) {
            // non-fatal
        }

        workScheduler.schedulePeriodicCleanup(context)
        workScheduler.scheduleHealthCheck(context)

        // Resume valid queued / retry-wait work; expire stale jobs inside the worker.
        val resumable = try {
            forwardJobRepository.listByStates(
                setOf(ForwardState.QUEUED, ForwardState.RETRY_WAIT, ForwardState.SUBMITTING),
            )
        } catch (e: Exception) {
            emptyList()
        }
        if (resumable.isNotEmpty()) {
            Log.i(TAG, "boot resuming jobs count=${resumable.size}")
            workScheduler.enqueueProcessExpedited(context)
            // Re-arm callback timeouts for in-flight submissions.
            for (job in resumable) {
                if (job.state == ForwardState.SUBMITTING) {
                    workScheduler.scheduleCallbackTimeout(context, job.id)
                }
            }
        }
    }

    private fun detectSafetyIssue(
        context: Context,
        source: com.johnc4rl0.smsforwarder.domain.model.LineSelection?,
        outbound: com.johnc4rl0.smsforwarder.domain.model.LineSelection?,
    ): PauseReason? {
        if (!PermissionAndNotificationHealth.permissionsOk(context)) {
            return PauseReason.PERMISSIONS_REVOKED
        }
        if (!PermissionAndNotificationHealth.notificationsEnabled(context)) {
            return PauseReason.NOTIFICATIONS_DISABLED
        }
        if (!SensitiveSmsPrivilege.privilegeOk(context)) {
            return PauseReason.SENSITIVE_SMS_PRIVILEGE_MISSING
        }
        if (source == null || outbound == null) {
            return PauseReason.CONFIGURATION_INCOMPLETE
        }
        when (val v = subscriptionCatalog.validate(source)) {
            is LineValidation.Invalid -> return when (v.reason) {
                PauseReason.SOURCE_IDENTITY_MISMATCH -> PauseReason.SOURCE_IDENTITY_MISMATCH
                PauseReason.SOURCE_IDENTITY_UNAVAILABLE -> PauseReason.SOURCE_IDENTITY_UNAVAILABLE
                else -> PauseReason.SOURCE_SUBSCRIPTION_INACTIVE
            }
            LineValidation.Valid -> Unit
        }
        when (val v = subscriptionCatalog.validate(outbound)) {
            is LineValidation.Invalid -> return when (v.reason) {
                PauseReason.SOURCE_IDENTITY_MISMATCH -> PauseReason.OUTBOUND_IDENTITY_MISMATCH
                PauseReason.SOURCE_IDENTITY_UNAVAILABLE -> PauseReason.OUTBOUND_IDENTITY_UNAVAILABLE
                else -> PauseReason.OUTBOUND_SUBSCRIPTION_INACTIVE
            }
            LineValidation.Valid -> Unit
        }
        return null
    }

    companion object {
        private const val TAG = "BootRestore"
    }
}

package com.johnc4rl0.smsforwarder.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.johnc4rl0.smsforwarder.di.appContainer
import com.johnc4rl0.smsforwarder.domain.ConfigRepository
import com.johnc4rl0.smsforwarder.domain.DefaultForwardingEngine
import com.johnc4rl0.smsforwarder.domain.ForwardSubmissionGate
import com.johnc4rl0.smsforwarder.domain.model.ErrorCategory
import com.johnc4rl0.smsforwarder.domain.model.ForwardJob
import com.johnc4rl0.smsforwarder.domain.model.ForwardState
import com.johnc4rl0.smsforwarder.domain.model.LineValidation
import com.johnc4rl0.smsforwarder.domain.model.OperationalState
import com.johnc4rl0.smsforwarder.domain.model.PauseReason
import com.johnc4rl0.smsforwarder.domain.model.SubmitResult
import com.johnc4rl0.smsforwarder.telephony.PermissionAndNotificationHealth
import com.johnc4rl0.smsforwarder.telephony.RetryPolicy
import com.johnc4rl0.smsforwarder.telephony.SensitiveSmsPrivilege

/**
 * Processes QUEUED / RETRY_WAIT jobs: expire TTL, submit via SmsGateway, schedule retries / timeouts.
 *
 * Partial sends and missing callbacks are never retried (handled by SendResultReceiver / CallbackTimeoutWorker).
 */
class ProcessForwardJobsWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer()
        val repo = container.forwardJobRepository
        val gateway = container.smsGateway
        val configRepository = container.configRepository
        val catalog = container.subscriptionCatalog
        val coordinator = container.activationCoordinator
        val submissionGate = container.forwardSubmissionGate
        val scheduler = ForwardWorkScheduler()
        val now = System.currentTimeMillis()

        val candidates = try {
            repo.listByStates(setOf(ForwardState.QUEUED, ForwardState.RETRY_WAIT))
        } catch (e: Exception) {
            Log.e(TAG, "listByStates failed")
            return Result.retry()
        }

        for (job in candidates) {
            try {
                processOne(
                    job,
                    now,
                    repo,
                    gateway,
                    scheduler,
                    configRepository,
                    catalog,
                    coordinator,
                    submissionGate,
                )
            } catch (e: Exception) {
                Log.e(TAG, "processOne failed jobState=${job.state}")
            }
        }
        return Result.success()
    }

    private suspend fun processOne(
        job: ForwardJob,
        now: Long,
        repo: com.johnc4rl0.smsforwarder.domain.ForwardJobRepository,
        gateway: com.johnc4rl0.smsforwarder.domain.SmsGateway,
        scheduler: ForwardWorkScheduler,
        configRepository: ConfigRepository,
        catalog: com.johnc4rl0.smsforwarder.domain.SubscriptionCatalog,
        coordinator: com.johnc4rl0.smsforwarder.domain.ActivationCoordinator,
        submissionGate: ForwardSubmissionGate,
    ) {
        if (RetryPolicy.isExpired(job.createdAtMillis, now)) {
            Log.i(TAG, "job expired by TTL category=expired_ttl")
            repo.updateState(
                jobId = job.id,
                state = ForwardState.FAILED,
                attemptCount = job.attemptCount,
                lastErrorCategory = ErrorCategory.EXPIRED_TTL,
            )
            repo.purgeSensitivePayloads(listOf(job.id))
            return
        }

        if (!RetryPolicy.canAttempt(job.attemptCount)) {
            Log.i(TAG, "job max attempts reached")
            repo.updateState(job.id, ForwardState.FAILED, attemptCount = job.attemptCount)
            repo.purgeSensitivePayloads(listOf(job.id))
            return
        }

        // Enforce retry backoff: never re-submit RETRY_WAIT jobs before nextAttemptAtMillis.
        if (job.state == ForwardState.RETRY_WAIT) {
            val nextAt = job.nextAttemptAtMillis
            if (nextAt != null && nextAt > now) {
                scheduler.enqueueProcessDelayed(applicationContext, nextAt - now)
                return
            }
            if (nextAt == null) {
                // Missing due time (legacy / incomplete write): persist delay and wait.
                val delay = RetryPolicy.delayAfterFailedAttempt(job.attemptCount)
                if (delay != null) {
                    val due = now + delay
                    repo.updateState(
                        jobId = job.id,
                        state = ForwardState.RETRY_WAIT,
                        attemptCount = job.attemptCount,
                        nextAttemptAtMillis = due,
                        updateNextAttemptAt = true,
                    )
                    scheduler.enqueueProcessDelayed(applicationContext, delay)
                    return
                }
            }
        }

        // Gate submit on operational state + config revision (stale jobs must not send).
        val config = try {
            configRepository.getConfig()
        } catch (e: Exception) {
            Log.e(TAG, "getConfig failed — skip submit")
            return
        }
        if (config.operationalState !is OperationalState.Enabled) {
            Log.i(TAG, "skip submit: operational state not Enabled")
            return
        }
        if (!DefaultForwardingEngine.isConfigRevisionCurrent(job.configRevision, config)) {
            Log.i(TAG, "fail job: configRevision mismatch")
            repo.updateState(
                jobId = job.id,
                state = ForwardState.FAILED,
                attemptCount = job.attemptCount,
                lastErrorCategory = ErrorCategory.POLICY_OR_GENERIC,
            )
            repo.purgeSensitivePayloads(listOf(job.id))
            return
        }

        val healthReason = liveHealthPauseReason()
        if (healthReason != null) {
            Log.w(TAG, "skip submit: live health gate failed reason=$healthReason")
            failClosed(
                job = job,
                attemptCount = job.attemptCount,
                reason = healthReason,
                repo = repo,
                coordinator = coordinator,
            )
            return
        }

        val outbound = config.outbound
        if (outbound == null) {
            Log.w(TAG, "abort submit: outbound line missing — safety pause")
            failClosed(
                job = job,
                attemptCount = job.attemptCount,
                reason = PauseReason.CONFIGURATION_INCOMPLETE,
                repo = repo,
                coordinator = coordinator,
            )
            return
        }
        val source = config.source
        if (source == null) {
            Log.w(TAG, "abort submit: source line missing — safety pause")
            failClosed(
                job = job,
                attemptCount = job.attemptCount,
                reason = PauseReason.CONFIGURATION_INCOMPLETE,
                repo = repo,
                coordinator = coordinator,
            )
            return
        }
        val sourceValidation = catalog.validate(source)
        if (sourceValidation is LineValidation.Invalid) {
            val pauseReason = sourcePauseReason(sourceValidation)
            Log.w(TAG, "source line invalid reason=$pauseReason — safety pause")
            failClosed(
                job = job,
                attemptCount = job.attemptCount,
                reason = pauseReason,
                repo = repo,
                coordinator = coordinator,
            )
            return
        }
        val outboundValidation = catalog.validate(outbound)
        if (outboundValidation is LineValidation.Invalid) {
            val pauseReason = outboundPauseReason(outboundValidation)
            Log.w(TAG, "outbound line invalid reason=$pauseReason — safety pause")
            failClosed(
                job = job,
                attemptCount = job.attemptCount,
                reason = pauseReason,
                repo = repo,
                coordinator = coordinator,
            )
            return
        }

        val nextAttempt = job.attemptCount + 1
        val claimed = repo.claimForSubmission(
            jobId = job.id,
            fromStates = setOf(ForwardState.QUEUED, ForwardState.RETRY_WAIT),
            targetAttemptCount = nextAttempt,
        )
        if (!claimed) {
            Log.i(TAG, "skip submit: job already claimed by another worker")
            return
        }

        // Serialize the final config/health/identity check with every pause and
        // configuration mutation. The gate remains held through SmsGateway.submit,
        // making this a linearizable send boundary instead of another TOCTOU window.
        val boundaryResult = submissionGate.withLock {
            val latestConfig = try {
                configRepository.getConfig()
            } catch (_: Exception) {
                null
            }
            if (latestConfig == null ||
                latestConfig.operationalState !is OperationalState.Enabled ||
                !DefaultForwardingEngine.isConfigRevisionCurrent(job.configRevision, latestConfig)
            ) {
                return@withLock SubmitBoundaryResult.Blocked(PauseReason.CONFIGURATION_INCOMPLETE)
            }

            val latestSource = latestConfig.source
            val latestOutbound = latestConfig.outbound
            val healthReason = liveHealthPauseReason()
            val lineReason = if (healthReason != null) {
                healthReason
            } else {
                when {
                    latestSource == null || latestOutbound == null ->
                        PauseReason.CONFIGURATION_INCOMPLETE
                    else -> {
                        val latestSourceValidation = catalog.validate(latestSource)
                        val latestOutboundValidation = catalog.validate(latestOutbound)
                        when {
                            latestSourceValidation is LineValidation.Invalid ->
                                sourcePauseReason(latestSourceValidation)
                            latestOutboundValidation is LineValidation.Invalid ->
                                outboundPauseReason(latestOutboundValidation)
                            else -> null
                        }
                    }
                }
            }
            if (lineReason != null) {
                return@withLock SubmitBoundaryResult.Blocked(lineReason)
            }

            try {
                SubmitBoundaryResult.Completed(
                    gateway.submit(job.copy(attemptCount = nextAttempt, state = ForwardState.SUBMITTING)),
                )
            } catch (_: Exception) {
                SubmitBoundaryResult.Threw
            }
        }

        if (boundaryResult is SubmitBoundaryResult.Blocked) {
            Log.w(TAG, "abort submit: live send gate failed reason=${boundaryResult.reason}")
            failClosed(
                job = job,
                attemptCount = nextAttempt,
                reason = boundaryResult.reason,
                repo = repo,
                coordinator = coordinator,
            )
            return
        }
        if (boundaryResult === SubmitBoundaryResult.Threw) {
            Log.e(TAG, "gateway.submit threw")
            handleSubmitFailure(
                jobId = job.id,
                attemptCount = nextAttempt,
                category = ErrorCategory.UNKNOWN,
                now = now,
                repo = repo,
                scheduler = scheduler,
            )
            return
        }
        val submitResult = (boundaryResult as SubmitBoundaryResult.Completed).result

        when (submitResult) {
            is SubmitResult.Submitted -> {
                // Wait for segment callbacks; arm 15-minute UNKNOWN timeout (no partial retry).
                scheduler.scheduleCallbackTimeout(applicationContext, job.id)
                Log.i(TAG, "submitted segments=${submitResult.segmentCount}")
            }
            is SubmitResult.Failed -> {
                val retryable = isRetryableSubmitFailure(submitResult.category)
                if (retryable) {
                    handleSubmitFailure(
                        jobId = job.id,
                        attemptCount = nextAttempt,
                        category = submitResult.category,
                        now = now,
                        repo = repo,
                        scheduler = scheduler,
                    )
                } else {
                    repo.updateState(
                        jobId = job.id,
                        state = ForwardState.FAILED,
                        attemptCount = nextAttempt,
                        lastErrorCategory = submitResult.category,
                    )
                    repo.purgeSensitivePayloads(listOf(job.id))
                    Log.i(TAG, "submit failed non-retryable category=${submitResult.category}")
                }
            }
        }
    }

    private fun liveHealthPauseReason(): PauseReason? = when {
        !PermissionAndNotificationHealth.permissionsOk(applicationContext) ->
            PauseReason.PERMISSIONS_REVOKED
        !PermissionAndNotificationHealth.notificationsEnabled(applicationContext) ->
            PauseReason.NOTIFICATIONS_DISABLED
        !SensitiveSmsPrivilege.privilegeOk(applicationContext) ->
            PauseReason.SENSITIVE_SMS_PRIVILEGE_MISSING
        else -> null
    }

    private fun sourcePauseReason(validation: LineValidation.Invalid): PauseReason =
        when (validation.reason) {
            PauseReason.SOURCE_IDENTITY_MISMATCH -> PauseReason.SOURCE_IDENTITY_MISMATCH
            else -> PauseReason.SOURCE_SUBSCRIPTION_INACTIVE
        }

    private fun outboundPauseReason(validation: LineValidation.Invalid): PauseReason =
        when (validation.reason) {
            PauseReason.SOURCE_IDENTITY_MISMATCH -> PauseReason.OUTBOUND_IDENTITY_MISMATCH
            else -> PauseReason.OUTBOUND_SUBSCRIPTION_INACTIVE
        }

    private sealed interface SubmitBoundaryResult {
        data class Blocked(val reason: PauseReason) : SubmitBoundaryResult
        data class Completed(val result: SubmitResult) : SubmitBoundaryResult
        data object Threw : SubmitBoundaryResult
    }

    private suspend fun failClosed(
        job: ForwardJob,
        attemptCount: Int,
        reason: PauseReason,
        repo: com.johnc4rl0.smsforwarder.domain.ForwardJobRepository,
        coordinator: com.johnc4rl0.smsforwarder.domain.ActivationCoordinator,
    ) {
        try {
            coordinator.safetyPause(reason)
        } catch (_: Exception) {
            Log.e(TAG, "safetyPause failed")
        }
        try {
            repo.updateState(
                jobId = job.id,
                state = ForwardState.FAILED,
                attemptCount = attemptCount,
                lastErrorCategory = ErrorCategory.POLICY_OR_GENERIC,
            )
            repo.purgeSensitivePayloads(listOf(job.id))
        } catch (_: Exception) {
            Log.e(TAG, "failed to purge blocked job")
        }
    }

    private suspend fun handleSubmitFailure(
        jobId: String,
        attemptCount: Int,
        category: ErrorCategory,
        now: Long,
        repo: com.johnc4rl0.smsforwarder.domain.ForwardJobRepository,
        scheduler: ForwardWorkScheduler,
    ) {
        val delay = RetryPolicy.delayAfterFailedAttempt(attemptCount)
        if (delay != null && RetryPolicy.canAttempt(attemptCount)) {
            val due = now + delay
            repo.updateState(
                jobId = jobId,
                state = ForwardState.RETRY_WAIT,
                attemptCount = attemptCount,
                nextAttemptAtMillis = due,
                updateNextAttemptAt = true,
                lastErrorCategory = category,
            )
            scheduler.enqueueProcessDelayed(applicationContext, delay)
            Log.i(TAG, "scheduled retry attempt=$attemptCount delayMs=$delay category=$category")
        } else {
            repo.updateState(
                jobId = jobId,
                state = ForwardState.FAILED,
                attemptCount = attemptCount,
                lastErrorCategory = category,
            )
            repo.purgeSensitivePayloads(listOf(jobId))
            Log.i(TAG, "exhausted retries category=$category")
        }
    }

    /**
     * Pre-callback submit failures: only radio/service/SIM-style categories are retryable.
     * Partial multipart is never produced here (callbacks handle that).
     */
    private fun isRetryableSubmitFailure(category: ErrorCategory): Boolean = when (category) {
        ErrorCategory.TRANSIENT_RADIO,
        ErrorCategory.NO_SERVICE,
        ErrorCategory.SIM_BUSY,
        ErrorCategory.SEND_FAIL_RETRY,
        -> true
        else -> false
    }

    companion object {
        private const val TAG = "ProcessFwdJobs"
    }
}

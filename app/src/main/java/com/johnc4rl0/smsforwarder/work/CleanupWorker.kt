package com.johnc4rl0.smsforwarder.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.johnc4rl0.smsforwarder.di.appContainer
import com.johnc4rl0.smsforwarder.domain.model.ErrorCategory
import com.johnc4rl0.smsforwarder.domain.model.ForwardState
import com.johnc4rl0.smsforwarder.telephony.RetryPolicy

/**
 * Expires queued jobs older than 24h, purges sensitive payloads on terminal/expired work,
 * and drops expired dedup fingerprints.
 */
class CleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer()
        val repo = container.forwardJobRepository
        val dedup = container.dedupStore
        val now = System.currentTimeMillis()

        try {
            val open = repo.listByStates(
                setOf(
                    ForwardState.QUEUED,
                    ForwardState.RETRY_WAIT,
                    ForwardState.SUBMITTING,
                ),
            )
            for (job in open) {
                if (RetryPolicy.isExpired(job.createdAtMillis, now)) {
                    Log.i(TAG, "expiring job state=${job.state} category=expired_ttl")
                    repo.updateState(
                        jobId = job.id,
                        state = ForwardState.FAILED,
                        attemptCount = job.attemptCount,
                        lastErrorCategory = ErrorCategory.EXPIRED_TTL,
                    )
                    repo.purgeSensitivePayloads(listOf(job.id))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "expire pass failed")
        }

        try {
            // Terminal jobs: ensure payloads are gone (id null = implementation-defined purge policy).
            repo.purgeSensitivePayloads(null)
            repo.pruneTerminalOutcomes()
        } catch (_: Exception) {
            // non-fatal
        }

        try {
            dedup.purgeExpired(now)
        } catch (_: Exception) {
            // non-fatal
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "CleanupWorker"
    }
}

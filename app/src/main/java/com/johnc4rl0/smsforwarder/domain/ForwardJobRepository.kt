package com.johnc4rl0.smsforwarder.domain

import com.johnc4rl0.smsforwarder.domain.model.ErrorCategory
import com.johnc4rl0.smsforwarder.domain.model.ForwardJob
import com.johnc4rl0.smsforwarder.domain.model.ForwardState
import com.johnc4rl0.smsforwarder.domain.model.OutcomeMetadata
import com.johnc4rl0.smsforwarder.domain.model.PartSendResult
import com.johnc4rl0.smsforwarder.domain.model.QuotaSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * Durable queue and outcome store (Room + encrypted fields in data layer).
 */
interface ForwardJobRepository {
    /**
     * Persist [job] and reserve quota atomically. Fails closed if quota would be exceeded.
     */
    suspend fun enqueue(job: ForwardJob)

    /** Record a single multipart segment result and aggregate terminal state when complete. */
    suspend fun recordPartResult(result: PartSendResult): ForwardJob?

    /** Last 50 metadata-only outcomes for the dashboard (no bodies/senders). */
    fun observeRecent(limit: Int = 50): Flow<List<OutcomeMetadata>>

    /** Delete terminal metadata older than the retained outcome window. */
    suspend fun pruneTerminalOutcomes(keep: Int = 50) = Unit

    suspend fun getJob(id: String): ForwardJob?

    fun observeJob(id: String): Flow<ForwardJob?>

    /** Jobs eligible for WorkManager processing. */
    suspend fun listByStates(states: Set<ForwardState>): List<ForwardJob>

    /**
     * Atomically claims a job for submission if its current state is in [fromStates].
     * Sets state = SUBMITTING and increments attemptCount to [targetAttemptCount].
     * @return true if successfully claimed; false if the job was already claimed or moved state.
     */
    suspend fun claimForSubmission(
        jobId: String,
        fromStates: Set<ForwardState>,
        targetAttemptCount: Int,
    ): Boolean

    /**
     * Update job lifecycle fields.
     *
     * @param nextAttemptAtMillis when [updateNextAttemptAt] is true, written as-is (may be null to clear)
     * @param updateNextAttemptAt when false, leave stored next-attempt time unless state is
     *   SUBMITTING or terminal (those clear it automatically)
     * @param lastErrorCategory when non-null, overwrites the stored error category
     */
    suspend fun updateState(
        jobId: String,
        state: ForwardState,
        attemptCount: Int? = null,
        nextAttemptAtMillis: Long? = null,
        updateNextAttemptAt: Boolean = false,
        lastErrorCategory: ErrorCategory? = null,
    )

    /**
     * Purge sender/body ciphertext for terminal jobs and on pause/config change.
     * Retains metadata only.
     */
    suspend fun purgeSensitivePayloads(jobIds: Collection<String>? = null)

    /** Drop unsent jobs (e.g. line/destination change). */
    suspend fun purgeUnsentJobs()

    suspend fun currentQuota(nowMillis: Long): QuotaSnapshot
}

package com.johnc4rl0.smsforwarder.domain

import com.johnc4rl0.smsforwarder.domain.model.ErrorCategory
import com.johnc4rl0.smsforwarder.domain.model.ForwardState
import com.johnc4rl0.smsforwarder.domain.model.PartSendResult

/**
 * Pure classification of multipart send outcomes into terminal or retry states.
 *
 * Spec rules:
 * - Retry only when zero segments succeeded and every result is definitely transient.
 * - Initial attempt + at most three retries after 1, 5, and 30 minutes.
 * - Never retry partial multipart sends, generic/policy failures, or missing callbacks
 *   after 15 minutes (→ UNKNOWN).
 * - Queued jobs expire after 24 hours.
 */
object RetryClassifier {
    /** Max retries after the initial attempt (total attempts = MAX_RETRIES + 1). */
    const val MAX_RETRIES: Int = 3
    const val MAX_ATTEMPTS: Int = MAX_RETRIES + 1

    /** Backoff after attempt 1, 2, 3 (milliseconds). */
    val RETRY_BACKOFF_MILLIS: LongArray = longArrayOf(
        1L * 60L * 1000L,
        5L * 60L * 1000L,
        30L * 60L * 1000L,
    )

    const val CALLBACK_TIMEOUT_MILLIS: Long = 15L * 60L * 1000L
    const val JOB_TTL_MILLIS: Long = 24L * 60L * 60L * 1000L

    /** Result of classifying a completed or partial set of segment callbacks. */
    data class Classification(
        val state: ForwardState,
        val errorCategory: ErrorCategory?,
        /** When [state] is RETRY_WAIT, suggested delay before next attempt. */
        val retryDelayMillis: Long? = null,
    )

    /**
     * Classify [results] for a job that has submitted [partCount] segments.
     *
     * @param results callbacks received so far (may be incomplete)
     * @param partCount expected segment count from submission
     * @param attemptCount attempts already made (1 after first submit)
     * @param submittedAtMillis when the current submit started (for callback timeout)
     * @param nowMillis evaluation time
     * @param successResultCode typically [android.app.Activity.RESULT_OK] (-1); injected for purity
     */
    fun classifyPartResults(
        results: List<PartSendResult>,
        partCount: Int,
        attemptCount: Int,
        submittedAtMillis: Long,
        nowMillis: Long,
        successResultCode: Int = -1,
    ): Classification {
        require(partCount > 0) { "partCount must be > 0" }
        require(attemptCount >= 1) { "attemptCount must be >= 1" }

        val byIndex = results.groupBy { it.partIndex }
        val latest = (0 until partCount).mapNotNull { index ->
            byIndex[index]?.maxByOrNull { it.receivedAtMillis }
        }

        val receivedCount = latest.size
        val allReceived = receivedCount >= partCount

        if (!allReceived) {
            val elapsed = nowMillis - submittedAtMillis
            return if (elapsed >= CALLBACK_TIMEOUT_MILLIS) {
                Classification(ForwardState.UNKNOWN, ErrorCategory.CALLBACK_TIMEOUT)
            } else {
                // Still waiting — not a terminal classification for the repository
                Classification(ForwardState.SUBMITTING, null)
            }
        }

        val successCount = latest.count { it.resultCode == successResultCode }
        val failureResults = latest.filter { it.resultCode != successResultCode }

        if (successCount == partCount) {
            return Classification(ForwardState.SENT, null)
        }

        // Partial: some succeeded, some failed — never retry
        if (successCount > 0) {
            return Classification(ForwardState.PARTIAL, ErrorCategory.PARTIAL_SEND)
        }

        // Zero successes: all failed
        val allTransient = failureResults.isNotEmpty() && failureResults.all { it.isTransient }
        if (allTransient) {
            val category = dominantTransientCategory(failureResults)
            return if (attemptCount < MAX_ATTEMPTS) {
                val delay = retryDelayAfterAttempt(attemptCount)
                Classification(ForwardState.RETRY_WAIT, category, retryDelayMillis = delay)
            } else {
                Classification(ForwardState.FAILED, category)
            }
        }

        // Generic / policy / non-transient
        return Classification(ForwardState.FAILED, ErrorCategory.POLICY_OR_GENERIC)
    }

    /**
     * Delay before the next attempt given [attemptCount] that just finished
     * (1 → 1 min, 2 → 5 min, 3 → 30 min). Null when no more retries.
     */
    fun retryDelayAfterAttempt(attemptCount: Int): Long? {
        if (attemptCount < 1 || attemptCount > MAX_RETRIES) return null
        return RETRY_BACKOFF_MILLIS[attemptCount - 1]
    }

    /** True when a queued job created at [createdAtMillis] has exceeded the 24h TTL. */
    fun isJobExpired(createdAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis - createdAtMillis >= JOB_TTL_MILLIS

    /** Terminal classification when TTL expires while still unsent. */
    fun expiredClassification(): Classification =
        Classification(ForwardState.FAILED, ErrorCategory.EXPIRED_TTL)

    /** Whether another submit is allowed for [attemptCount] (attempts so far). */
    fun canRetry(attemptCount: Int): Boolean = attemptCount < MAX_ATTEMPTS

    private fun dominantTransientCategory(failures: List<PartSendResult>): ErrorCategory {
        // Prefer more specific categories if result codes are mapped by the telephony layer
        // into isTransient; without raw code maps we use TRANSIENT_RADIO as the umbrella.
        // Callers may set isTransient consistently for radio/no-service/sim-busy/retry.
        return ErrorCategory.TRANSIENT_RADIO
    }

    /**
     * Map common SmsManager error codes to [ErrorCategory] (pure; telephony may use this).
     * Codes mirror android.telephony.SmsManager RESULT_* constants.
     */
    fun categoryForSmsResultCode(resultCode: Int, successResultCode: Int = -1): ErrorCategory {
        if (resultCode == successResultCode) return ErrorCategory.UNKNOWN // unused for success
        return when (resultCode) {
            2 -> ErrorCategory.TRANSIENT_RADIO // RESULT_ERROR_RADIO_OFF
            4 -> ErrorCategory.NO_SERVICE // RESULT_ERROR_NO_SERVICE
            9 -> ErrorCategory.TRANSIENT_RADIO // RESULT_RADIO_NOT_AVAILABLE
            // RESULT_ERROR_GENERIC_FAILURE = 1 and others → policy/generic
            else -> ErrorCategory.POLICY_OR_GENERIC
        }
    }

    fun isTransientSmsResultCode(resultCode: Int, successResultCode: Int = -1): Boolean {
        if (resultCode == successResultCode) return false
        return when (resultCode) {
            2, 4, 9 -> true // radio off, no service, radio not available
            // RESULT_ERROR_LIMIT_EXCEEDED (5) is not treated as auto-retry here
            else -> false
        }
    }
}

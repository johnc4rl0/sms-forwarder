package com.johnc4rl0.smsforwarder.domain

import com.google.common.truth.Truth.assertThat
import com.johnc4rl0.smsforwarder.domain.model.ErrorCategory
import com.johnc4rl0.smsforwarder.domain.model.ForwardState
import com.johnc4rl0.smsforwarder.domain.model.PartSendResult
import org.junit.Test

class RetryClassifierTest {

    private val ok = -1
    private val now = 1_000_000L

    private fun part(
        index: Int,
        count: Int,
        code: Int,
        transient: Boolean,
        at: Long = now,
    ) = PartSendResult(
        jobId = "j1",
        partIndex = index,
        partCount = count,
        resultCode = code,
        isTransient = transient,
        receivedAtMillis = at,
    )

    @Test
    fun allSuccess_isSent() {
        val results = listOf(part(0, 1, ok, false))
        val c = RetryClassifier.classifyPartResults(results, 1, attemptCount = 1, now - 1000, now, ok)
        assertThat(c.state).isEqualTo(ForwardState.SENT)
        assertThat(c.errorCategory).isNull()
    }

    @Test
    fun partialSuccess_isPartialNeverRetry() {
        val results = listOf(
            part(0, 2, ok, false),
            part(1, 2, 1, false),
        )
        val c = RetryClassifier.classifyPartResults(results, 2, 1, now - 1000, now, ok)
        assertThat(c.state).isEqualTo(ForwardState.PARTIAL)
        assertThat(c.errorCategory).isEqualTo(ErrorCategory.PARTIAL_SEND)
    }

    @Test
    fun allTransient_schedulesRetryWithBackoff() {
        val results = listOf(part(0, 1, 2, transient = true))
        val c = RetryClassifier.classifyPartResults(results, 1, attemptCount = 1, now - 1000, now, ok)
        assertThat(c.state).isEqualTo(ForwardState.RETRY_WAIT)
        assertThat(c.retryDelayMillis).isEqualTo(1L * 60 * 1000)
    }

    @Test
    fun allTransient_secondAttemptUses5MinBackoff() {
        val results = listOf(part(0, 1, 4, transient = true))
        val c = RetryClassifier.classifyPartResults(results, 1, attemptCount = 2, now - 1000, now, ok)
        assertThat(c.state).isEqualTo(ForwardState.RETRY_WAIT)
        assertThat(c.retryDelayMillis).isEqualTo(5L * 60 * 1000)
    }

    @Test
    fun allTransient_afterMaxAttempts_isFailed() {
        val results = listOf(part(0, 1, 2, transient = true))
        val c = RetryClassifier.classifyPartResults(
            results,
            partCount = 1,
            attemptCount = RetryClassifier.MAX_ATTEMPTS,
            submittedAtMillis = now - 1000,
            nowMillis = now,
            successResultCode = ok,
        )
        assertThat(c.state).isEqualTo(ForwardState.FAILED)
    }

    @Test
    fun nonTransientFailure_isFailedPolicy() {
        val results = listOf(part(0, 1, 1, transient = false))
        val c = RetryClassifier.classifyPartResults(results, 1, 1, now - 1000, now, ok)
        assertThat(c.state).isEqualTo(ForwardState.FAILED)
        assertThat(c.errorCategory).isEqualTo(ErrorCategory.POLICY_OR_GENERIC)
    }

    @Test
    fun missingCallbacksBeforeTimeout_stillSubmitting() {
        val results = listOf(part(0, 2, ok, false))
        val c = RetryClassifier.classifyPartResults(
            results,
            partCount = 2,
            attemptCount = 1,
            submittedAtMillis = now - 60_000,
            nowMillis = now,
            successResultCode = ok,
        )
        assertThat(c.state).isEqualTo(ForwardState.SUBMITTING)
    }

    @Test
    fun missingCallbacksAfter15Min_isUnknown() {
        val results = listOf(part(0, 2, ok, false))
        val c = RetryClassifier.classifyPartResults(
            results,
            partCount = 2,
            attemptCount = 1,
            submittedAtMillis = now - RetryClassifier.CALLBACK_TIMEOUT_MILLIS,
            nowMillis = now,
            successResultCode = ok,
        )
        assertThat(c.state).isEqualTo(ForwardState.UNKNOWN)
        assertThat(c.errorCategory).isEqualTo(ErrorCategory.CALLBACK_TIMEOUT)
    }

    @Test
    fun jobTtl_24h() {
        val created = 0L
        assertThat(RetryClassifier.isJobExpired(created, RetryClassifier.JOB_TTL_MILLIS - 1)).isFalse()
        assertThat(RetryClassifier.isJobExpired(created, RetryClassifier.JOB_TTL_MILLIS)).isTrue()
        assertThat(RetryClassifier.expiredClassification().errorCategory)
            .isEqualTo(ErrorCategory.EXPIRED_TTL)
    }

    @Test
    fun canRetry_respectsAttemptLimit() {
        assertThat(RetryClassifier.canRetry(1)).isTrue()
        assertThat(RetryClassifier.canRetry(3)).isTrue()
        assertThat(RetryClassifier.canRetry(4)).isFalse()
    }

    @Test
    fun backoffSchedule_1_5_30_minutes() {
        assertThat(RetryClassifier.retryDelayAfterAttempt(1)).isEqualTo(60_000L)
        assertThat(RetryClassifier.retryDelayAfterAttempt(2)).isEqualTo(5 * 60_000L)
        assertThat(RetryClassifier.retryDelayAfterAttempt(3)).isEqualTo(30 * 60_000L)
        assertThat(RetryClassifier.retryDelayAfterAttempt(4)).isNull()
    }
}

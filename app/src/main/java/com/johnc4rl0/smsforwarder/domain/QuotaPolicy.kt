package com.johnc4rl0.smsforwarder.domain

import com.johnc4rl0.smsforwarder.domain.model.PauseReason
import com.johnc4rl0.smsforwarder.domain.model.RuntimeSnapshot

/**
 * Pure rolling-window quota decisions.
 *
 * Limits (defaults): 100 source messages and 500 outbound segments per 24h.
 * Each accepted source message counts once; retries do not consume extra source quota.
 * A message that would exceed either limit must not be admitted.
 */
object QuotaPolicy {
    const val DEFAULT_SOURCE_MESSAGE_LIMIT: Int = 100
    const val DEFAULT_OUTBOUND_SEGMENT_LIMIT: Int = 500
    const val WINDOW_MILLIS: Long = 24L * 60L * 60L * 1000L

    sealed class QuotaDecision {
        data object Allowed : QuotaDecision()
        data class Exceeded(val pauseReason: PauseReason) : QuotaDecision()
    }

    /**
     * Whether admitting one more source message that produces [estimatedSegments]
     * outbound segments is within [snapshot] limits.
     */
    fun checkAdmission(
        sourceMessagesUsed: Int,
        outboundSegmentsUsed: Int,
        estimatedSegments: Int,
        sourceMessageLimit: Int = DEFAULT_SOURCE_MESSAGE_LIMIT,
        outboundSegmentLimit: Int = DEFAULT_OUTBOUND_SEGMENT_LIMIT,
    ): QuotaDecision {
        require(estimatedSegments >= 0) { "estimatedSegments must be >= 0" }
        // Source message budget: need room for +1
        if (sourceMessagesUsed >= sourceMessageLimit) {
            return QuotaDecision.Exceeded(PauseReason.QUOTA_SOURCE_MESSAGES)
        }
        // Segment budget: need room for this job's segments
        val segments = estimatedSegments.coerceAtLeast(1)
        if (outboundSegmentsUsed >= outboundSegmentLimit) {
            return QuotaDecision.Exceeded(PauseReason.QUOTA_OUTBOUND_SEGMENTS)
        }
        if (outboundSegmentsUsed + segments > outboundSegmentLimit) {
            return QuotaDecision.Exceeded(PauseReason.QUOTA_OUTBOUND_SEGMENTS)
        }
        return QuotaDecision.Allowed
    }

    fun checkAdmission(snapshot: RuntimeSnapshot, estimatedSegments: Int): QuotaDecision =
        checkAdmission(
            sourceMessagesUsed = snapshot.sourceMessagesUsedInWindow,
            outboundSegmentsUsed = snapshot.outboundSegmentsUsedInWindow,
            estimatedSegments = estimatedSegments,
            sourceMessageLimit = snapshot.sourceMessageLimit,
            outboundSegmentLimit = snapshot.outboundSegmentLimit,
        )

    /**
     * True when re-enable is allowed because both counters are under their limits
     * (authentication cannot reset counters — caller still enforces auth separately).
     */
    fun hasAvailableCapacity(
        sourceMessagesUsed: Int,
        outboundSegmentsUsed: Int,
        sourceMessageLimit: Int = DEFAULT_SOURCE_MESSAGE_LIMIT,
        outboundSegmentLimit: Int = DEFAULT_OUTBOUND_SEGMENT_LIMIT,
    ): Boolean =
        sourceMessagesUsed < sourceMessageLimit && outboundSegmentsUsed < outboundSegmentLimit
}

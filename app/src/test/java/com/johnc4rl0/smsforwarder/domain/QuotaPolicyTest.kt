package com.johnc4rl0.smsforwarder.domain

import com.google.common.truth.Truth.assertThat
import com.johnc4rl0.smsforwarder.domain.model.PauseReason
import org.junit.Test

class QuotaPolicyTest {

    @Test
    fun allowsWhenUnderBothLimits() {
        val d = QuotaPolicy.checkAdmission(
            sourceMessagesUsed = 0,
            outboundSegmentsUsed = 0,
            estimatedSegments = 1,
        )
        assertThat(d).isEqualTo(QuotaPolicy.QuotaDecision.Allowed)
    }

    @Test
    fun rejectsWhenSourceMessagesAtLimit() {
        val d = QuotaPolicy.checkAdmission(
            sourceMessagesUsed = 100,
            outboundSegmentsUsed = 0,
            estimatedSegments = 1,
            sourceMessageLimit = 100,
        )
        assertThat(d).isEqualTo(
            QuotaPolicy.QuotaDecision.Exceeded(PauseReason.QUOTA_SOURCE_MESSAGES),
        )
    }

    @Test
    fun rejectsWhenSourceMessagesWouldExceed() {
        // at 99 of 100, +1 is ok
        assertThat(
            QuotaPolicy.checkAdmission(99, 0, 1, 100, 500),
        ).isEqualTo(QuotaPolicy.QuotaDecision.Allowed)

        assertThat(
            QuotaPolicy.checkAdmission(100, 0, 1, 100, 500),
        ).isInstanceOf(QuotaPolicy.QuotaDecision.Exceeded::class.java)
    }

    @Test
    fun rejectsWhenSegmentsWouldExceed() {
        val d = QuotaPolicy.checkAdmission(
            sourceMessagesUsed = 0,
            outboundSegmentsUsed = 498,
            estimatedSegments = 3,
            outboundSegmentLimit = 500,
        )
        assertThat(d).isEqualTo(
            QuotaPolicy.QuotaDecision.Exceeded(PauseReason.QUOTA_OUTBOUND_SEGMENTS),
        )
    }

    @Test
    fun allowsSegmentsExactlyFillingBudget() {
        val d = QuotaPolicy.checkAdmission(
            sourceMessagesUsed = 0,
            outboundSegmentsUsed = 497,
            estimatedSegments = 3,
            outboundSegmentLimit = 500,
        )
        assertThat(d).isEqualTo(QuotaPolicy.QuotaDecision.Allowed)
    }

    @Test
    fun hasAvailableCapacity_falseWhenEitherFull() {
        assertThat(QuotaPolicy.hasAvailableCapacity(100, 0)).isFalse()
        assertThat(QuotaPolicy.hasAvailableCapacity(0, 500)).isFalse()
        assertThat(QuotaPolicy.hasAvailableCapacity(50, 100)).isTrue()
    }
}

package com.johnc4rl0.smsforwarder.domain

import com.google.common.truth.Truth.assertThat
import com.johnc4rl0.smsforwarder.domain.model.DestinationVerificationState
import org.junit.Test

class VerificationPolicyTest {

    private val now = 1_000_000L
    private val digest = byteArrayOf(1, 2, 3)

    private fun state(
        attempts: Int = 5,
        sends: Int = 1,
        lastSend: Long = now,
        expires: Long = now + VerificationPolicy.CODE_EXPIRY_MILLIS,
    ) = DestinationVerificationState(
        destinationE164 = "+15551234567",
        codeDigest = digest,
        expiresAtMillis = expires,
        attemptsRemaining = attempts,
        sendsInRollingHour = sends,
        lastSendAtMillis = lastSend,
    )

    @Test
    fun canSend_rateLimitedAfterThreeInHour() {
        val s = state(sends = 3, lastSend = now - 1000)
        assertThat(VerificationPolicy.canSend(s, now))
            .isEqualTo(VerificationPolicy.SendDecision.RateLimited)
    }

    @Test
    fun canSend_allowsAfterHourElapses() {
        val s = state(sends = 3, lastSend = now - VerificationPolicy.ROLLING_HOUR_MILLIS)
        assertThat(VerificationPolicy.canSend(s, now))
            .isEqualTo(VerificationPolicy.SendDecision.Allow)
    }

    @Test
    fun confirm_expired() {
        val s = state(expires = now - 1)
        val d = VerificationPolicy.confirm(s, "123456", now) { _, _ -> true }
        assertThat(d).isEqualTo(VerificationPolicy.ConfirmDecision.Expired)
    }

    @Test
    fun confirm_lockedOut() {
        val s = state(attempts = 0)
        val d = VerificationPolicy.confirm(s, "123456", now) { _, _ -> true }
        assertThat(d).isEqualTo(VerificationPolicy.ConfirmDecision.LockedOut)
    }

    @Test
    fun confirm_matchAndMismatch() {
        val s = state()
        assertThat(
            VerificationPolicy.confirm(s, "123456", now) { code, dig ->
                code == "123456" && dig.contentEquals(digest)
            },
        ).isEqualTo(VerificationPolicy.ConfirmDecision.Match)

        assertThat(
            VerificationPolicy.confirm(s, "000000", now) { _, _ -> false },
        ).isEqualTo(VerificationPolicy.ConfirmDecision.Mismatch)
    }

    @Test
    fun confirm_noPending() {
        assertThat(
            VerificationPolicy.confirm(null, "123456", now) { _, _ -> true },
        ).isEqualTo(VerificationPolicy.ConfirmDecision.NoPending)
    }

    @Test
    fun isWellFormedCode_sixDigits() {
        assertThat(VerificationPolicy.isWellFormedCode("123456")).isTrue()
        assertThat(VerificationPolicy.isWellFormedCode("12345")).isFalse()
        assertThat(VerificationPolicy.isWellFormedCode("12345a")).isFalse()
    }

    @Test
    fun attemptsAfterFailure_decrements() {
        assertThat(VerificationPolicy.attemptsAfterFailure(state(attempts = 3))).isEqualTo(2)
        assertThat(VerificationPolicy.attemptsAfterFailure(state(attempts = 0))).isEqualTo(0)
    }

    @Test
    fun expiryIs10Minutes() {
        assertThat(VerificationPolicy.expiryTimestamp(now) - now)
            .isEqualTo(10L * 60 * 1000)
    }
}

package com.johnc4rl0.smsforwarder.domain

import com.johnc4rl0.smsforwarder.domain.model.DestinationVerificationState

/**
 * Pure destination-verification rules:
 * - Six-digit codes expire after 10 minutes
 * - At most 5 entry attempts per code
 * - At most 3 sends per rolling hour; resend invalidates previous code
 */
object VerificationPolicy {
    const val CODE_LENGTH: Int = 6
    const val CODE_EXPIRY_MILLIS: Long = 10L * 60L * 1000L
    const val MAX_ATTEMPTS: Int = 5
    const val MAX_SENDS_PER_ROLLING_HOUR: Int = 3
    const val ROLLING_HOUR_MILLIS: Long = 60L * 60L * 1000L

    sealed class SendDecision {
        data object Allow : SendDecision()
        data object RateLimited : SendDecision()
    }

    sealed class ConfirmDecision {
        data object Match : ConfirmDecision()
        data object Mismatch : ConfirmDecision()
        data object Expired : ConfirmDecision()
        data object LockedOut : ConfirmDecision()
        data object NoPending : ConfirmDecision()
    }

    /** Whether a new verification SMS may be sent at [nowMillis]. */
    fun canSend(
        state: DestinationVerificationState?,
        nowMillis: Long,
    ): SendDecision {
        if (state == null) return SendDecision.Allow
        // Count sends in the rolling hour relative to last send bookkeeping.
        // [sendsInRollingHour] is maintained by the coordinator; if last send is outside
        // the hour window, treat as zero.
        val sends = if (nowMillis - state.lastSendAtMillis >= ROLLING_HOUR_MILLIS) {
            0
        } else {
            state.sendsInRollingHour
        }
        return if (sends >= MAX_SENDS_PER_ROLLING_HOUR) {
            SendDecision.RateLimited
        } else {
            SendDecision.Allow
        }
    }

    /**
     * Next [sendsInRollingHour] value after a successful send.
     * Resets to 1 when the previous send falls outside the rolling hour.
     */
    fun nextSendsInRollingHour(
        state: DestinationVerificationState?,
        nowMillis: Long,
    ): Int {
        if (state == null) return 1
        return if (nowMillis - state.lastSendAtMillis >= ROLLING_HOUR_MILLIS) {
            1
        } else {
            state.sendsInRollingHour + 1
        }
    }

    fun expiryTimestamp(sentAtMillis: Long): Long = sentAtMillis + CODE_EXPIRY_MILLIS

    /**
     * Confirm a user-entered [code] against [state] using [codesEqual] for constant-time
     * digest comparison (HMAC digest of the code vs stored digest).
     *
     * @param codesEqual returns true when the entered code matches the protected digest
     */
    fun confirm(
        state: DestinationVerificationState?,
        code: String,
        nowMillis: Long,
        codesEqual: (enteredCode: String, storedDigest: ByteArray) -> Boolean,
    ): ConfirmDecision {
        if (state == null) return ConfirmDecision.NoPending
        if (state.attemptsRemaining <= 0) return ConfirmDecision.LockedOut
        if (nowMillis >= state.expiresAtMillis) return ConfirmDecision.Expired
        if (!isWellFormedCode(code)) return ConfirmDecision.Mismatch
        return if (codesEqual(code.trim(), state.codeDigest)) {
            ConfirmDecision.Match
        } else {
            ConfirmDecision.Mismatch
        }
    }

    fun isWellFormedCode(code: String?): Boolean {
        if (code.isNullOrBlank()) return false
        val trimmed = code.trim()
        return trimmed.length == CODE_LENGTH && trimmed.all { it.isDigit() }
    }

    /** Attempts remaining after a failed confirm (floor at 0). */
    fun attemptsAfterFailure(state: DestinationVerificationState): Int =
        (state.attemptsRemaining - 1).coerceAtLeast(0)
}

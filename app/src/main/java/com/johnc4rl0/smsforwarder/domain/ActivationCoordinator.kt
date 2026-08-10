package com.johnc4rl0.smsforwarder.domain

import com.johnc4rl0.smsforwarder.domain.model.ForwardingConfig
import com.johnc4rl0.smsforwarder.domain.model.LineSelection
import com.johnc4rl0.smsforwarder.domain.model.PauseReason
import kotlinx.coroutines.flow.Flow

/**
 * Orchestrates onboarding and enablement: disclosure, permissions, line selection,
 * destination verification, device authentication, and pause/re-enable.
 *
 * UI and telephony layers call into this; pure validation helpers may live in domain.
 */
interface ActivationCoordinator {
    fun observeConfig(): Flow<ForwardingConfig>

    suspend fun acceptDisclosure()

    suspend fun setSourceLine(selection: LineSelection)

    suspend fun setOutboundLine(selection: LineSelection)

    /**
     * Set destination E.164. Clears verification. Rejects local/source/outbound numbers.
     * @return null on success, or a user-facing error key/message on failure.
     */
    suspend fun setDestination(e164: String): String?

    /**
     * Restore a previously captured [ForwardingConfig] snapshot. Used when the user
     * discards an in-progress destination change before verification completes, so the
     * prior (verified) destination is not left overwritten by an unverified one.
     */
    suspend fun restoreConfig(snapshot: ForwardingConfig)

    /** Send a new verification code via outbound SIM (rate-limited). */
    suspend fun sendVerificationCode(): VerificationSendResult

    /** Check user-entered code against protected digest. */
    suspend fun confirmVerificationCode(code: String): VerificationConfirmResult

    /**
     * Authenticate then enable forwarding. Fails closed if health checks fail.
     * [authenticate] is supplied by the UI (biometric / device credential prompt).
     */
    suspend fun enable(authenticate: suspend () -> DeviceAuthResult): EnableResult

    /** Pause immediately; never requires authentication. */
    suspend fun pauseManual()

    /**
     * Re-enable after safety pause or config change; requires authentication and healthy state.
     */
    suspend fun reEnable(authenticate: suspend () -> DeviceAuthResult): EnableResult

    /** Apply a safety/health pause with [reason]. */
    suspend fun safetyPause(reason: PauseReason)
}

sealed class VerificationSendResult {
    data object Sent : VerificationSendResult()
    data object RateLimited : VerificationSendResult()
    data object DestinationMissing : VerificationSendResult()
    data object OutboundUnavailable : VerificationSendResult()
    data class Failed(val message: String? = null) : VerificationSendResult()
}

sealed class VerificationConfirmResult {
    data object Verified : VerificationConfirmResult()
    data object Expired : VerificationConfirmResult()
    data object Mismatch : VerificationConfirmResult()
    data object LockedOut : VerificationConfirmResult()
    data object NoPending : VerificationConfirmResult()
}

sealed class EnableResult {
    data object Enabled : EnableResult()
    data object AuthFailed : EnableResult()
    data object AuthCancelled : EnableResult()
    data class Blocked(val reason: PauseReason) : EnableResult()
}

/** Result of a device biometric / credential prompt (UI → domain). */
sealed class DeviceAuthResult {
    data object Success : DeviceAuthResult()
    data object Cancelled : DeviceAuthResult()
    data object Failed : DeviceAuthResult()
}

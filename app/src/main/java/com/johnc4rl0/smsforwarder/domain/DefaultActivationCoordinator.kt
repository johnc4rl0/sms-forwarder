package com.johnc4rl0.smsforwarder.domain

import com.johnc4rl0.smsforwarder.domain.model.DestinationVerificationState
import com.johnc4rl0.smsforwarder.domain.model.ForwardingConfig
import com.johnc4rl0.smsforwarder.domain.model.LineSelection
import com.johnc4rl0.smsforwarder.domain.model.LineValidation
import com.johnc4rl0.smsforwarder.domain.model.OperationalState
import com.johnc4rl0.smsforwarder.domain.model.PauseReason
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Production [ActivationCoordinator]: disclosure, line/destination setup, verification,
 * authenticated enablement, and fail-closed safety pauses.
 *
 * Pure policy uses [VerificationPolicy], [E164], [QuotaPolicy]; side effects go through
 * injected repositories, catalog, HMAC, SMS sender, and health checks.
 */
class DefaultActivationCoordinator(
    private val configRepository: ConfigRepository,
    private val forwardJobRepository: ForwardJobRepository,
    private val subscriptionCatalog: SubscriptionCatalog,
    /** Keystore (or test) HMAC over code / digest preimage bytes. */
    private val mac: (ByteArray) -> ByteArray,
    /**
     * Send a verification SMS via the chosen outbound subscription.
     * Returns true when the carrier interface accepted the send request.
     */
    private val sendVerificationSms: suspend (
        outboundSubscriptionId: Int,
        destinationE164: String,
        code: String,
    ) -> Boolean,
    private val permissionsOk: () -> Boolean,
    private val notificationsOk: () -> Boolean,
    /**
     * Private sensitive-SMS / OTP privilege (appops). Must be true on OS levels that
     * require [com.johnc4rl0.smsforwarder.telephony.SensitiveSmsPrivilege].
     */
    private val sensitiveSmsPrivilegeOk: () -> Boolean = { true },
    /** True when encryption keys are usable (Keystore). */
    private val encryptionAvailable: () -> Boolean = { true },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val randomCode: () -> String = { generateSixDigitCode() },
    private val submissionGate: ForwardSubmissionGate = ForwardSubmissionGate(),
    /** Invoked after operational state transitions so UI/notification can refresh. */
    private val onConfigChanged: (ForwardingConfig) -> Unit = {},
) : ActivationCoordinator {

    private val sendCodeMutex = Mutex()

    override fun observeConfig(): Flow<ForwardingConfig> = configRepository.observeConfig()

    override suspend fun acceptDisclosure() = submissionGate.withLock {
        val next = configRepository.updateAndGet { it.copy(disclosureAccepted = true) }
        onConfigChanged(next)
    }

    override suspend fun setSourceLine(selection: LineSelection) = submissionGate.withLock {
        pauseAndPurgeForLineChange()
        val next = configRepository.updateAndGet { cfg ->
            cfg.copy(
                source = selection,
                configRevision = cfg.configRevision + 1,
                // Stay in onboarding until fully enabled; only pause if already past NotConfigured.
                operationalState = pausedStateAfterConfigChange(cfg.operationalState),
                pauseReason = pauseReasonAfterConfigChange(cfg.operationalState),
            )
        }
        onConfigChanged(next)
    }

    override suspend fun setOutboundLine(selection: LineSelection) = submissionGate.withLock {
        pauseAndPurgeForLineChange()
        val next = configRepository.updateAndGet { cfg ->
            cfg.copy(
                outbound = selection,
                configRevision = cfg.configRevision + 1,
                operationalState = pausedStateAfterConfigChange(cfg.operationalState),
                pauseReason = pauseReasonAfterConfigChange(cfg.operationalState),
            )
        }
        onConfigChanged(next)
    }

    override suspend fun setDestination(e164: String): String? = submissionGate.withLock {
        sendCodeMutex.withLock sendCode@{
        val normalized = E164.normalize(e164)
            ?: return@sendCode "Enter a valid E.164 number (e.g. +15551234567)."

        val config = configRepository.getConfig()
        val knownLocals = collectKnownLocalNumbers(config)
        if (E164.isLocalNumber(normalized, knownLocals)) {
            return@sendCode "Destination cannot be one of this device's lines."
        }

        // Destination change clears pending code verification; drop any unsent work stamped for the old dest.
        try {
            forwardJobRepository.purgeUnsentJobs()
        } catch (_: Exception) {
            // best-effort
        }
        val currentVerification = configRepository.getVerificationState()
        if (currentVerification != null) {
            configRepository.setVerificationState(
                currentVerification.copy(
                    destinationE164 = normalized,
                    codeDigest = ByteArray(0),
                    expiresAtMillis = 0L,
                    attemptsRemaining = 0,
                ),
            )
        } else {
            configRepository.setVerificationState(null)
        }
        val next = configRepository.updateAndGet { cfg ->
            cfg.copy(
                destinationE164 = normalized,
                destinationVerified = false,
                configRevision = cfg.configRevision + 1,
                operationalState = pausedStateAfterConfigChange(cfg.operationalState),
                pauseReason = pauseReasonAfterConfigChange(cfg.operationalState),
            )
        }
        onConfigChanged(next)
        null
        }
    }

    override suspend fun restoreConfig(snapshot: ForwardingConfig) = submissionGate.withLock {
        val currentVerification = configRepository.getVerificationState()
        if (currentVerification != null && snapshot.destinationE164 != null) {
            configRepository.setVerificationState(
                currentVerification.copy(
                    destinationE164 = snapshot.destinationE164,
                    codeDigest = ByteArray(0),
                    expiresAtMillis = 0L,
                    attemptsRemaining = 0,
                ),
            )
        } else {
            configRepository.setVerificationState(null)
        }
        val next = configRepository.updateAndGet { cfg ->
            cfg.copy(
                destinationE164 = snapshot.destinationE164,
                destinationVerified = snapshot.destinationVerified,
                configRevision = cfg.configRevision + 1,
                operationalState = pausedStateAfterConfigChange(cfg.operationalState),
                pauseReason = pauseReasonAfterConfigChange(cfg.operationalState),
            )
        }
        onConfigChanged(next)
    }

    override suspend fun sendVerificationCode(): VerificationSendResult = submissionGate.withLock {
        sendCodeMutex.withLock sendCode@{
        val config = configRepository.getConfig()
        val destination = config.destinationE164
        if (destination.isNullOrBlank() || !E164.isValid(destination)) {
            return@sendCode VerificationSendResult.DestinationMissing
        }
        val outbound = config.outbound
            ?: return@sendCode VerificationSendResult.OutboundUnavailable
        when (subscriptionCatalog.validate(outbound)) {
            is LineValidation.Invalid -> return@sendCode VerificationSendResult.OutboundUnavailable
            LineValidation.Valid -> Unit
        }
        if (!permissionsOk()) {
            return@sendCode VerificationSendResult.Failed("Required permissions are missing.")
        }

        val now = clock()
        val previous = configRepository.getVerificationState()

        when (VerificationPolicy.canSend(previous, now)) {
            VerificationPolicy.SendDecision.RateLimited -> return@sendCode VerificationSendResult.RateLimited
            VerificationPolicy.SendDecision.Allow -> Unit
        }

        val code = randomCode()
        if (!VerificationPolicy.isWellFormedCode(code)) {
            return@sendCode VerificationSendResult.Failed("Could not generate verification code.")
        }

        val sent = try {
            sendVerificationSms(outbound.subscriptionId, destination, code)
        } catch (_: Exception) {
            false
        }
        if (!sent) {
            return@sendCode VerificationSendResult.Failed("Failed to send verification SMS.")
        }

        // Resend invalidates previous code; store HMAC of destination-bound preimage only.
        val digest = mac(verificationMacPreimage(destination, code))
        val state = DestinationVerificationState(
            destinationE164 = destination,
            codeDigest = digest,
            expiresAtMillis = VerificationPolicy.expiryTimestamp(now),
            attemptsRemaining = VerificationPolicy.MAX_ATTEMPTS,
            sendsInRollingHour = VerificationPolicy.nextSendsInRollingHour(previous, now),
            lastSendAtMillis = now,
        )
        configRepository.setVerificationState(state)
        // Sending a new code means destination is not yet confirmed for this code.
        val next = configRepository.updateAndGet {
            it.copy(destinationVerified = false)
        }
        onConfigChanged(next)
        VerificationSendResult.Sent
        }
    }

    override suspend fun confirmVerificationCode(code: String): VerificationConfirmResult = submissionGate.withLock {
        sendCodeMutex.withLock confirmCode@{
        val now = clock()
        val state = configRepository.getVerificationState()
        val currentConfig = configRepository.getConfig()
        if (state != null && currentConfig.destinationE164 != state.destinationE164) {
            return@confirmCode VerificationConfirmResult.NoPending
        }
        val decision = VerificationPolicy.confirm(
            state = state,
            code = code,
            nowMillis = now,
            codesEqual = { entered, storedDigest ->
                val dest = state?.destinationE164.orEmpty()
                val computed = mac(verificationMacPreimage(dest, entered))
                MessageDigest.isEqual(computed, storedDigest)
            },
        )

        when (decision) {
            VerificationPolicy.ConfirmDecision.NoPending -> VerificationConfirmResult.NoPending
            VerificationPolicy.ConfirmDecision.Expired -> {
                configRepository.setVerificationState(null)
                VerificationConfirmResult.Expired
            }
            VerificationPolicy.ConfirmDecision.LockedOut -> VerificationConfirmResult.LockedOut
            VerificationPolicy.ConfirmDecision.Mismatch -> {
                if (state != null) {
                    val remaining = VerificationPolicy.attemptsAfterFailure(state)
                    if (remaining <= 0) {
                        configRepository.setVerificationState(
                            state.copy(attemptsRemaining = 0),
                        )
                        VerificationConfirmResult.LockedOut
                    } else {
                        configRepository.setVerificationState(
                            state.copy(attemptsRemaining = remaining),
                        )
                        VerificationConfirmResult.Mismatch
                    }
                } else {
                    VerificationConfirmResult.Mismatch
                }
            }
            VerificationPolicy.ConfirmDecision.Match -> {
                configRepository.setVerificationState(null)
                val next = configRepository.updateAndGet { cfg ->
                    if (cfg.destinationE164 == state?.destinationE164) {
                        cfg.copy(destinationVerified = true)
                    } else {
                        cfg
                    }
                }
                if (next.destinationVerified) {
                    onConfigChanged(next)
                    VerificationConfirmResult.Verified
                } else {
                    VerificationConfirmResult.NoPending
                }
            }
        }
        }
    }

    override suspend fun enable(authenticate: suspend () -> DeviceAuthResult): EnableResult {
        val blocked = readinessBlocker()
        if (blocked != null) return EnableResult.Blocked(blocked)

        when (val auth = runAuth(authenticate)) {
            DeviceAuthResult.Success -> Unit
            DeviceAuthResult.Cancelled -> return EnableResult.AuthCancelled
            DeviceAuthResult.Failed -> return EnableResult.AuthFailed
        }

        // Re-check and commit the enable transition atomically with the send gate.
        return submissionGate.withLock {
            val blockedAfter = readinessBlocker()
            if (blockedAfter != null) return@withLock EnableResult.Blocked(blockedAfter)

            val next = configRepository.updateAndGet {
                it.copy(
                    operationalState = OperationalState.Enabled,
                    pauseReason = null,
                )
            }
            onConfigChanged(next)
            EnableResult.Enabled
        }
    }

    override suspend fun pauseManual() = submissionGate.withLock {
        try {
            // Stop unsent work immediately and clear residual terminal ciphertext.
            forwardJobRepository.purgeUnsentJobs()
            forwardJobRepository.purgeSensitivePayloads()
        } catch (_: Exception) {
            // still pause
        }
        val next = configRepository.updateAndGet {
            it.copy(
                operationalState = OperationalState.ManuallyPaused,
                pauseReason = PauseReason.MANUAL,
            )
        }
        onConfigChanged(next)
    }

    override suspend fun reEnable(authenticate: suspend () -> DeviceAuthResult): EnableResult {
        val blocked = readinessBlocker(checkQuota = true)
        if (blocked != null) return EnableResult.Blocked(blocked)

        when (val auth = runAuth(authenticate)) {
            DeviceAuthResult.Success -> Unit
            DeviceAuthResult.Cancelled -> return EnableResult.AuthCancelled
            DeviceAuthResult.Failed -> return EnableResult.AuthFailed
        }

        return submissionGate.withLock {
            val blockedAfter = readinessBlocker(checkQuota = true)
            if (blockedAfter != null) return@withLock EnableResult.Blocked(blockedAfter)

            val next = configRepository.updateAndGet {
                it.copy(
                    operationalState = OperationalState.Enabled,
                    pauseReason = null,
                )
            }
            onConfigChanged(next)
            EnableResult.Enabled
        }
    }

    override suspend fun safetyPause(reason: PauseReason) = submissionGate.withLock {
        try {
            // Spec: purge sender/body ciphertext on safety pause; drop unsent so WorkManager
            // cannot submit after pause (worker also rechecks Enabled).
            forwardJobRepository.purgeUnsentJobs()
            forwardJobRepository.purgeSensitivePayloads()
        } catch (_: Exception) {
            // still pause
        }
        val next = configRepository.updateAndGet {
            it.copy(
                operationalState = OperationalState.SafetyPaused(reason),
                pauseReason = reason,
            )
        }
        onConfigChanged(next)
    }

    override suspend fun repairSourceLine(
        selection: LineSelection,
        authenticate: suspend () -> DeviceAuthResult,
    ): RepairResult = repairLine(isSource = true, selection = selection, authenticate = authenticate)

    override suspend fun repairOutboundLine(
        selection: LineSelection,
        authenticate: suspend () -> DeviceAuthResult,
    ): RepairResult = repairLine(isSource = false, selection = selection, authenticate = authenticate)

    private suspend fun repairLine(
        isSource: Boolean,
        selection: LineSelection,
        authenticate: suspend () -> DeviceAuthResult,
    ): RepairResult {
        val initialLines = subscriptionCatalog.listActiveLines()
        val initialLine = initialLines.find { it.subscriptionId == selection.subscriptionId }
            ?: return RepairResult.LineNotFound

        when (val auth = runAuth(authenticate)) {
            DeviceAuthResult.Success -> Unit
            DeviceAuthResult.Cancelled -> return RepairResult.AuthCancelled
            DeviceAuthResult.Failed -> return RepairResult.AuthFailed
        }

        return submissionGate.withLock {
            val freshLines = subscriptionCatalog.listActiveLines()
            val freshLine = freshLines.find { it.subscriptionId == selection.subscriptionId }
                ?: return@withLock RepairResult.LineNotFound

            val driftComparison = SubscriptionIdentity.compare(initialLine.identityToken, freshLine.identityToken)
            if (driftComparison != IdentityComparisonResult.Same) {
                return@withLock RepairResult.CatalogDrift
            }

            val currentConfig = configRepository.getConfig()
            val repairedLine = LineSelection(
                subscriptionId = freshLine.subscriptionId,
                slotIndex = freshLine.slotIndex,
                carrierDisplayName = freshLine.carrierDisplayName,
                reportedNumberE164 = freshLine.reportedNumberE164,
                manualNumberE164 = selection.manualNumberE164
                    ?: (if (isSource) currentConfig.source else currentConfig.outbound)
                        ?.takeIf { it.subscriptionId == freshLine.subscriptionId }?.manualNumberE164,
                identityToken = freshLine.identityToken,
            )

            val candidateConfig = if (isSource) {
                currentConfig.copy(source = repairedLine)
            } else {
                currentConfig.copy(outbound = repairedLine)
            }

            val dest = candidateConfig.destinationE164
            if (!dest.isNullOrBlank()) {
                val locals = collectKnownLocalNumbers(candidateConfig)
                if (E164.isLocalNumber(dest, locals)) {
                    return@withLock RepairResult.DestinationConflict(
                        "Destination conflicts with one of this device's lines.",
                    )
                }
            }

            try {
                forwardJobRepository.purgeUnsentJobs()
                forwardJobRepository.purgeSensitivePayloads()
            } catch (_: Exception) {
                // best-effort
            }

            val next = configRepository.updateAndGet { cfg ->
                if (isSource) {
                    cfg.copy(
                        source = repairedLine,
                        configRevision = cfg.configRevision + 1,
                        operationalState = pausedStateAfterConfigChange(cfg.operationalState),
                        pauseReason = pauseReasonAfterConfigChange(cfg.operationalState),
                    )
                } else {
                    cfg.copy(
                        outbound = repairedLine,
                        configRevision = cfg.configRevision + 1,
                        operationalState = pausedStateAfterConfigChange(cfg.operationalState),
                        pauseReason = pauseReasonAfterConfigChange(cfg.operationalState),
                    )
                }
            }
            onConfigChanged(next)
            RepairResult.Success
        }
    }

    private suspend fun runAuth(authenticate: suspend () -> DeviceAuthResult): DeviceAuthResult =
        try {
            authenticate()
        } catch (_: Exception) {
            DeviceAuthResult.Failed
        }

    // --- helpers ---

    private suspend fun pauseAndPurgeForLineChange() {
        val config = configRepository.getConfig()
        if (config.operationalState is OperationalState.Enabled ||
            config.operationalState is OperationalState.ManuallyPaused ||
            config.operationalState is OperationalState.SafetyPaused ||
            config.operationalState is OperationalState.Unhealthy
        ) {
            try {
                forwardJobRepository.purgeUnsentJobs()
                forwardJobRepository.purgeSensitivePayloads()
            } catch (_: Exception) {
                // best-effort
            }
        }
    }

    /**
     * After a line/destination change: if already operating (or paused from prior enable),
     * move to [OperationalState.ManuallyPaused]; leave [OperationalState.NotConfigured] alone
     * so onboarding continues.
     */
    private fun pausedStateAfterConfigChange(current: OperationalState): OperationalState =
        when (current) {
            is OperationalState.NotConfigured -> OperationalState.NotConfigured
            else -> OperationalState.ManuallyPaused
        }

    private fun pauseReasonAfterConfigChange(current: OperationalState): PauseReason? =
        when (current) {
            is OperationalState.NotConfigured -> null
            else -> PauseReason.MANUAL
        }

    private fun collectKnownLocalNumbers(config: ForwardingConfig): List<String?> {
        val fromConfig = listOf(
            config.source?.effectiveNumberE164,
            config.source?.reportedNumberE164,
            config.source?.manualNumberE164,
            config.outbound?.effectiveNumberE164,
            config.outbound?.reportedNumberE164,
            config.outbound?.manualNumberE164,
        )
        val fromCatalog = try {
            subscriptionCatalog.listActiveLines().flatMap { line ->
                listOf(line.reportedNumberE164)
            }
        } catch (_: Exception) {
            emptyList()
        }
        return fromConfig + fromCatalog
    }

    /**
     * @return null when ready to enable; otherwise the blocking [PauseReason].
     */
    private suspend fun readinessBlocker(checkQuota: Boolean = false): PauseReason? {
        if (!encryptionAvailable()) return PauseReason.ENCRYPTION_UNAVAILABLE
        if (!permissionsOk()) return PauseReason.PERMISSIONS_REVOKED
        if (!notificationsOk()) return PauseReason.NOTIFICATIONS_DISABLED
        if (!sensitiveSmsPrivilegeOk()) return PauseReason.SENSITIVE_SMS_PRIVILEGE_MISSING

        val config = configRepository.getConfig()
        if (!config.disclosureAccepted) return PauseReason.CONFIGURATION_INCOMPLETE
        val source = config.source ?: return PauseReason.CONFIGURATION_INCOMPLETE
        val outbound = config.outbound ?: return PauseReason.CONFIGURATION_INCOMPLETE
        if (config.destinationE164.isNullOrBlank() || !config.destinationVerified) {
            return PauseReason.CONFIGURATION_INCOMPLETE
        }

        when (val v = subscriptionCatalog.validate(source)) {
            is LineValidation.Invalid -> return when (v.reason) {
                PauseReason.SOURCE_IDENTITY_MISMATCH -> PauseReason.SOURCE_IDENTITY_MISMATCH
                PauseReason.SOURCE_IDENTITY_UNAVAILABLE -> PauseReason.SOURCE_IDENTITY_UNAVAILABLE
                else -> PauseReason.SOURCE_SUBSCRIPTION_INACTIVE
            }
            LineValidation.Valid -> Unit
        }
        when (val v = subscriptionCatalog.validate(outbound)) {
            is LineValidation.Invalid -> return when (v.reason) {
                PauseReason.SOURCE_IDENTITY_MISMATCH -> PauseReason.OUTBOUND_IDENTITY_MISMATCH
                PauseReason.SOURCE_IDENTITY_UNAVAILABLE -> PauseReason.OUTBOUND_IDENTITY_UNAVAILABLE
                else -> PauseReason.OUTBOUND_SUBSCRIPTION_INACTIVE
            }
            LineValidation.Valid -> Unit
        }

        if (checkQuota) {
            val now = clock()
            val quota = try {
                forwardJobRepository.currentQuota(now)
            } catch (_: Exception) {
                return PauseReason.CONFIGURATION_INCOMPLETE
            }
            if (!QuotaPolicy.hasAvailableCapacity(
                    sourceMessagesUsed = quota.sourceMessagesUsed,
                    outboundSegmentsUsed = quota.outboundSegmentsUsed,
                )
            ) {
                return if (quota.sourceMessagesUsed >= QuotaPolicy.DEFAULT_SOURCE_MESSAGE_LIMIT) {
                    PauseReason.QUOTA_SOURCE_MESSAGES
                } else {
                    PauseReason.QUOTA_OUTBOUND_SEGMENTS
                }
            }
        }
        return null
    }

    private suspend fun ConfigRepository.updateAndGet(
        transform: (ForwardingConfig) -> ForwardingConfig,
    ): ForwardingConfig {
        updateConfig(transform)
        return getConfig()
    }

    companion object {
        private val secureRandom = SecureRandom()

        fun generateSixDigitCode(): String {
            val n = secureRandom.nextInt(1_000_000)
            return n.toString().padStart(VerificationPolicy.CODE_LENGTH, '0')
        }

        /** Body text for the outbound verification SMS (no secrets beyond the code). */
        fun verificationMessageBody(code: String): String =
            "SMS Forwarder verification code: $code"

        /**
         * HMAC preimage for destination verification: binds code to destination E.164
         * so digests cannot be reused across destinations.
         */
        fun verificationMacPreimage(destinationE164: String, code: String): ByteArray {
            val dest = destinationE164.toByteArray(Charsets.UTF_8)
            val codeBytes = code.toByteArray(Charsets.UTF_8)
            // destination || 0x00 || code
            return ByteArray(dest.size + 1 + codeBytes.size).also { out ->
                System.arraycopy(dest, 0, out, 0, dest.size)
                out[dest.size] = 0
                System.arraycopy(codeBytes, 0, out, dest.size + 1, codeBytes.size)
            }
        }
    }
}

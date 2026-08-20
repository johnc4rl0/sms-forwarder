package com.johnc4rl0.smsforwarder.domain.model

/**
 * Core domain models for SMS Forwarder.
 *
 * Pure Kotlin — no Android dependencies. Parallel agents must keep policy logic here
 * free of framework types so unit tests can exercise decisions without Robolectric.
 */

/** Identifies a SIM/eSIM line the user selected for inbound or outbound routing. */
data class LineSelection(
    val subscriptionId: Int,
    val slotIndex: Int?,
    val carrierDisplayName: String?,
    /** OS-reported number when available (may be inaccurate). */
    val reportedNumberE164: String?,
    /** User-entered E.164 when OS number is missing or overridden. */
    val manualNumberE164: String?,
    /**
     * Stable identity snapshot used to detect SIM swap / profile change.
     * Implementations choose the concrete fields (ICCID hash, etc.).
     */
    val identityToken: String?,
) {
    /** Prefer manual override, else OS-reported number. */
    val effectiveNumberE164: String?
        get() = manualNumberE164?.takeIf { it.isNotBlank() }
            ?: reportedNumberE164?.takeIf { it.isNotBlank() }
}

/** Snapshot of an active subscription as listed by [com.johnc4rl0.smsforwarder.domain.SubscriptionCatalog]. */
data class ActiveLine(
    val subscriptionId: Int,
    val slotIndex: Int?,
    val carrierDisplayName: String?,
    val reportedNumberE164: String?,
    val isEmbedded: Boolean,
    val identityToken: String?,
)

/** Durable user configuration for forwarding. */
data class ForwardingConfig(
    val disclosureAccepted: Boolean = false,
    val source: LineSelection? = null,
    val outbound: LineSelection? = null,
    val destinationE164: String? = null,
    val destinationVerified: Boolean = false,
    /** Monotonic revision bumped on any line/destination change. */
    val configRevision: Long = 0L,
    val operationalState: OperationalState = OperationalState.NotConfigured,
    val pauseReason: PauseReason? = null,
)

/** High-level app operational mode shown on the dashboard / notification. */
sealed class OperationalState {
    data object NotConfigured : OperationalState()
    data object Enabled : OperationalState()
    data object ManuallyPaused : OperationalState()
    data class SafetyPaused(val reason: PauseReason) : OperationalState()
    data class Unhealthy(val reason: PauseReason) : OperationalState()
}

/** Why forwarding was paused (safety or health). Manual pause uses [OperationalState.ManuallyPaused]. */
enum class PauseReason {
    MANUAL,
    QUOTA_SOURCE_MESSAGES,
    QUOTA_OUTBOUND_SEGMENTS,
    PERMISSIONS_REVOKED,
    NOTIFICATIONS_DISABLED,
    /** Private RECEIVE_SENSITIVE_NOTIFICATIONS / appops missing on OS that requires it. */
    SENSITIVE_SMS_PRIVILEGE_MISSING,
    SOURCE_SUBSCRIPTION_INACTIVE,
    OUTBOUND_SUBSCRIPTION_INACTIVE,
    SOURCE_IDENTITY_MISMATCH,
    OUTBOUND_IDENTITY_MISMATCH,
    SOURCE_IDENTITY_UNAVAILABLE,
    OUTBOUND_IDENTITY_UNAVAILABLE,
    MISSING_INBOUND_SUBSCRIPTION_ID,
    ENCRYPTION_UNAVAILABLE,
    HIBERNATION_RISK,
    CONFIGURATION_INCOMPLETE,
}

/** Parsed inbound SMS delivered by Android (reconstructed multipart body). */
data class InboundSms(
    val sender: String?,
    val body: String,
    /** Subscription that received the SMS; null/invalid → fail closed. */
    val subscriptionId: Int?,
    val serviceTimestampMillis: Long?,
    val receivedAtMillis: Long,
    /** Raw PDU bytes used for dedup fingerprint; never log. */
    val rawPdus: List<ByteArray> = emptyList(),
)

/** Job lifecycle states (spec). */
enum class ForwardState {
    QUEUED,
    SUBMITTING,
    SENT,
    RETRY_WAIT,
    FAILED,
    PARTIAL,
    UNKNOWN,
    PURGED,
}

/** Why an inbound SMS was not enqueued. */
enum class SkipReason {
    FORWARDING_NOT_ENABLED,
    DESTINATION_NOT_VERIFIED,
    PERMISSIONS_MISSING,
    NOTIFICATIONS_DISABLED,
    SENSITIVE_SMS_PRIVILEGE_MISSING,
    SOURCE_SUBSCRIPTION_INACTIVE,
    OUTBOUND_SUBSCRIPTION_INACTIVE,
    IDENTITY_MISMATCH,
    IDENTITY_UNAVAILABLE,
    WRONG_SOURCE_SUBSCRIPTION,
    MISSING_SUBSCRIPTION_ID,
    LOOP_MARKER,
    SENDER_IS_DESTINATION,
    DUPLICATE,
    QUOTA_EXCEEDED,
    CONFIG_REVISION_STALE,
    BODY_EMPTY,
    NOT_CONFIGURED,
}

/** Durable forward work item (plaintext fields encrypted at rest by data layer). */
data class ForwardJob(
    val id: String,
    val state: ForwardState,
    val configRevision: Long,
    val sourceSubscriptionId: Int,
    val outboundSubscriptionId: Int,
    val sender: String?,
    val body: String,
    val destinationE164: String,
    val createdAtMillis: Long,
    val attemptCount: Int = 0,
    val segmentCount: Int? = null,
    val lastErrorCategory: ErrorCategory? = null,
    val nextAttemptAtMillis: Long? = null,
)

/** Broad error category retained after body purge (no secrets). */
enum class ErrorCategory {
    TRANSIENT_RADIO,
    NO_SERVICE,
    SIM_BUSY,
    SEND_FAIL_RETRY,
    POLICY_OR_GENERIC,
    PARTIAL_SEND,
    CALLBACK_TIMEOUT,
    EXPIRED_TTL,
    ENCRYPTION,
    UNKNOWN,
}

/** Outcome of [com.johnc4rl0.smsforwarder.domain.ForwardingEngine.accept]. */
sealed class ForwardDecision {
    data class Accept(val job: ForwardJob) : ForwardDecision()
    data class Skip(val reason: SkipReason) : ForwardDecision()
    data class PauseAndSkip(
        val pauseReason: PauseReason,
        val skipReason: SkipReason,
    ) : ForwardDecision()
}

/**
 * Runtime facts revalidated on every inbound message (built by telephony/app layers).
 * Policy code treats this as an immutable snapshot.
 */
data class RuntimeSnapshot(
    val config: ForwardingConfig,
    val permissionsOk: Boolean,
    val notificationsOk: Boolean,
    /**
     * Private sensitive-SMS / OTP privilege (appops). True when not required on this OS
     * or when the private grant is present. Fail closed when false.
     */
    val sensitiveSmsPrivilegeOk: Boolean = true,
    val activeSubscriptionIds: Set<Int>,
    /** Current identity tokens keyed by subscriptionId. */
    val currentIdentityTokens: Map<Int, String?>,
    val sourceMessagesUsedInWindow: Int,
    val outboundSegmentsUsedInWindow: Int,
    val sourceMessageLimit: Int = DEFAULT_SOURCE_MESSAGE_LIMIT,
    val outboundSegmentLimit: Int = DEFAULT_OUTBOUND_SEGMENT_LIMIT,
    val nowMillis: Long,
) {
    companion object {
        const val DEFAULT_SOURCE_MESSAGE_LIMIT: Int = 100
        const val DEFAULT_OUTBOUND_SEGMENT_LIMIT: Int = 500
    }
}

/** Metadata-only outcome retained for the last 50 dashboard rows (no body/sender). */
data class OutcomeMetadata(
    val jobId: String,
    val state: ForwardState,
    val finishedAtMillis: Long,
    val attemptCount: Int,
    val segmentCount: Int?,
    val errorCategory: ErrorCategory?,
)

/** Result of validating a [LineSelection] against live subscriptions. */
sealed class LineValidation {
    data object Valid : LineValidation()
    data class Invalid(val reason: PauseReason) : LineValidation()
}

/** Destination verification attempt bookkeeping (no plaintext code stored). */
data class DestinationVerificationState(
    val destinationE164: String,
    /** Protected comparison value (HMAC), not the code itself. */
    val codeDigest: ByteArray,
    val expiresAtMillis: Long,
    val attemptsRemaining: Int,
    val sendsInRollingHour: Int,
    val lastSendAtMillis: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DestinationVerificationState) return false
        return destinationE164 == other.destinationE164 &&
            codeDigest.contentEquals(other.codeDigest) &&
            expiresAtMillis == other.expiresAtMillis &&
            attemptsRemaining == other.attemptsRemaining &&
            sendsInRollingHour == other.sendsInRollingHour &&
            lastSendAtMillis == other.lastSendAtMillis
    }

    override fun hashCode(): Int {
        var result = destinationE164.hashCode()
        result = 31 * result + codeDigest.contentHashCode()
        result = 31 * result + expiresAtMillis.hashCode()
        result = 31 * result + attemptsRemaining
        result = 31 * result + sendsInRollingHour
        result = 31 * result + lastSendAtMillis.hashCode()
        return result
    }
}

/** Segment-level send callback aggregation input. */
data class PartSendResult(
    val jobId: String,
    val partIndex: Int,
    val partCount: Int,
    val resultCode: Int,
    val isTransient: Boolean,
    val receivedAtMillis: Long,
)

/** Result of submitting a job to the carrier interface. */
sealed class SubmitResult {
    data class Submitted(val segmentCount: Int) : SubmitResult()
    data class Failed(val category: ErrorCategory, val message: String? = null) : SubmitResult()
}

/** Rolling quota counters (atomic reservation happens in repository/data layer). */
data class QuotaSnapshot(
    val sourceMessagesUsed: Int,
    val outboundSegmentsUsed: Int,
    val windowStartMillis: Long,
)

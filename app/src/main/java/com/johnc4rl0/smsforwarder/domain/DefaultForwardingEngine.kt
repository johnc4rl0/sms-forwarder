package com.johnc4rl0.smsforwarder.domain

import com.johnc4rl0.smsforwarder.domain.model.ForwardDecision
import com.johnc4rl0.smsforwarder.domain.model.ForwardJob
import com.johnc4rl0.smsforwarder.domain.model.ForwardState
import com.johnc4rl0.smsforwarder.domain.model.ForwardingConfig
import com.johnc4rl0.smsforwarder.domain.model.InboundSms
import com.johnc4rl0.smsforwarder.domain.model.LineSelection
import com.johnc4rl0.smsforwarder.domain.model.OperationalState
import com.johnc4rl0.smsforwarder.domain.model.PauseReason
import com.johnc4rl0.smsforwarder.domain.model.RuntimeSnapshot
import com.johnc4rl0.smsforwarder.domain.model.SkipReason
import java.util.UUID

/**
 * Pure [ForwardingEngine] implementation of SPEC accept policy.
 *
 * @param mac keyed HMAC (or test double) over [DedupFingerprint] preimage bytes
 * @param isDuplicate returns true when [fingerprint] was already seen in the retention window;
 *   wired by telephony/data to [DedupStore.seenRecently] (blocking or precomputed)
 * @param newJobId factory for durable job ids (injectable for tests)
 * @param estimateSegments pure segment-count estimate for quota reservation
 */
class DefaultForwardingEngine(
    private val mac: (ByteArray) -> ByteArray,
    private val isDuplicate: (ByteArray) -> Boolean = { false },
    private val newJobId: () -> String = { UUID.randomUUID().toString() },
    private val estimateSegments: (String) -> Int = MessageFormatter::estimateSegmentCount,
) : ForwardingEngine {

    override fun accept(inbound: InboundSms, runtime: RuntimeSnapshot): ForwardDecision {
        val config = runtime.config

        // --- Configuration completeness ---
        val source = config.source
        val outbound = config.outbound
        val destination = config.destinationE164
        if (source == null || outbound == null || destination.isNullOrBlank()) {
            return if (config.operationalState is OperationalState.NotConfigured) {
                ForwardDecision.Skip(SkipReason.NOT_CONFIGURED)
            } else {
                ForwardDecision.PauseAndSkip(
                    pauseReason = PauseReason.CONFIGURATION_INCOMPLETE,
                    skipReason = SkipReason.NOT_CONFIGURED,
                )
            }
        }

        // --- Enablement & verification ---
        when (config.operationalState) {
            is OperationalState.Enabled -> Unit
            is OperationalState.NotConfigured ->
                return ForwardDecision.Skip(SkipReason.NOT_CONFIGURED)
            is OperationalState.ManuallyPaused,
            is OperationalState.SafetyPaused,
            is OperationalState.Unhealthy,
            -> return ForwardDecision.Skip(SkipReason.FORWARDING_NOT_ENABLED)
        }

        if (!config.destinationVerified) {
            return ForwardDecision.Skip(SkipReason.DESTINATION_NOT_VERIFIED)
        }

        // --- Runtime health (fail closed → pause) ---
        if (!runtime.permissionsOk) {
            return ForwardDecision.PauseAndSkip(
                pauseReason = PauseReason.PERMISSIONS_REVOKED,
                skipReason = SkipReason.PERMISSIONS_MISSING,
            )
        }
        if (!runtime.notificationsOk) {
            return ForwardDecision.PauseAndSkip(
                pauseReason = PauseReason.NOTIFICATIONS_DISABLED,
                skipReason = SkipReason.NOTIFICATIONS_DISABLED,
            )
        }
        if (!runtime.sensitiveSmsPrivilegeOk) {
            return ForwardDecision.PauseAndSkip(
                pauseReason = PauseReason.SENSITIVE_SMS_PRIVILEGE_MISSING,
                skipReason = SkipReason.SENSITIVE_SMS_PRIVILEGE_MISSING,
            )
        }

        // --- Subscription activity ---
        if (source.subscriptionId !in runtime.activeSubscriptionIds) {
            return ForwardDecision.PauseAndSkip(
                pauseReason = PauseReason.SOURCE_SUBSCRIPTION_INACTIVE,
                skipReason = SkipReason.SOURCE_SUBSCRIPTION_INACTIVE,
            )
        }
        if (outbound.subscriptionId !in runtime.activeSubscriptionIds) {
            return ForwardDecision.PauseAndSkip(
                pauseReason = PauseReason.OUTBOUND_SUBSCRIPTION_INACTIVE,
                skipReason = SkipReason.OUTBOUND_SUBSCRIPTION_INACTIVE,
            )
        }

        // --- Identity mismatch (stored vs currently reported) ---
        identityMismatch(source, runtime)?.let { reason ->
            return ForwardDecision.PauseAndSkip(
                pauseReason = reason,
                skipReason = SkipReason.IDENTITY_MISMATCH,
            )
        }
        identityMismatch(outbound, runtime)?.let { reason ->
            return ForwardDecision.PauseAndSkip(
                pauseReason = reason,
                skipReason = SkipReason.IDENTITY_MISMATCH,
            )
        }

        // --- Incoming subscription: missing/invalid → pause; wrong → skip ---
        val inboundSub = inbound.subscriptionId
        if (inboundSub == null || inboundSub < 0) {
            return ForwardDecision.PauseAndSkip(
                pauseReason = PauseReason.MISSING_INBOUND_SUBSCRIPTION_ID,
                skipReason = SkipReason.MISSING_SUBSCRIPTION_ID,
            )
        }
        if (inboundSub != source.subscriptionId) {
            // Do not silently remap — reject as wrong source without pause when id is known
            return ForwardDecision.Skip(SkipReason.WRONG_SOURCE_SUBSCRIPTION)
        }

        // --- Loop marker ---
        if (MessageFormatter.hasLoopMarker(inbound.body)) {
            return ForwardDecision.Skip(SkipReason.LOOP_MARKER)
        }

        // --- Destination-as-sender suppression ---
        if (E164.senderMatchesDestination(inbound.sender, destination)) {
            return ForwardDecision.Skip(SkipReason.SENDER_IS_DESTINATION)
        }

        // --- Empty body ---
        if (inbound.body.isEmpty()) {
            return ForwardDecision.Skip(SkipReason.BODY_EMPTY)
        }

        // --- Dedup fingerprint ---
        val fingerprint = DedupFingerprint.fingerprint(
            sourceSubscriptionId = source.subscriptionId,
            sender = inbound.sender,
            serviceTimestampMillis = inbound.serviceTimestampMillis ?: inbound.receivedAtMillis,
            rawPdus = inbound.rawPdus,
            mac = mac,
        )
        if (isDuplicate(fingerprint)) {
            return ForwardDecision.Skip(SkipReason.DUPLICATE)
        }

        // --- Build payload then quota (needs segment estimate) ---
        val payload = MessageFormatter.buildForwardPayload(
            sender = inbound.sender,
            source = source,
            originalBody = inbound.body,
        )
        val segments = estimateSegments(payload).coerceAtLeast(1)

        when (val quota = QuotaPolicy.checkAdmission(runtime, segments)) {
            is QuotaPolicy.QuotaDecision.Allowed -> Unit
            is QuotaPolicy.QuotaDecision.Exceeded -> {
                return ForwardDecision.PauseAndSkip(
                    pauseReason = quota.pauseReason,
                    skipReason = SkipReason.QUOTA_EXCEEDED,
                )
            }
        }

        val job = ForwardJob(
            id = newJobId(),
            state = ForwardState.QUEUED,
            configRevision = config.configRevision,
            sourceSubscriptionId = source.subscriptionId,
            outboundSubscriptionId = outbound.subscriptionId,
            sender = inbound.sender,
            body = payload,
            destinationE164 = destination,
            createdAtMillis = runtime.nowMillis,
            attemptCount = 0,
            segmentCount = segments,
            lastErrorCategory = null,
            nextAttemptAtMillis = null,
        )
        return ForwardDecision.Accept(job)
    }

    /**
     * When the selection stored an identity token and the live token differs (or is absent),
     * fail closed with the appropriate pause reason.
     */
    private fun identityMismatch(selection: LineSelection, runtime: RuntimeSnapshot): PauseReason? {
        val stored = selection.identityToken ?: return null
        val current = runtime.currentIdentityTokens[selection.subscriptionId]
        if (current == null || current != stored) {
            // Distinguish source vs outbound by matching against config
            val sourceId = runtime.config.source?.subscriptionId
            return if (selection.subscriptionId == sourceId) {
                PauseReason.SOURCE_IDENTITY_MISMATCH
            } else {
                PauseReason.OUTBOUND_IDENTITY_MISMATCH
            }
        }
        return null
    }

    companion object {
        /**
         * Helper for send-pipeline callers: true when a job's stamped revision still matches config.
         */
        fun isConfigRevisionCurrent(jobRevision: Long, config: ForwardingConfig): Boolean =
            jobRevision == config.configRevision
    }
}

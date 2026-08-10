package com.johnc4rl0.smsforwarder.domain

import com.google.common.truth.Truth.assertThat
import com.johnc4rl0.smsforwarder.domain.model.ForwardDecision
import com.johnc4rl0.smsforwarder.domain.model.ForwardState
import com.johnc4rl0.smsforwarder.domain.model.ForwardingConfig
import com.johnc4rl0.smsforwarder.domain.model.InboundSms
import com.johnc4rl0.smsforwarder.domain.model.LineSelection
import com.johnc4rl0.smsforwarder.domain.model.OperationalState
import com.johnc4rl0.smsforwarder.domain.model.PauseReason
import com.johnc4rl0.smsforwarder.domain.model.RuntimeSnapshot
import com.johnc4rl0.smsforwarder.domain.model.SkipReason
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class DefaultForwardingEngineTest {

    /** Deterministic stand-in for keyed HMAC (full digest, not a truncated preimage). */
    private val testMac: (ByteArray) -> ByteArray = { data ->
        MessageDigest.getInstance("SHA-256").digest(data)
    }

    private val source = LineSelection(
        subscriptionId = 10,
        slotIndex = 0,
        carrierDisplayName = "Src",
        reportedNumberE164 = "+15551111111",
        manualNumberE164 = null,
        identityToken = "src-token",
    )

    private val outbound = LineSelection(
        subscriptionId = 20,
        slotIndex = 1,
        carrierDisplayName = "Out",
        reportedNumberE164 = "+15552222222",
        manualNumberE164 = null,
        identityToken = "out-token",
    )

    private val destination = "+15553333333"
    private val now = 1_700_000_000_000L

    private val seen = mutableSetOf<String>()
    private val jobSeq = AtomicInteger(0)

    private fun engine(
        duplicateCheck: (ByteArray) -> Boolean = { fp ->
            val key = fp.toHex()
            key in seen
        },
    ) = DefaultForwardingEngine(
        mac = testMac,
        isDuplicate = duplicateCheck,
        newJobId = { "job-${jobSeq.incrementAndGet()}" },
        estimateSegments = { 1 },
    )

    private fun enabledConfig(revision: Long = 1L) = ForwardingConfig(
        disclosureAccepted = true,
        source = source,
        outbound = outbound,
        destinationE164 = destination,
        destinationVerified = true,
        configRevision = revision,
        operationalState = OperationalState.Enabled,
        pauseReason = null,
    )

    private fun snapshot(
        config: ForwardingConfig = enabledConfig(),
        permissionsOk: Boolean = true,
        notificationsOk: Boolean = true,
        sensitiveSmsPrivilegeOk: Boolean = true,
        active: Set<Int> = setOf(10, 20),
        identities: Map<Int, String?> = mapOf(10 to "src-token", 20 to "out-token"),
        sourceUsed: Int = 0,
        segmentsUsed: Int = 0,
        sourceLimit: Int = 100,
        segmentLimit: Int = 500,
    ) = RuntimeSnapshot(
        config = config,
        permissionsOk = permissionsOk,
        notificationsOk = notificationsOk,
        sensitiveSmsPrivilegeOk = sensitiveSmsPrivilegeOk,
        activeSubscriptionIds = active,
        currentIdentityTokens = identities,
        sourceMessagesUsedInWindow = sourceUsed,
        outboundSegmentsUsedInWindow = segmentsUsed,
        sourceMessageLimit = sourceLimit,
        outboundSegmentLimit = segmentLimit,
        nowMillis = now,
    )

    private fun inbound(
        sender: String? = "+15554444444",
        body: String = "Hello",
        subId: Int? = 10,
        serviceTs: Long? = 123L,
        pdus: List<ByteArray> = listOf(byteArrayOf(0x01, 0x02)),
    ) = InboundSms(
        sender = sender,
        body = body,
        subscriptionId = subId,
        serviceTimestampMillis = serviceTs,
        receivedAtMillis = now,
        rawPdus = pdus,
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun rememberFingerprint(inboundSms: InboundSms) {
        val eng = engine()
        // compute same way as engine
        val fp = DedupFingerprint.fingerprint(
            sourceSubscriptionId = source.subscriptionId,
            sender = inboundSms.sender,
            serviceTimestampMillis = inboundSms.serviceTimestampMillis,
            rawPdus = inboundSms.rawPdus,
            mac = testMac,
        )
        seen.add(fp.toHex())
    }

    // --- Happy path ---

    @Test
    fun accept_buildsJobWithExactHeaderAndMetadata() {
        val decision = engine().accept(inbound(), snapshot())
        assertThat(decision).isInstanceOf(ForwardDecision.Accept::class.java)
        val job = (decision as ForwardDecision.Accept).job
        assertThat(job.state).isEqualTo(ForwardState.QUEUED)
        assertThat(job.sourceSubscriptionId).isEqualTo(10)
        assertThat(job.outboundSubscriptionId).isEqualTo(20)
        assertThat(job.destinationE164).isEqualTo(destination)
        assertThat(job.configRevision).isEqualTo(1L)
        assertThat(job.createdAtMillis).isEqualTo(now)
        assertThat(job.body).isEqualTo(
            "[SMS-FWD/1] From +15554444444 via +15551111111\nHello",
        )
        assertThat(job.segmentCount).isEqualTo(1)
        assertThat(job.attemptCount).isEqualTo(0)
    }

    @Test
    fun accept_preservesUnicodeBodyInPayload() {
        val body = "OTP: 码 🔐"
        val job = (engine().accept(inbound(body = body), snapshot()) as ForwardDecision.Accept).job
        assertThat(job.body).endsWith("\n$body")
    }

    @Test
    fun accept_unknownSenderInHeader() {
        val job = (engine().accept(inbound(sender = null), snapshot()) as ForwardDecision.Accept).job
        assertThat(job.body).startsWith("[SMS-FWD/1] From Unknown via ")
    }

    // --- Source subscription ---

    @Test
    fun accept_rejectsWrongSourceSubscription() {
        val d = engine().accept(inbound(subId = 99), snapshot())
        assertThat(d).isEqualTo(ForwardDecision.Skip(SkipReason.WRONG_SOURCE_SUBSCRIPTION))
    }

    @Test
    fun accept_pausesWhenSubscriptionIdMissing() {
        val d = engine().accept(inbound(subId = null), snapshot())
        assertThat(d).isEqualTo(
            ForwardDecision.PauseAndSkip(
                pauseReason = PauseReason.MISSING_INBOUND_SUBSCRIPTION_ID,
                skipReason = SkipReason.MISSING_SUBSCRIPTION_ID,
            ),
        )
    }

    @Test
    fun accept_pausesWhenSubscriptionIdInvalid() {
        val d = engine().accept(inbound(subId = -1), snapshot())
        assertThat(d).isInstanceOf(ForwardDecision.PauseAndSkip::class.java)
        assertThat((d as ForwardDecision.PauseAndSkip).skipReason)
            .isEqualTo(SkipReason.MISSING_SUBSCRIPTION_ID)
    }

    // --- Enablement / health ---

    @Test
    fun skip_whenManuallyPaused() {
        val config = enabledConfig().copy(operationalState = OperationalState.ManuallyPaused)
        val d = engine().accept(inbound(), snapshot(config = config))
        assertThat(d).isEqualTo(ForwardDecision.Skip(SkipReason.FORWARDING_NOT_ENABLED))
    }

    @Test
    fun skip_whenDestinationNotVerified() {
        val config = enabledConfig().copy(destinationVerified = false)
        val d = engine().accept(inbound(), snapshot(config = config))
        assertThat(d).isEqualTo(ForwardDecision.Skip(SkipReason.DESTINATION_NOT_VERIFIED))
    }

    @Test
    fun pause_whenPermissionsMissing() {
        val d = engine().accept(inbound(), snapshot(permissionsOk = false))
        assertThat(d).isEqualTo(
            ForwardDecision.PauseAndSkip(
                PauseReason.PERMISSIONS_REVOKED,
                SkipReason.PERMISSIONS_MISSING,
            ),
        )
    }

    @Test
    fun pause_whenNotificationsDisabled() {
        val d = engine().accept(inbound(), snapshot(notificationsOk = false))
        assertThat(d).isEqualTo(
            ForwardDecision.PauseAndSkip(
                PauseReason.NOTIFICATIONS_DISABLED,
                SkipReason.NOTIFICATIONS_DISABLED,
            ),
        )
    }

    @Test
    fun pause_whenSensitiveSmsPrivilegeMissing() {
        val d = engine().accept(inbound(), snapshot(sensitiveSmsPrivilegeOk = false))
        assertThat(d).isEqualTo(
            ForwardDecision.PauseAndSkip(
                PauseReason.SENSITIVE_SMS_PRIVILEGE_MISSING,
                SkipReason.SENSITIVE_SMS_PRIVILEGE_MISSING,
            ),
        )
    }

    @Test
    fun pause_whenSourceSubscriptionInactive() {
        val d = engine().accept(inbound(), snapshot(active = setOf(20)))
        assertThat((d as ForwardDecision.PauseAndSkip).pauseReason)
            .isEqualTo(PauseReason.SOURCE_SUBSCRIPTION_INACTIVE)
    }

    @Test
    fun pause_whenOutboundSubscriptionInactive() {
        val d = engine().accept(inbound(), snapshot(active = setOf(10)))
        assertThat((d as ForwardDecision.PauseAndSkip).pauseReason)
            .isEqualTo(PauseReason.OUTBOUND_SUBSCRIPTION_INACTIVE)
    }

    @Test
    fun pause_whenSourceIdentityMismatch() {
        val d = engine().accept(
            inbound(),
            snapshot(identities = mapOf(10 to "other-token", 20 to "out-token")),
        )
        assertThat((d as ForwardDecision.PauseAndSkip).pauseReason)
            .isEqualTo(PauseReason.SOURCE_IDENTITY_MISMATCH)
        assertThat(d.skipReason).isEqualTo(SkipReason.IDENTITY_MISMATCH)
    }

    // --- Loop / destination / dedup ---

    @Test
    fun skip_loopMarker() {
        val d = engine().accept(
            inbound(body = "[SMS-FWD/1] From x via y\norig"),
            snapshot(),
        )
        assertThat(d).isEqualTo(ForwardDecision.Skip(SkipReason.LOOP_MARKER))
    }

    @Test
    fun skip_loopMarkerWithLeadingWhitespace() {
        val d = engine().accept(inbound(body = "  \n[SMS-FWD/2] z"), snapshot())
        assertThat(d).isEqualTo(ForwardDecision.Skip(SkipReason.LOOP_MARKER))
    }

    @Test
    fun skip_whenSenderIsDestination() {
        val d = engine().accept(inbound(sender = destination), snapshot())
        assertThat(d).isEqualTo(ForwardDecision.Skip(SkipReason.SENDER_IS_DESTINATION))
    }

    @Test
    fun skip_duplicateWhenFingerprintAlreadySeen() {
        val msg = inbound()
        rememberFingerprint(msg)
        val d = engine().accept(msg, snapshot())
        assertThat(d).isEqualTo(ForwardDecision.Skip(SkipReason.DUPLICATE))
    }

    @Test
    fun accept_differentPduIsNotDuplicate() {
        val msg1 = inbound(pdus = listOf(byteArrayOf(1)))
        rememberFingerprint(msg1)
        val msg2 = inbound(pdus = listOf(byteArrayOf(2)))
        val d = engine().accept(msg2, snapshot())
        assertThat(d).isInstanceOf(ForwardDecision.Accept::class.java)
    }

    // --- Quota ---

    @Test
    fun pause_whenSourceQuotaExceeded() {
        val d = engine().accept(inbound(), snapshot(sourceUsed = 100, sourceLimit = 100))
        assertThat(d).isEqualTo(
            ForwardDecision.PauseAndSkip(
                PauseReason.QUOTA_SOURCE_MESSAGES,
                SkipReason.QUOTA_EXCEEDED,
            ),
        )
    }

    @Test
    fun pause_whenSegmentQuotaWouldExceed() {
        val eng = DefaultForwardingEngine(
            mac = testMac,
            estimateSegments = { 10 },
            newJobId = { "j" },
        )
        val d = eng.accept(inbound(), snapshot(segmentsUsed = 495, segmentLimit = 500))
        assertThat((d as ForwardDecision.PauseAndSkip).pauseReason)
            .isEqualTo(PauseReason.QUOTA_OUTBOUND_SEGMENTS)
    }

    @Test
    fun accept_atQuotaBoundaryStillAllowed() {
        // 99 messages used of 100 → allow
        val d = engine().accept(inbound(), snapshot(sourceUsed = 99, sourceLimit = 100))
        assertThat(d).isInstanceOf(ForwardDecision.Accept::class.java)
    }

    // --- Config incomplete ---

    @Test
    fun skip_notConfigured() {
        val d = engine().accept(inbound(), snapshot(config = ForwardingConfig()))
        assertThat(d).isEqualTo(ForwardDecision.Skip(SkipReason.NOT_CONFIGURED))
    }

    @Test
    fun skip_emptyBody() {
        val d = engine().accept(inbound(body = ""), snapshot())
        assertThat(d).isEqualTo(ForwardDecision.Skip(SkipReason.BODY_EMPTY))
    }

    @Test
    fun configRevision_stampedOnJob() {
        val job = (
            engine().accept(inbound(), snapshot(config = enabledConfig(revision = 42)))
                as ForwardDecision.Accept
            ).job
        assertThat(job.configRevision).isEqualTo(42L)
        assertThat(
            DefaultForwardingEngine.isConfigRevisionCurrent(42L, enabledConfig(42)),
        ).isTrue()
        assertThat(
            DefaultForwardingEngine.isConfigRevisionCurrent(41L, enabledConfig(42)),
        ).isFalse()
    }
}

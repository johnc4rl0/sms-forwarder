package com.johnc4rl0.smsforwarder.domain.stub

import com.johnc4rl0.smsforwarder.domain.ActivationCoordinator
import com.johnc4rl0.smsforwarder.domain.ConfigRepository
import com.johnc4rl0.smsforwarder.domain.DedupStore
import com.johnc4rl0.smsforwarder.domain.DeviceAuthResult
import com.johnc4rl0.smsforwarder.domain.EnableResult
import com.johnc4rl0.smsforwarder.domain.ForwardJobRepository
import com.johnc4rl0.smsforwarder.domain.ForwardingEngine
import com.johnc4rl0.smsforwarder.domain.SmsGateway
import com.johnc4rl0.smsforwarder.domain.SubscriptionCatalog
import com.johnc4rl0.smsforwarder.domain.VerificationConfirmResult
import com.johnc4rl0.smsforwarder.domain.VerificationSendResult
import com.johnc4rl0.smsforwarder.domain.model.ActiveLine
import com.johnc4rl0.smsforwarder.domain.model.DestinationVerificationState
import com.johnc4rl0.smsforwarder.domain.model.ForwardDecision
import com.johnc4rl0.smsforwarder.domain.model.ForwardJob
import com.johnc4rl0.smsforwarder.domain.model.ForwardState
import com.johnc4rl0.smsforwarder.domain.model.ForwardingConfig
import com.johnc4rl0.smsforwarder.domain.model.InboundSms
import com.johnc4rl0.smsforwarder.domain.model.LineSelection
import com.johnc4rl0.smsforwarder.domain.model.LineValidation
import com.johnc4rl0.smsforwarder.domain.model.OutcomeMetadata
import com.johnc4rl0.smsforwarder.domain.model.PartSendResult
import com.johnc4rl0.smsforwarder.domain.model.PauseReason
import com.johnc4rl0.smsforwarder.domain.model.QuotaSnapshot
import com.johnc4rl0.smsforwarder.domain.model.RuntimeSnapshot
import com.johnc4rl0.smsforwarder.domain.model.SkipReason
import com.johnc4rl0.smsforwarder.domain.model.SubmitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Compile-only stubs. Domain/data/telephony agents replace these with real implementations.
 * Prefer throwing [NotImplementedError] over silent success so misuse is obvious.
 */

class StubSubscriptionCatalog : SubscriptionCatalog {
    override fun listActiveLines(): List<ActiveLine> = emptyList()

    override fun validate(selection: LineSelection): LineValidation =
        LineValidation.Invalid(PauseReason.CONFIGURATION_INCOMPLETE)
}

class StubForwardingEngine : ForwardingEngine {
    override fun accept(inbound: InboundSms, runtime: RuntimeSnapshot): ForwardDecision =
        ForwardDecision.Skip(SkipReason.NOT_CONFIGURED)
}

class StubForwardJobRepository : ForwardJobRepository {
    override suspend fun enqueue(job: ForwardJob) {
        throw NotImplementedError("ForwardJobRepository.enqueue — data agent")
    }

    override suspend fun recordPartResult(result: PartSendResult): ForwardJob? {
        throw NotImplementedError("ForwardJobRepository.recordPartResult — data agent")
    }

    override fun observeRecent(limit: Int): Flow<List<OutcomeMetadata>> = flowOf(emptyList())

    override suspend fun getJob(id: String): ForwardJob? = null

    override fun observeJob(id: String): Flow<ForwardJob?> = flowOf(null)

    override suspend fun listByStates(states: Set<ForwardState>): List<ForwardJob> = emptyList()

    override suspend fun claimForSubmission(
        jobId: String,
        fromStates: Set<ForwardState>,
        targetAttemptCount: Int,
    ): Boolean = true

    override suspend fun updateState(
        jobId: String,
        state: ForwardState,
        attemptCount: Int?,
        nextAttemptAtMillis: Long?,
        updateNextAttemptAt: Boolean,
        lastErrorCategory: com.johnc4rl0.smsforwarder.domain.model.ErrorCategory?,
    ) {
        throw NotImplementedError("ForwardJobRepository.updateState — data agent")
    }

    override suspend fun purgeSensitivePayloads(jobIds: Collection<String>?) {
        // no-op
    }

    override suspend fun purgeUnsentJobs() {
        // no-op
    }

    override suspend fun currentQuota(nowMillis: Long): QuotaSnapshot =
        QuotaSnapshot(sourceMessagesUsed = 0, outboundSegmentsUsed = 0, windowStartMillis = nowMillis)
}

class StubSmsGateway : SmsGateway {
    override suspend fun submit(job: ForwardJob): SubmitResult {
        throw NotImplementedError("SmsGateway.submit — telephony agent")
    }
}

class StubConfigRepository : ConfigRepository {
    private val config = MutableStateFlow(ForwardingConfig())

    override fun observeConfig(): Flow<ForwardingConfig> = config

    override suspend fun getConfig(): ForwardingConfig = config.value

    override suspend fun updateConfig(transform: (ForwardingConfig) -> ForwardingConfig) {
        config.value = transform(config.value)
    }

    override suspend fun getVerificationState(): DestinationVerificationState? = null

    override suspend fun setVerificationState(state: DestinationVerificationState?) {
        // no-op
    }

    override suspend fun purgeAll() {
        config.value = ForwardingConfig()
    }
}

class StubDedupStore : DedupStore {
    override suspend fun seenRecently(fingerprint: ByteArray): Boolean = false

    override suspend fun checkAndRemember(fingerprint: ByteArray, nowMillis: Long): Boolean = false

    override suspend fun remember(fingerprint: ByteArray, nowMillis: Long) {
        // no-op
    }

    override suspend fun purgeExpired(nowMillis: Long) {
        // no-op
    }
}

class StubActivationCoordinator(
    private val configRepository: ConfigRepository,
) : ActivationCoordinator {
    override fun observeConfig(): Flow<ForwardingConfig> = configRepository.observeConfig()

    override suspend fun acceptDisclosure() {
        configRepository.updateConfig { it.copy(disclosureAccepted = true) }
    }

    override suspend fun setSourceLine(selection: LineSelection) {
        throw NotImplementedError("ActivationCoordinator.setSourceLine — domain/UI agent")
    }

    override suspend fun setOutboundLine(selection: LineSelection) {
        throw NotImplementedError("ActivationCoordinator.setOutboundLine — domain/UI agent")
    }

    override suspend fun setDestination(e164: String): String? {
        throw NotImplementedError("ActivationCoordinator.setDestination — domain/UI agent")
    }

    override suspend fun restoreConfig(snapshot: ForwardingConfig) {
        throw NotImplementedError("ActivationCoordinator.restoreConfig — domain/UI agent")
    }

    override suspend fun sendVerificationCode(): VerificationSendResult =
        VerificationSendResult.Failed("not implemented")

    override suspend fun confirmVerificationCode(code: String): VerificationConfirmResult =
        VerificationConfirmResult.NoPending

    override suspend fun enable(authenticate: suspend () -> DeviceAuthResult): EnableResult =
        EnableResult.Blocked(PauseReason.CONFIGURATION_INCOMPLETE)

    override suspend fun pauseManual() {
        configRepository.updateConfig {
            it.copy(
                operationalState = com.johnc4rl0.smsforwarder.domain.model.OperationalState.ManuallyPaused,
                pauseReason = PauseReason.MANUAL,
            )
        }
    }

    override suspend fun reEnable(authenticate: suspend () -> DeviceAuthResult): EnableResult =
        EnableResult.Blocked(PauseReason.CONFIGURATION_INCOMPLETE)

    override suspend fun safetyPause(reason: PauseReason) {
        configRepository.updateConfig {
            it.copy(
                operationalState = com.johnc4rl0.smsforwarder.domain.model.OperationalState.SafetyPaused(reason),
                pauseReason = reason,
            )
        }
    }

    override suspend fun repairSourceLine(
        selection: LineSelection,
        authenticate: suspend () -> DeviceAuthResult,
    ): com.johnc4rl0.smsforwarder.domain.RepairResult =
        com.johnc4rl0.smsforwarder.domain.RepairResult.LineNotFound

    override suspend fun repairOutboundLine(
        selection: LineSelection,
        authenticate: suspend () -> DeviceAuthResult,
    ): com.johnc4rl0.smsforwarder.domain.RepairResult =
        com.johnc4rl0.smsforwarder.domain.RepairResult.LineNotFound
}

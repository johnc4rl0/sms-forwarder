package com.johnc4rl0.smsforwarder.domain

import com.google.common.truth.Truth.assertThat
import com.johnc4rl0.smsforwarder.domain.model.ActiveLine
import com.johnc4rl0.smsforwarder.domain.model.DestinationVerificationState
import com.johnc4rl0.smsforwarder.domain.model.ErrorCategory
import com.johnc4rl0.smsforwarder.domain.model.ForwardJob
import com.johnc4rl0.smsforwarder.domain.model.ForwardState
import com.johnc4rl0.smsforwarder.domain.model.ForwardingConfig
import com.johnc4rl0.smsforwarder.domain.model.LineSelection
import com.johnc4rl0.smsforwarder.domain.model.LineValidation
import com.johnc4rl0.smsforwarder.domain.model.OperationalState
import com.johnc4rl0.smsforwarder.domain.model.OutcomeMetadata
import com.johnc4rl0.smsforwarder.domain.model.PartSendResult
import com.johnc4rl0.smsforwarder.domain.model.PauseReason
import com.johnc4rl0.smsforwarder.domain.model.QuotaSnapshot
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class DefaultActivationCoordinatorTest {

    private val now = 2_000_000_000_000L
    private var clockMs = now

    private val source = LineSelection(
        subscriptionId = 1,
        slotIndex = 0,
        carrierDisplayName = "Src",
        reportedNumberE164 = "+15551111111",
        manualNumberE164 = null,
        identityToken = "v1:icc:src-tok",
    )
    private val outbound = LineSelection(
        subscriptionId = 2,
        slotIndex = 1,
        carrierDisplayName = "Out",
        reportedNumberE164 = "+15552222222",
        manualNumberE164 = null,
        identityToken = "v1:icc:out-tok",
    )
    private val destination = "+15553333333"

    private lateinit var configRepo: FakeConfigRepository
    private lateinit var jobs: FakeForwardJobRepository
    private lateinit var catalog: FakeSubscriptionCatalog
    private val sentCodes = mutableListOf<String>()
    private var permissionsOk = true
    private var notificationsOk = true
    private var sensitiveSmsPrivilegeOk = true
    private var encryptionOk = true

    private val mac: (ByteArray) -> ByteArray = { data ->
        MessageDigest.getInstance("SHA-256").digest(data)
    }

    private fun coordinator(
        code: String = "123456",
        smsSucceeds: Boolean = true,
    ): DefaultActivationCoordinator =
        DefaultActivationCoordinator(
            configRepository = configRepo,
            forwardJobRepository = jobs,
            subscriptionCatalog = catalog,
            mac = mac,
            sendVerificationSms = { _, _, c ->
                sentCodes += c
                smsSucceeds
            },
            permissionsOk = { permissionsOk },
            notificationsOk = { notificationsOk },
            sensitiveSmsPrivilegeOk = { sensitiveSmsPrivilegeOk },
            encryptionAvailable = { encryptionOk },
            clock = { clockMs },
            randomCode = { code },
        )

    @Before
    fun setUp() {
        clockMs = now
        sentCodes.clear()
        permissionsOk = true
        notificationsOk = true
        sensitiveSmsPrivilegeOk = true
        encryptionOk = true
        configRepo = FakeConfigRepository()
        jobs = FakeForwardJobRepository()
        catalog = FakeSubscriptionCatalog(
            lines = listOf(
                ActiveLine(1, 0, "Src", "+15551111111", false, "v1:icc:src-tok"),
                ActiveLine(2, 1, "Out", "+15552222222", false, "v1:icc:out-tok"),
            ),
        )
    }

    @Test
    fun acceptDisclosure_setsFlag() = runBlocking {
        coordinator().acceptDisclosure()
        assertThat(configRepo.getConfig().disclosureAccepted).isTrue()
    }

    @Test
    fun setDestination_rejectsInvalidE164() = runBlocking {
        val err = coordinator().setDestination("555-1234")
        assertThat(err).isNotNull()
        assertThat(configRepo.getConfig().destinationE164).isNull()
    }

    @Test
    fun setDestination_rejectsLocalLine() = runBlocking {
        configRepo.seed(
            ForwardingConfig(
                disclosureAccepted = true,
                source = source,
                outbound = outbound,
            ),
        )
        val err = coordinator().setDestination("+15551111111")
        assertThat(err).isNotNull()
        assertThat(err!!.lowercase()).contains("device")
    }

    @Test
    fun setDestination_clearsVerificationCode_butPreservesRateLimitState() = runBlocking {
        configRepo.seed(
            ForwardingConfig(
                disclosureAccepted = true,
                source = source,
                outbound = outbound,
                destinationE164 = destination,
                destinationVerified = true,
            ),
        )
        configRepo.setVerificationState(
            DestinationVerificationState(
                destinationE164 = destination,
                codeDigest = byteArrayOf(1),
                expiresAtMillis = now + 60_000,
                attemptsRemaining = 5,
                sendsInRollingHour = 1,
                lastSendAtMillis = now,
            ),
        )
        val err = coordinator().setDestination("+15554444444")
        assertThat(err).isNull()
        assertThat(configRepo.getConfig().destinationVerified).isFalse()
        assertThat(configRepo.getConfig().destinationE164).isEqualTo("+15554444444")
        val state = configRepo.getVerificationState()
        assertThat(state).isNotNull()
        assertThat(state!!.attemptsRemaining).isEqualTo(0)
        assertThat(state.sendsInRollingHour).isEqualTo(1)
    }

    @Test
    fun setDestination_doesNotResetVerificationRateLimit() = runBlocking {
        seedReadyForVerification()
        val c = coordinator(code = "111111")
        // Send 3 codes to hit rate limit
        assertThat(c.sendVerificationCode()).isEqualTo(VerificationSendResult.Sent)
        assertThat(c.sendVerificationCode()).isEqualTo(VerificationSendResult.Sent)
        assertThat(c.sendVerificationCode()).isEqualTo(VerificationSendResult.Sent)
        assertThat(c.sendVerificationCode()).isEqualTo(VerificationSendResult.RateLimited)

        // Change destination
        assertThat(c.setDestination("+15559999999")).isNull()

        // 4th send still rate limited!
        assertThat(c.sendVerificationCode()).isEqualTo(VerificationSendResult.RateLimited)
    }

    @Test
    fun setSourceLine_whileEnabled_pausesAndPurgesUnsent() = runBlocking {
        configRepo.seed(
            ForwardingConfig(
                disclosureAccepted = true,
                source = source,
                outbound = outbound,
                destinationE164 = destination,
                destinationVerified = true,
                operationalState = OperationalState.Enabled,
                configRevision = 3,
            ),
        )
        jobs.unsentPurged = false
        coordinator().setSourceLine(source.copy(subscriptionId = 1, identityToken = "v1:icc:new"))
        val cfg = configRepo.getConfig()
        assertThat(cfg.operationalState).isEqualTo(OperationalState.ManuallyPaused)
        assertThat(cfg.configRevision).isEqualTo(4)
        assertThat(jobs.unsentPurged).isTrue()
    }

    @Test
    fun setSourceLine_duringOnboarding_staysNotConfigured() = runBlocking {
        configRepo.seed(ForwardingConfig(disclosureAccepted = true))
        coordinator().setSourceLine(source)
        assertThat(configRepo.getConfig().operationalState)
            .isEqualTo(OperationalState.NotConfigured)
        assertThat(configRepo.getConfig().source).isEqualTo(source)
    }

    @Test
    fun sendVerificationCode_storesHmacOnly_andRateLimits() = runBlocking {
        seedReadyForVerification()
        val c = coordinator(code = "654321")
        assertThat(c.sendVerificationCode()).isEqualTo(VerificationSendResult.Sent)
        assertThat(sentCodes).containsExactly("654321")
        val state = configRepo.getVerificationState()!!
        assertThat(
            state.codeDigest.contentEquals(
                mac(DefaultActivationCoordinator.verificationMacPreimage(destination, "654321")),
            ),
        ).isTrue()
        assertThat(state.attemptsRemaining).isEqualTo(5)
        assertThat(configRepo.getConfig().destinationVerified).isFalse()

        // Two more sends ok
        assertThat(c.sendVerificationCode()).isEqualTo(VerificationSendResult.Sent)
        assertThat(c.sendVerificationCode()).isEqualTo(VerificationSendResult.Sent)
        // Fourth within hour
        assertThat(c.sendVerificationCode()).isEqualTo(VerificationSendResult.RateLimited)
    }

    @Test
    fun sendVerificationCode_concurrentCalls_strictlyRateLimited() = runBlocking {
        seedReadyForVerification()
        val c = coordinator(code = "654321")
        // Launch 5 concurrent calls
        val deferreds = (1..5).map {
            async {
                c.sendVerificationCode()
            }
        }
        val results = deferreds.map { it.await() }

        val sentCount = results.count { it == VerificationSendResult.Sent }
        val rateLimitedCount = results.count { it == VerificationSendResult.RateLimited }

        assertThat(sentCount).isEqualTo(3)
        assertThat(rateLimitedCount).isEqualTo(2)
    }

    @Test
    fun sendVerificationCode_resendInvalidatesPrevious() = runBlocking {
        seedReadyForVerification()
        val c = coordinator(code = "111111")
        assertThat(c.sendVerificationCode()).isEqualTo(VerificationSendResult.Sent)
        // Change random code for second send
        val c2 = DefaultActivationCoordinator(
            configRepository = configRepo,
            forwardJobRepository = jobs,
            subscriptionCatalog = catalog,
            mac = mac,
            sendVerificationSms = { _, _, code ->
                sentCodes += code
                true
            },
            permissionsOk = { true },
            notificationsOk = { true },
            clock = { clockMs },
            randomCode = { "222222" },
        )
        assertThat(c2.sendVerificationCode()).isEqualTo(VerificationSendResult.Sent)
        assertThat(c2.confirmVerificationCode("111111"))
            .isEqualTo(VerificationConfirmResult.Mismatch)
        assertThat(c2.confirmVerificationCode("222222"))
            .isEqualTo(VerificationConfirmResult.Verified)
        assertThat(configRepo.getConfig().destinationVerified).isTrue()
    }

    @Test
    fun confirmVerificationCode_fiveAttemptsThenLockedOut() = runBlocking {
        seedReadyForVerification()
        val c = coordinator(code = "999999")
        assertThat(c.sendVerificationCode()).isEqualTo(VerificationSendResult.Sent)
        repeat(4) {
            assertThat(c.confirmVerificationCode("000000"))
                .isEqualTo(VerificationConfirmResult.Mismatch)
        }
        assertThat(c.confirmVerificationCode("000000"))
            .isEqualTo(VerificationConfirmResult.LockedOut)
        // Further attempts stay locked
        assertThat(c.confirmVerificationCode("999999"))
            .isEqualTo(VerificationConfirmResult.LockedOut)
    }

    @Test
    fun confirmVerificationCode_expired() = runBlocking {
        seedReadyForVerification()
        val c = coordinator(code = "123456")
        assertThat(c.sendVerificationCode()).isEqualTo(VerificationSendResult.Sent)
        clockMs = now + VerificationPolicy.CODE_EXPIRY_MILLIS + 1
        assertThat(c.confirmVerificationCode("123456"))
            .isEqualTo(VerificationConfirmResult.Expired)
    }

    @Test
    fun confirmVerificationCode_destinationChangedInterleaved_returnsNoPendingOrLockedOut() = runBlocking {
        seedReadyForVerification()
        val c = coordinator(code = "123456")
        assertThat(c.sendVerificationCode()).isEqualTo(VerificationSendResult.Sent)
        
        // Interleaved destination change updates config.destinationE164 and resets state (attemptsRemaining = 0, expiresAt = 0)
        c.setDestination("+15559998888")

        // Confirming code for old destination must be rejected and not verify +15559998888
        val result = c.confirmVerificationCode("123456")
        assertThat(result == VerificationConfirmResult.NoPending || result == VerificationConfirmResult.LockedOut || result == VerificationConfirmResult.Expired).isTrue()
        assertThat(configRepo.getConfig().destinationVerified).isFalse()
    }

    @Test
    fun enable_requiresAuthAndPermissions() = runBlocking {
        seedFullyConfigured()
        val c = coordinator()
        assertThat(c.enable { DeviceAuthResult.Failed }).isEqualTo(EnableResult.AuthFailed)
        assertThat(c.enable { DeviceAuthResult.Cancelled }).isEqualTo(EnableResult.AuthCancelled)

        permissionsOk = false
        assertThat(c.enable { DeviceAuthResult.Success })
            .isEqualTo(EnableResult.Blocked(PauseReason.PERMISSIONS_REVOKED))

        permissionsOk = true
        sensitiveSmsPrivilegeOk = false
        assertThat(c.enable { DeviceAuthResult.Success })
            .isEqualTo(EnableResult.Blocked(PauseReason.SENSITIVE_SMS_PRIVILEGE_MISSING))

        sensitiveSmsPrivilegeOk = true
        assertThat(c.enable { DeviceAuthResult.Success }).isEqualTo(EnableResult.Enabled)
        assertThat(configRepo.getConfig().operationalState)
            .isEqualTo(OperationalState.Enabled)
    }

    @Test
    fun pauseManual_noAuth_andPurgesUnsentAndSensitive() = runBlocking {
        seedFullyConfigured()
        configRepo.seed(configRepo.getConfig().copy(operationalState = OperationalState.Enabled))
        jobs.unsentPurged = false
        jobs.sensitivePurged = false
        coordinator().pauseManual()
        assertThat(configRepo.getConfig().operationalState)
            .isEqualTo(OperationalState.ManuallyPaused)
        assertThat(jobs.unsentPurged).isTrue()
        assertThat(jobs.sensitivePurged).isTrue()
    }

    @Test
    fun safetyPause_setsReasonAndPurgesUnsent() = runBlocking {
        seedFullyConfigured()
        configRepo.seed(configRepo.getConfig().copy(operationalState = OperationalState.Enabled))
        jobs.unsentPurged = false
        coordinator().safetyPause(PauseReason.QUOTA_SOURCE_MESSAGES)
        val cfg = configRepo.getConfig()
        assertThat(cfg.operationalState)
            .isEqualTo(OperationalState.SafetyPaused(PauseReason.QUOTA_SOURCE_MESSAGES))
        assertThat(cfg.pauseReason).isEqualTo(PauseReason.QUOTA_SOURCE_MESSAGES)
        assertThat(jobs.unsentPurged).isTrue()
    }

    @Test
    fun setDestination_whilePaused_purgesUnsent() = runBlocking {
        seedFullyConfigured()
        configRepo.seed(
            configRepo.getConfig().copy(
                operationalState = OperationalState.ManuallyPaused,
                pauseReason = PauseReason.MANUAL,
            ),
        )
        jobs.unsentPurged = false
        assertThat(coordinator().setDestination("+15554444444")).isNull()
        assertThat(jobs.unsentPurged).isTrue()
        assertThat(configRepo.getConfig().destinationVerified).isFalse()
    }

    @Test
    fun restoreConfig_revertsToPreEditVerifiedDestination() = runBlocking {
        seedFullyConfigured()
        configRepo.seed(
            configRepo.getConfig().copy(
                operationalState = OperationalState.ManuallyPaused,
                pauseReason = PauseReason.MANUAL,
            ),
        )
        val before = configRepo.getConfig()
        assertThat(before.destinationVerified).isTrue()
        assertThat(before.destinationE164).isEqualTo(destination)

        // Simulate a destination change made before verification completes.
        assertThat(coordinator().setDestination("+15554444444")).isNull()
        assertThat(configRepo.getConfig().destinationE164).isEqualTo("+15554444444")
        assertThat(configRepo.getConfig().destinationVerified).isFalse()

        // Discard reverts to the prior verified destination.
        coordinator().restoreConfig(before)
        val after = configRepo.getConfig()
        assertThat(after.destinationE164).isEqualTo(destination)
        assertThat(after.destinationVerified).isTrue()
    }

    @Test
    fun reEnable_blocksWhenQuotaExhausted() = runBlocking {
        seedFullyConfigured()
        configRepo.seed(
            configRepo.getConfig().copy(
                operationalState = OperationalState.SafetyPaused(PauseReason.QUOTA_SOURCE_MESSAGES),
                pauseReason = PauseReason.QUOTA_SOURCE_MESSAGES,
            ),
        )
        jobs.quota = QuotaSnapshot(
            sourceMessagesUsed = 100,
            outboundSegmentsUsed = 0,
            windowStartMillis = now,
        )
        val result = coordinator().reEnable { DeviceAuthResult.Success }
        assertThat(result).isEqualTo(EnableResult.Blocked(PauseReason.QUOTA_SOURCE_MESSAGES))
    }

    @Test
    fun reEnable_succeedsWithCapacityAndAuth() = runBlocking {
        seedFullyConfigured()
        configRepo.seed(
            configRepo.getConfig().copy(
                operationalState = OperationalState.ManuallyPaused,
                pauseReason = PauseReason.MANUAL,
            ),
        )
        jobs.quota = QuotaSnapshot(10, 20, now)
        assertThat(coordinator().reEnable { DeviceAuthResult.Success }).isEqualTo(EnableResult.Enabled)
        assertThat(configRepo.getConfig().operationalState)
            .isEqualTo(OperationalState.Enabled)
    }

    @Test
    fun enable_blockedWhenEncryptionUnavailable() = runBlocking {
        seedFullyConfigured()
        encryptionOk = false
        assertThat(coordinator().enable { DeviceAuthResult.Success })
            .isEqualTo(EnableResult.Blocked(PauseReason.ENCRYPTION_UNAVAILABLE))
    }

    // --- fixtures ---

    private suspend fun seedReadyForVerification() {
        configRepo.seed(
            ForwardingConfig(
                disclosureAccepted = true,
                source = source,
                outbound = outbound,
                destinationE164 = destination,
                destinationVerified = false,
            ),
        )
    }

    private suspend fun seedFullyConfigured() {
        configRepo.seed(
            ForwardingConfig(
                disclosureAccepted = true,
                source = source,
                outbound = outbound,
                destinationE164 = destination,
                destinationVerified = true,
                operationalState = OperationalState.NotConfigured,
            ),
        )
    }

    // --- fakes ---

    private class FakeConfigRepository : ConfigRepository {
        private val config = MutableStateFlow(ForwardingConfig())
        private var verification: DestinationVerificationState? = null

        fun seed(cfg: ForwardingConfig) {
            config.value = cfg
        }

        override fun observeConfig(): Flow<ForwardingConfig> = config
        override suspend fun getConfig(): ForwardingConfig = config.value
        override suspend fun updateConfig(transform: (ForwardingConfig) -> ForwardingConfig) {
            config.value = transform(config.value)
        }

        override suspend fun getVerificationState(): DestinationVerificationState? = verification
        override suspend fun setVerificationState(state: DestinationVerificationState?) {
            verification = state
        }

        override suspend fun purgeAll() {
            config.value = ForwardingConfig()
            verification = null
        }
    }

    private class FakeForwardJobRepository : ForwardJobRepository {
        var unsentPurged = false
        var sensitivePurged = false
        var failPurge = false
        var quota = QuotaSnapshot(0, 0, 0L)

        override suspend fun enqueue(job: ForwardJob) = Unit
        override suspend fun recordPartResult(result: PartSendResult): ForwardJob? = null
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
            lastErrorCategory: ErrorCategory?,
        ) = Unit

        override suspend fun purgeSensitivePayloads(jobIds: Collection<String>?) {
            if (failPurge) throw IllegalStateException("purge failed")
            sensitivePurged = true
        }

        override suspend fun purgeUnsentJobs() {
            if (failPurge) throw IllegalStateException("purge failed")
            unsentPurged = true
        }

        override suspend fun currentQuota(nowMillis: Long): QuotaSnapshot = quota
    }

    @Test
    fun repairSourceLine_success_sameSubId_updatesBindingAndBumpsRevision() = runBlocking {
        // Initial setup has old token; live catalog has updated token
        catalog = FakeSubscriptionCatalog(
            lines = listOf(
                ActiveLine(1, 0, "Src", "+15551111111", false, "v1:icc:repaired-token"),
                ActiveLine(2, 1, "Out", "+15552222222", false, "v1:icc:out-tok"),
            ),
        )
        configRepo.seed(
            ForwardingConfig(
                disclosureAccepted = true,
                source = source.copy(identityToken = "v1:icc:old-token"),
                outbound = outbound,
                destinationE164 = destination,
                destinationVerified = true,
                operationalState = OperationalState.SafetyPaused(PauseReason.SOURCE_IDENTITY_MISMATCH),
                pauseReason = PauseReason.SOURCE_IDENTITY_MISMATCH,
                configRevision = 5L,
            ),
        )
        jobs.unsentPurged = false
        jobs.sensitivePurged = false

        val c = coordinator()
        val repairTarget = source.copy(identityToken = "v1:icc:repaired-token")
        val result = c.repairSourceLine(repairTarget) { DeviceAuthResult.Success }

        assertThat(result).isEqualTo(RepairResult.Success)
        val updated = configRepo.getConfig()
        assertThat(updated.source?.identityToken).isEqualTo("v1:icc:repaired-token")
        assertThat(updated.configRevision).isEqualTo(6L)
        assertThat(updated.operationalState).isEqualTo(OperationalState.ManuallyPaused)
        assertThat(jobs.unsentPurged).isTrue()
        assertThat(jobs.sensitivePurged).isTrue()
    }

    @Test
    fun repairSourceLine_authCancelled_leavesConfigUnchanged() = runBlocking {
        configRepo.seed(
            ForwardingConfig(
                disclosureAccepted = true,
                source = source.copy(identityToken = "v1:icc:old-token"),
                outbound = outbound,
                destinationE164 = destination,
                destinationVerified = true,
                operationalState = OperationalState.SafetyPaused(PauseReason.SOURCE_IDENTITY_MISMATCH),
                pauseReason = PauseReason.SOURCE_IDENTITY_MISMATCH,
                configRevision = 5L,
            ),
        )
        val c = coordinator()
        val result = c.repairSourceLine(source) { DeviceAuthResult.Cancelled }
        assertThat(result).isEqualTo(RepairResult.AuthCancelled)
        val updated = configRepo.getConfig()
        assertThat(updated.source?.identityToken).isEqualTo("v1:icc:old-token")
        assertThat(updated.configRevision).isEqualTo(5L)
    }

    @Test
    fun repairSourceLine_catalogDriftDuringAuth_failsClosed() = runBlocking {
        var catalogListCount = 0
        val driftingCatalog = object : SubscriptionCatalog {
            override fun listActiveLines(): List<ActiveLine> {
                catalogListCount++
                return if (catalogListCount == 1) {
                    listOf(
                        ActiveLine(1, 0, "Src", "+15551111111", false, "v1:icc:token-1"),
                        ActiveLine(2, 1, "Out", "+15552222222", false, "v1:icc:out-tok"),
                    )
                } else {
                    listOf(
                        ActiveLine(1, 0, "Src", "+15551111111", false, "v1:icc:token-2"), // Drifted!
                        ActiveLine(2, 1, "Out", "+15552222222", false, "v1:icc:out-tok"),
                    )
                }
            }

            override fun validate(selection: LineSelection): LineValidation = LineValidation.Valid
        }
        val c = DefaultActivationCoordinator(
            configRepository = configRepo,
            forwardJobRepository = jobs,
            subscriptionCatalog = driftingCatalog,
            mac = mac,
            sendVerificationSms = { _, _, _ -> true },
            permissionsOk = { true },
            notificationsOk = { true },
            sensitiveSmsPrivilegeOk = { true },
            encryptionAvailable = { true },
            clock = { clockMs },
            randomCode = { "123456" },
        )
        val target = source.copy(identityToken = "v1:icc:token-1")
        val result = c.repairSourceLine(target) { DeviceAuthResult.Success }
        assertThat(result).isEqualTo(RepairResult.CatalogDrift)
    }

    @Test
    fun repairSourceLine_purgeFailure_leavesBindingUnchanged() = runBlocking {
        configRepo.seed(
            ForwardingConfig(
                disclosureAccepted = true,
                source = source.copy(identityToken = "v1:icc:old-token"),
                outbound = outbound,
                destinationE164 = destination,
                destinationVerified = true,
                operationalState = OperationalState.SafetyPaused(PauseReason.SOURCE_IDENTITY_MISMATCH),
                pauseReason = PauseReason.SOURCE_IDENTITY_MISMATCH,
                configRevision = 5L,
            ),
        )
        jobs.failPurge = true

        val result = coordinator().repairSourceLine(source) { DeviceAuthResult.Success }

        assertThat(result).isEqualTo(RepairResult.PurgeFailed)
        assertThat(configRepo.getConfig().source?.identityToken).isEqualTo("v1:icc:old-token")
        assertThat(configRepo.getConfig().configRevision).isEqualTo(5L)
    }

    @Test
    fun repairSourceLine_destinationConflict_rejectsRepair() = runBlocking {
        catalog = FakeSubscriptionCatalog(
            lines = listOf(
                ActiveLine(1, 0, "Src", "+15559999999", false, "v1:icc:src-tok"),
                ActiveLine(2, 1, "Out", "+15552222222", false, "v1:icc:out-tok"),
            ),
        )
        configRepo.seed(
            ForwardingConfig(
                disclosureAccepted = true,
                source = source,
                outbound = outbound,
                destinationE164 = "+15559999999", // Conflicts with repaired source line
                destinationVerified = true,
            ),
        )
        val target = source.copy(reportedNumberE164 = "+15559999999")
        val result = coordinator().repairSourceLine(target) { DeviceAuthResult.Success }
        assertThat(result).isInstanceOf(RepairResult.DestinationConflict::class.java)
    }

    @Test
    fun repairOutboundLine_success_independentFromSource() = runBlocking {
        catalog = FakeSubscriptionCatalog(
            lines = listOf(
                ActiveLine(1, 0, "Src", "+15551111111", false, "v1:icc:src-tok"),
                ActiveLine(2, 1, "Out", "+15552222222", false, "v1:icc:repaired-out"),
            ),
        )
        configRepo.seed(
            ForwardingConfig(
                disclosureAccepted = true,
                source = source,
                outbound = outbound.copy(identityToken = "v1:icc:old-out"),
                destinationE164 = destination,
                destinationVerified = true,
                configRevision = 1L,
            ),
        )
        val target = outbound.copy(identityToken = "v1:icc:repaired-out")
        val result = coordinator().repairOutboundLine(target) { DeviceAuthResult.Success }
        assertThat(result).isEqualTo(RepairResult.Success)
        val updated = configRepo.getConfig()
        assertThat(updated.outbound?.identityToken).isEqualTo("v1:icc:repaired-out")
        assertThat(updated.configRevision).isEqualTo(2L)
    }

    private class FakeSubscriptionCatalog(
        private val lines: List<ActiveLine>,
    ) : SubscriptionCatalog {
        override fun listActiveLines(): List<ActiveLine> = lines

        override fun validate(selection: LineSelection): LineValidation {
            val match = lines.find { it.subscriptionId == selection.subscriptionId }
                ?: return LineValidation.Invalid(PauseReason.SOURCE_SUBSCRIPTION_INACTIVE)
            return when (SubscriptionIdentity.compare(selection.identityToken, match.identityToken)) {
                IdentityComparisonResult.Same -> LineValidation.Valid
                IdentityComparisonResult.Different -> LineValidation.Invalid(PauseReason.SOURCE_IDENTITY_MISMATCH)
                IdentityComparisonResult.Unknown -> LineValidation.Invalid(PauseReason.SOURCE_IDENTITY_UNAVAILABLE)
            }
        }
    }
}

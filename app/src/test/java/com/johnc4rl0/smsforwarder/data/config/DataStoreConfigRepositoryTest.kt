package com.johnc4rl0.smsforwarder.data.config

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.johnc4rl0.smsforwarder.data.crypto.SoftwareCryptoVault
import com.johnc4rl0.smsforwarder.domain.model.DestinationVerificationState
import com.johnc4rl0.smsforwarder.domain.model.LineSelection
import com.johnc4rl0.smsforwarder.domain.model.OperationalState
import com.johnc4rl0.smsforwarder.domain.model.PauseReason
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DataStoreConfigRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var repo: DataStoreConfigRepository
    private lateinit var vault: SoftwareCryptoVault
    private lateinit var dataFile: File

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        vault = SoftwareCryptoVault(
            aesKeyBytes = ByteArray(32) { 11 },
            hmacKeyBytes = ByteArray(32) { 12 },
        )
        dataFile = tmp.newFile("test_cfg_${System.nanoTime()}.preferences_pb")
        val store = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { dataFile },
        )
        repo = DataStoreConfigRepository.create(store, vault)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun defaultConfig_isEmpty() = runBlocking {
        val cfg = repo.getConfig()
        assertThat(cfg.disclosureAccepted).isFalse()
        assertThat(cfg.source).isNull()
        assertThat(cfg.destinationE164).isNull()
        assertThat(cfg.operationalState).isEqualTo(OperationalState.NotConfigured)
    }

    @Test
    fun updateConfig_roundTripsEncryptedNumbers() = runBlocking {
        val source = LineSelection(
            subscriptionId = 7,
            slotIndex = 0,
            carrierDisplayName = "TestCarrier",
            reportedNumberE164 = "+15551112222",
            manualNumberE164 = null,
            identityToken = "tok-abc",
        )
        repo.updateConfig {
            it.copy(
                disclosureAccepted = true,
                source = source,
                destinationE164 = "+15553334444",
                destinationVerified = true,
                configRevision = 3,
                operationalState = OperationalState.Enabled,
            )
        }
        val cfg = repo.getConfig()
        assertThat(cfg.disclosureAccepted).isTrue()
        assertThat(cfg.source?.subscriptionId).isEqualTo(7)
        assertThat(cfg.source?.reportedNumberE164).isEqualTo("+15551112222")
        assertThat(cfg.source?.identityToken).isEqualTo("tok-abc")
        assertThat(cfg.destinationE164).isEqualTo("+15553334444")
        assertThat(cfg.destinationVerified).isTrue()
        assertThat(cfg.configRevision).isEqualTo(3)
        assertThat(cfg.operationalState).isEqualTo(OperationalState.Enabled)
    }

    @Test
    fun observeConfig_emitsUpdates() = runBlocking {
        repo.observeConfig().test {
            val first = awaitItem()
            assertThat(first.disclosureAccepted).isFalse()
            repo.updateConfig { it.copy(disclosureAccepted = true) }
            val second = awaitItem()
            assertThat(second.disclosureAccepted).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun verificationState_storesDigestNotPlaintextCode() = runBlocking {
        val digest = ByteArray(32) { (it + 3).toByte() }
        val state = DestinationVerificationState(
            destinationE164 = "+15556667777",
            codeDigest = digest,
            expiresAtMillis = 99_000L,
            attemptsRemaining = 5,
            sendsInRollingHour = 1,
            lastSendAtMillis = 50_000L,
        )
        repo.setVerificationState(state)
        val loaded = repo.getVerificationState()!!
        assertThat(loaded.destinationE164).isEqualTo("+15556667777")
        assertThat(loaded.codeDigest.contentEquals(digest)).isTrue()
        assertThat(loaded.attemptsRemaining).isEqualTo(5)

        repo.setVerificationState(null)
        assertThat(repo.getVerificationState()).isNull()
    }

    @Test
    fun safetyPaused_roundTripsWithReason() = runBlocking {
        repo.updateConfig {
            it.copy(
                operationalState = OperationalState.SafetyPaused(PauseReason.QUOTA_SOURCE_MESSAGES),
                pauseReason = PauseReason.QUOTA_SOURCE_MESSAGES,
            )
        }
        val cfg = repo.getConfig()
        assertThat(cfg.operationalState)
            .isEqualTo(OperationalState.SafetyPaused(PauseReason.QUOTA_SOURCE_MESSAGES))
        assertThat(cfg.pauseReason).isEqualTo(PauseReason.QUOTA_SOURCE_MESSAGES)
    }

    @Test
    fun purgeAll_clearsConfig() = runBlocking {
        repo.updateConfig {
            it.copy(
                disclosureAccepted = true,
                destinationE164 = "+15550000000",
                operationalState = OperationalState.Enabled,
            )
        }
        repo.setVerificationState(
            DestinationVerificationState(
                destinationE164 = "+15550000000",
                codeDigest = ByteArray(32),
                expiresAtMillis = 1,
                attemptsRemaining = 1,
                sendsInRollingHour = 1,
                lastSendAtMillis = 1,
            ),
        )
        repo.purgeAll()
        val cfg = repo.getConfig()
        assertThat(cfg.disclosureAccepted).isFalse()
        assertThat(cfg.destinationE164).isNull()
        assertThat(repo.getVerificationState()).isNull()
    }
}

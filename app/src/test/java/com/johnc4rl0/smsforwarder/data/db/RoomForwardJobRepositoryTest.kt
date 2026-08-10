package com.johnc4rl0.smsforwarder.data.db

import android.app.Activity
import android.telephony.SmsManager
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.johnc4rl0.smsforwarder.data.crypto.SoftwareCryptoVault
import com.johnc4rl0.smsforwarder.domain.model.ErrorCategory
import com.johnc4rl0.smsforwarder.domain.model.ForwardJob
import com.johnc4rl0.smsforwarder.domain.model.ForwardState
import com.johnc4rl0.smsforwarder.domain.model.PartSendResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RoomForwardJobRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: RoomForwardJobRepository
    private lateinit var vault: SoftwareCryptoVault

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = AppDatabase.buildInMemory(context)
        vault = SoftwareCryptoVault(
            aesKeyBytes = ByteArray(32) { 9 },
            hmacKeyBytes = ByteArray(32) { 10 },
        )
        repo = RoomForwardJobRepository(db, vault)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun job(
        id: String = "job-1",
        state: ForwardState = ForwardState.QUEUED,
        segmentCount: Int? = 1,
        // Must be "now" relative to wall clock so TTL expiry does not fire on get/list.
        createdAt: Long = System.currentTimeMillis(),
        body: String = "secret body",
        sender: String? = "+15550001111",
    ) = ForwardJob(
        id = id,
        state = state,
        configRevision = 1L,
        sourceSubscriptionId = 1,
        outboundSubscriptionId = 2,
        sender = sender,
        body = body,
        destinationE164 = "+15559998888",
        createdAtMillis = createdAt,
        attemptCount = 0,
        segmentCount = segmentCount,
    )

    @Test
    fun enqueue_encryptsAtRestAndDecryptsOnRead() = runBlocking {
        repo.enqueue(job())
        val loaded = repo.getJob("job-1")!!
        assertThat(loaded.body).isEqualTo("secret body")
        assertThat(loaded.sender).isEqualTo("+15550001111")
        assertThat(loaded.destinationE164).isEqualTo("+15559998888")

        val raw = db.forwardJobDao().getById("job-1")!!
        assertThat(raw.bodyCipher).isNotNull()
        assertThat(raw.bodyCipher!!.toString(Charsets.UTF_8)).doesNotContain("secret")
        assertThat(raw.senderCipher!!.toString(Charsets.UTF_8)).doesNotContain("15550001111")
    }

    @Test
    fun enqueue_reservesQuota_andRejectsWhenExceeded() = runBlocking {
        val now = 10_000_000L
        repeat(DataLayerConstants.SOURCE_MESSAGE_LIMIT) { i ->
            repo.enqueue(job(id = "m-$i", createdAt = now + i, segmentCount = 1))
        }
        val quota = repo.currentQuota(now + DataLayerConstants.SOURCE_MESSAGE_LIMIT)
        assertThat(quota.sourceMessagesUsed).isEqualTo(DataLayerConstants.SOURCE_MESSAGE_LIMIT)

        try {
            repo.enqueue(job(id = "overflow", createdAt = now + 200, segmentCount = 1))
            throw AssertionError("expected QuotaExceededException")
        } catch (e: QuotaExceededException) {
            assertThat(e.kind).isEqualTo(QuotaExceededException.Kind.SOURCE_MESSAGES)
        }
    }

    @Test
    fun enqueue_rejectsSegmentQuota() = runBlocking {
        val now = 20_000_000L
        // 499 segments used
        repo.enqueue(job(id = "big", createdAt = now, segmentCount = 499))
        try {
            repo.enqueue(job(id = "two", createdAt = now + 1, segmentCount = 2))
            throw AssertionError("expected segment quota fail")
        } catch (e: QuotaExceededException) {
            assertThat(e.kind).isEqualTo(QuotaExceededException.Kind.OUTBOUND_SEGMENTS)
        }
        // Exactly filling remaining 1 segment is OK
        repo.enqueue(job(id = "one", createdAt = now + 2, segmentCount = 1))
        val q = repo.currentQuota(now + 3)
        assertThat(q.outboundSegmentsUsed).isEqualTo(500)
    }

    @Test
    fun retries_doNotConsumeAdditionalQuota() = runBlocking {
        val now = 30_000_000L
        repo.enqueue(job(id = "r1", createdAt = now, segmentCount = 2))
        repo.updateState("r1", ForwardState.RETRY_WAIT, attemptCount = 1)
        repo.updateState("r1", ForwardState.SUBMITTING, attemptCount = 2)
        val q = repo.currentQuota(now + 1)
        assertThat(q.sourceMessagesUsed).isEqualTo(1)
        assertThat(q.outboundSegmentsUsed).isEqualTo(2)
    }

    @Test
    fun recordPartResult_allSuccess_becomesSentAndPurgesPayload() = runBlocking {
        repo.enqueue(job(id = "s1", segmentCount = 2))
        repo.updateState("s1", ForwardState.SUBMITTING)

        repo.recordPartResult(
            PartSendResult("s1", partIndex = 0, partCount = 2, resultCode = -1, isTransient = false, receivedAtMillis = 100),
        )
        val mid = repo.getJob("s1")!!
        assertThat(mid.state).isEqualTo(ForwardState.SUBMITTING)
        assertThat(mid.body).isEqualTo("secret body")

        val final = repo.recordPartResult(
            PartSendResult("s1", partIndex = 1, partCount = 2, resultCode = -1, isTransient = false, receivedAtMillis = 101),
        )!!
        assertThat(final.state).isEqualTo(ForwardState.SENT)
        assertThat(final.body).isEmpty()
        assertThat(final.sender).isNull()
        assertThat(final.destinationE164).isEmpty()

        val raw = db.forwardJobDao().getById("s1")!!
        assertThat(raw.payloadsPurged).isTrue()
        assertThat(raw.bodyCipher).isNull()
    }

    @Test
    fun recordPartResult_partialNeverRetries() = runBlocking {
        repo.enqueue(job(id = "p1", segmentCount = 2))
        repo.recordPartResult(
            PartSendResult("p1", 0, 2, resultCode = -1, isTransient = false, receivedAtMillis = 1),
        )
        val final = repo.recordPartResult(
            PartSendResult("p1", 1, 2, resultCode = 1, isTransient = true, receivedAtMillis = 2),
        )!!
        assertThat(final.state).isEqualTo(ForwardState.PARTIAL)
        assertThat(final.lastErrorCategory).isEqualTo(ErrorCategory.PARTIAL_SEND)
    }

    @Test
    fun recordPartResult_retriedAttempt_doesNotMixWithPriorAttemptResults() = runBlocking {
        // Attempt 1: Part 0 succeeds, Part 1 fails transiently -> PARTIAL (terminal)
        // For RETRY_WAIT, both parts must fail transiently.
        repo.enqueue(job(id = "m1", segmentCount = 2))
        repo.updateState("m1", ForwardState.SUBMITTING, attemptCount = 1)
        repo.recordPartResult(
            PartSendResult("m1", 0, 2, resultCode = SmsManager.RESULT_ERROR_GENERIC_FAILURE, isTransient = true, receivedAtMillis = 10),
        )
        val attempt1Final = repo.recordPartResult(
            PartSendResult("m1", 1, 2, resultCode = SmsManager.RESULT_ERROR_GENERIC_FAILURE, isTransient = true, receivedAtMillis = 11),
        )!!
        assertThat(attempt1Final.state).isEqualTo(ForwardState.RETRY_WAIT)

        // Attempt 2: State updated to SUBMITTING (attempt 2). Part 0 fails transiently, Part 1 succeeds.
        repo.updateState("m1", ForwardState.SUBMITTING, attemptCount = 2)
        repo.recordPartResult(
            PartSendResult("m1", 0, 2, resultCode = SmsManager.RESULT_ERROR_GENERIC_FAILURE, isTransient = true, receivedAtMillis = 20),
        )
        val attempt2Final = repo.recordPartResult(
            PartSendResult("m1", 1, 2, resultCode = -1, isTransient = false, receivedAtMillis = 21),
        )!!
        // Should NOT conclude SENT because Part 0 failed in attempt 2.
        assertThat(attempt2Final.state).isNotEqualTo(ForwardState.SENT)
    }

    @Test
    fun claimForSubmission_atomicallyClaimsJob() = runBlocking {
        repo.enqueue(job(id = "c1", state = ForwardState.QUEUED, segmentCount = 1))
        
        // First claim succeeds
        val claimed1 = repo.claimForSubmission("c1", fromStates = setOf(ForwardState.QUEUED), targetAttemptCount = 1)
        assertThat(claimed1).isTrue()
        
        val loaded = repo.getJob("c1")!!
        assertThat(loaded.state).isEqualTo(ForwardState.SUBMITTING)
        assertThat(loaded.attemptCount).isEqualTo(1)

        // Second claim fails because state is now SUBMITTING
        val claimed2 = repo.claimForSubmission("c1", fromStates = setOf(ForwardState.QUEUED), targetAttemptCount = 2)
        assertThat(claimed2).isFalse()
    }

    @Test
    fun recordPartResult_allTransient_goesToRetryWait() = runBlocking {
        repo.enqueue(job(id = "t1", segmentCount = 1))
        val final = repo.recordPartResult(
            PartSendResult(
                "t1",
                0,
                1,
                resultCode = SmsManager.RESULT_ERROR_RADIO_OFF,
                isTransient = true,
                receivedAtMillis = 5,
            ),
        )!!
        assertThat(final.state).isEqualTo(ForwardState.RETRY_WAIT)
        assertThat(final.lastErrorCategory).isEqualTo(ErrorCategory.TRANSIENT_RADIO)
        // Not terminal — payload retained
        assertThat(final.body).isEqualTo("secret body")
    }

    @Test
    fun recordPartResult_simBusy_preservesSpecificCategory() = runBlocking {
        repo.enqueue(job(id = "sim1", segmentCount = 1))
        val final = repo.recordPartResult(
            PartSendResult(
                "sim1",
                0,
                1,
                resultCode = SmsManager.RESULT_RIL_SIM_BUSY,
                isTransient = true,
                receivedAtMillis = 5,
            ),
        )!!
        assertThat(final.state).isEqualTo(ForwardState.RETRY_WAIT)
        assertThat(final.lastErrorCategory).isEqualTo(ErrorCategory.SIM_BUSY)
    }

    @Test
    fun isSuccessCode_onlyResultOk() {
        assertThat(RoomForwardJobRepository.isSuccessCode(Activity.RESULT_OK)).isTrue()
        assertThat(RoomForwardJobRepository.isSuccessCode(0)).isFalse()
        assertThat(RoomForwardJobRepository.isSuccessCode(1)).isFalse()
    }

    @Test
    fun observeRecent_metadataOnly() {
        runBlocking {
            repo.enqueue(job(id = "o1", segmentCount = 1))
            repo.recordPartResult(
                PartSendResult("o1", 0, 1, resultCode = -1, isTransient = false, receivedAtMillis = 50),
            )
            repo.observeRecent(50).test {
                val list = awaitItem()
                assertThat(list).hasSize(1)
                assertThat(list[0].jobId).isEqualTo("o1")
                assertThat(list[0].state).isEqualTo(ForwardState.SENT)
                assertThat(list[0].segmentCount).isEqualTo(1)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun pruneTerminalOutcomes_keepsNewestMetadata_andPreservesQuotaLedger() = runBlocking {
        repeat(3) { index ->
            val id = "terminal-$index"
            repo.enqueue(job(id = id, segmentCount = 1))
            repo.updateState(id, ForwardState.SENT)
        }

        repo.pruneTerminalOutcomes(keep = 1)

        repo.observeRecent(10).test {
            assertThat(awaitItem()).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(repo.currentQuota(System.currentTimeMillis()).sourceMessagesUsed).isEqualTo(3)
    }

    @Test
    fun purgeUnsentJobs_removesQueuedAndReleasesQuota() = runBlocking {
        val now = 40_000_000L
        repo.enqueue(job(id = "u1", createdAt = now, segmentCount = 3))
        repo.enqueue(job(id = "u2", createdAt = now + 1, segmentCount = 1))
        repo.recordPartResult(
            PartSendResult("u2", 0, 1, resultCode = -1, isTransient = false, receivedAtMillis = now + 2),
        )
        repo.purgeUnsentJobs()
        assertThat(repo.getJob("u1")).isNull()
        assertThat(repo.getJob("u2")!!.state).isEqualTo(ForwardState.SENT)
        val q = repo.currentQuota(now + 3)
        assertThat(q.sourceMessagesUsed).isEqualTo(1)
        assertThat(q.outboundSegmentsUsed).isEqualTo(1)
    }

    @Test
    fun ttl_expiresQueuedJobsAfter24h() = runBlocking {
        val created = 50_000_000L
        repo.enqueue(job(id = "ttl", createdAt = created, segmentCount = 1))
        val afterTtl = created + DataLayerConstants.JOB_TTL_MS + 1
        // currentQuota triggers expiry
        repo.currentQuota(afterTtl)
        val expired = repo.getJob("ttl")!!
        assertThat(expired.state).isEqualTo(ForwardState.FAILED)
        assertThat(expired.lastErrorCategory).isEqualTo(ErrorCategory.EXPIRED_TTL)
        assertThat(expired.body).isEmpty()
    }

    @Test
    fun quotaWindow_rollsOffAfter24h() = runBlocking {
        val t0 = 60_000_000L
        repo.enqueue(job(id = "old", createdAt = t0, segmentCount = 10))
        val later = t0 + DataLayerConstants.QUOTA_WINDOW_MS + 1
        val q = repo.currentQuota(later)
        assertThat(q.sourceMessagesUsed).isEqualTo(0)
        assertThat(q.outboundSegmentsUsed).isEqualTo(0)
    }

    @Test
    fun listByStates_returnsMatching() {
        runBlocking {
            repo.enqueue(job(id = "a", segmentCount = 1))
            repo.enqueue(job(id = "b", segmentCount = 1))
            repo.updateState("b", ForwardState.RETRY_WAIT, 1)
            val queued = repo.listByStates(setOf(ForwardState.QUEUED))
            assertThat(queued.map { it.id }).containsExactly("a")
            val retry = repo.listByStates(setOf(ForwardState.RETRY_WAIT))
            assertThat(retry.map { it.id }).containsExactly("b")
            Unit
        }
    }
}

package com.johnc4rl0.smsforwarder.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.johnc4rl0.smsforwarder.data.crypto.AndroidKeystoreCryptoVault
import com.johnc4rl0.smsforwarder.data.db.AppDatabase
import com.johnc4rl0.smsforwarder.data.db.RoomForwardJobRepository
import com.johnc4rl0.smsforwarder.domain.model.ForwardJob
import com.johnc4rl0.smsforwarder.domain.model.ForwardState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Room + Keystore encryption on device SQLite (not Robolectric).
 */
@RunWith(AndroidJUnit4::class)
class RoomForwardJobRepositoryInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: RoomForwardJobRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = AndroidKeystoreCryptoVault().also { it.ensureKeys() }
        db = AppDatabase.buildInMemory(context)
        repo = RoomForwardJobRepository(db, vault)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun enqueueEncryptsSensitiveFieldsAtRest() = runBlocking {
        val job = sampleJob(body = "Hello from device", sender = "+15550001111")
        repo.enqueue(job)

        val entity = db.forwardJobDao().getById(job.id)!!
        assertThat(entity.bodyCipher).isNotNull()
        assertThat(entity.bodyIv).isNotNull()
        assertThat(entity.senderCipher).isNotNull()
        assertThat(entity.destinationCipher).isNotNull()
        // Ciphertext must not contain plaintext body bytes.
        val bodyUtf8 = job.body.toByteArray(Charsets.UTF_8)
        assertThat(entity.bodyCipher!!.asList()).isNotEqualTo(bodyUtf8.asList())

        val loaded = repo.getJob(job.id)!!
        assertThat(loaded.body).isEqualTo(job.body)
        assertThat(loaded.sender).isEqualTo(job.sender)
        assertThat(loaded.destinationE164).isEqualTo(job.destinationE164)
        assertThat(loaded.state).isEqualTo(ForwardState.QUEUED)
    }

    @Test
    fun quotaTracksSourceMessages() = runBlocking {
        val now = System.currentTimeMillis()
        repo.enqueue(sampleJob(id = "q1", createdAtMillis = now, segmentCount = 1))
        repo.enqueue(sampleJob(id = "q2", createdAtMillis = now + 1, segmentCount = 2))
        val snap = repo.currentQuota(now + 2)
        assertThat(snap.sourceMessagesUsed).isEqualTo(2)
        assertThat(snap.outboundSegmentsUsed).isEqualTo(3)
    }

    private fun sampleJob(
        id: String = UUID.randomUUID().toString(),
        body: String = "body",
        sender: String? = "+10000000000",
        createdAtMillis: Long = System.currentTimeMillis(),
        segmentCount: Int? = 1,
    ) = ForwardJob(
        id = id,
        state = ForwardState.QUEUED,
        configRevision = 1L,
        sourceSubscriptionId = 1,
        outboundSubscriptionId = 2,
        sender = sender,
        body = body,
        destinationE164 = "+15559876543",
        createdAtMillis = createdAtMillis,
        attemptCount = 0,
        segmentCount = segmentCount,
    )
}

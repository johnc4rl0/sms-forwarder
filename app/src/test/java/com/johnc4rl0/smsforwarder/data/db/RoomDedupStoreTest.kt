package com.johnc4rl0.smsforwarder.data.db

import com.google.common.truth.Truth.assertThat
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
class RoomDedupStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var store: RoomDedupStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = AppDatabase.buildInMemory(context)
        store = RoomDedupStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun remember_and_seenRecently() {
        runBlocking {
            val fp = ByteArray(32) { it.toByte() }
            val now = System.currentTimeMillis()
            assertThat(store.seenRecently(fp)).isFalse()
            store.remember(fp, nowMillis = now)
            assertThat(store.seenRecently(fp)).isTrue()
            assertThat(store.seenRecently(ByteArray(32) { 0 })).isFalse()
        }
    }

    @Test
    fun checkAndRemember_atomicallyPreventsDuplicates() {
        runBlocking {
            val fp = ByteArray(32) { it.toByte() }
            val now = System.currentTimeMillis()
            assertThat(store.checkAndRemember(fp, nowMillis = now)).isFalse()
            assertThat(store.checkAndRemember(fp, nowMillis = now)).isTrue()
            assertThat(store.seenRecently(fp)).isTrue()
        }
    }

    @Test
    fun purgeExpired_removesOldFingerprints() {
        runBlocking {
            val fp = ByteArray(32) { 7 }
            val now = System.currentTimeMillis()
            store.remember(fp, nowMillis = now)
            assertThat(store.seenRecently(fp)).isTrue()
            // Still within window
            store.purgeExpired(now + DataLayerConstants.DEDUP_RETENTION_MS - 1)
            assertThat(store.seenRecently(fp)).isTrue()
            // After expiry, purge removes row; seenRecently also treats expired as absent
            val after = now + DataLayerConstants.DEDUP_RETENTION_MS + 1
            store.purgeExpired(after)
            assertThat(store.seenRecently(fp)).isFalse()
        }
    }
}

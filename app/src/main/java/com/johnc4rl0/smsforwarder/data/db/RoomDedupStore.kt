package com.johnc4rl0.smsforwarder.data.db

import androidx.room.withTransaction
import com.johnc4rl0.smsforwarder.domain.DedupStore
import java.util.Base64

/**
 * Room-backed 24-hour fingerprint store for duplicate SMS suppression.
 * Fingerprints are expected to already be HMAC-SHA256 digests from the crypto layer.
 */
class RoomDedupStore(
    private val db: AppDatabase,
) : DedupStore {

    private val dao get() = db.dedupDao()

    override suspend fun seenRecently(fingerprint: ByteArray): Boolean {
        val now = System.currentTimeMillis()
        return dao.countActive(fingerprint.toKey(), now) > 0
    }

    override suspend fun checkAndRemember(fingerprint: ByteArray, nowMillis: Long): Boolean =
        db.withTransaction {
            val key = fingerprint.toKey()
            if (dao.countActive(key, nowMillis) > 0) {
                true
            } else {
                dao.upsert(
                    DedupEntity(
                        fingerprintKey = key,
                        rememberedAtMillis = nowMillis,
                        expiresAtMillis = nowMillis + DataLayerConstants.DEDUP_RETENTION_MS,
                    ),
                )
                false
            }
        }

    override suspend fun remember(fingerprint: ByteArray, nowMillis: Long) {
        dao.upsert(
            DedupEntity(
                fingerprintKey = fingerprint.toKey(),
                rememberedAtMillis = nowMillis,
                expiresAtMillis = nowMillis + DataLayerConstants.DEDUP_RETENTION_MS,
            ),
        )
    }

    override suspend fun purgeExpired(nowMillis: Long) {
        dao.purgeExpired(nowMillis)
    }

    companion object {
        private fun ByteArray.toKey(): String =
            Base64.getEncoder().encodeToString(this)
    }
}

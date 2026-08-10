package com.johnc4rl0.smsforwarder.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Encrypted-at-rest forward job row.
 * Sender/body/destination ciphertext columns are nulled after terminal purge.
 */
@Entity(
    tableName = "forward_jobs",
    indices = [
        Index(value = ["state"]),
        Index(value = ["createdAtMillis"]),
        Index(value = ["finishedAtMillis"]),
    ],
)
data class ForwardJobEntity(
    @PrimaryKey val id: String,
    val state: String,
    val configRevision: Long,
    val sourceSubscriptionId: Int,
    val outboundSubscriptionId: Int,
    /** AES-GCM ciphertext; null after purge. */
    val senderCipher: ByteArray?,
    val senderIv: ByteArray?,
    val bodyCipher: ByteArray?,
    val bodyIv: ByteArray?,
    val destinationCipher: ByteArray?,
    val destinationIv: ByteArray?,
    val createdAtMillis: Long,
    val attemptCount: Int,
    val segmentCount: Int?,
    val lastErrorCategory: String?,
    val nextAttemptAtMillis: Long?,
    /** Set when job reaches a terminal outcome. */
    val finishedAtMillis: Long?,
    val payloadsPurged: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ForwardJobEntity) return false
        return id == other.id &&
            state == other.state &&
            configRevision == other.configRevision &&
            sourceSubscriptionId == other.sourceSubscriptionId &&
            outboundSubscriptionId == other.outboundSubscriptionId &&
            blobEq(senderCipher, other.senderCipher) &&
            blobEq(senderIv, other.senderIv) &&
            blobEq(bodyCipher, other.bodyCipher) &&
            blobEq(bodyIv, other.bodyIv) &&
            blobEq(destinationCipher, other.destinationCipher) &&
            blobEq(destinationIv, other.destinationIv) &&
            createdAtMillis == other.createdAtMillis &&
            attemptCount == other.attemptCount &&
            segmentCount == other.segmentCount &&
            lastErrorCategory == other.lastErrorCategory &&
            nextAttemptAtMillis == other.nextAttemptAtMillis &&
            finishedAtMillis == other.finishedAtMillis &&
            payloadsPurged == other.payloadsPurged
    }

    private fun blobEq(a: ByteArray?, b: ByteArray?): Boolean =
        when {
            a === b -> true
            a == null || b == null -> false
            else -> a.contentEquals(b)
        }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + configRevision.hashCode()
        result = 31 * result + sourceSubscriptionId
        result = 31 * result + outboundSubscriptionId
        result = 31 * result + (senderCipher?.contentHashCode() ?: 0)
        result = 31 * result + (senderIv?.contentHashCode() ?: 0)
        result = 31 * result + (bodyCipher?.contentHashCode() ?: 0)
        result = 31 * result + (bodyIv?.contentHashCode() ?: 0)
        result = 31 * result + (destinationCipher?.contentHashCode() ?: 0)
        result = 31 * result + (destinationIv?.contentHashCode() ?: 0)
        result = 31 * result + createdAtMillis.hashCode()
        result = 31 * result + attemptCount
        result = 31 * result + (segmentCount ?: 0)
        result = 31 * result + (lastErrorCategory?.hashCode() ?: 0)
        result = 31 * result + (nextAttemptAtMillis?.hashCode() ?: 0)
        result = 31 * result + (finishedAtMillis?.hashCode() ?: 0)
        result = 31 * result + payloadsPurged.hashCode()
        return result
    }
}

/** Per-segment send callback rows for multipart aggregation. */
@Entity(
    tableName = "part_results",
    primaryKeys = ["jobId", "attemptNumber", "partIndex"],
    indices = [Index(value = ["jobId"])],
)
data class PartResultEntity(
    val jobId: String,
    val attemptNumber: Int = 0,
    val partIndex: Int,
    val partCount: Int,
    val resultCode: Int,
    val isTransient: Boolean,
    val receivedAtMillis: Long,
)

/**
 * Rolling quota ledger: one row per admitted job.
 * Retries do not insert additional rows.
 */
@Entity(
    tableName = "quota_events",
    indices = [Index(value = ["reservedAtMillis"])],
)
data class QuotaEventEntity(
    @PrimaryKey val jobId: String,
    val reservedAtMillis: Long,
    val sourceMessages: Int,
    val outboundSegments: Int,
)

/** 24h fingerprint retention for duplicate suppression. */
@Entity(
    tableName = "dedup_fingerprints",
    indices = [Index(value = ["expiresAtMillis"])],
)
data class DedupEntity(
    /** Base64 of HMAC fingerprint (string PK — Room ByteArray PK equality is unreliable). */
    @PrimaryKey val fingerprintKey: String,
    val rememberedAtMillis: Long,
    val expiresAtMillis: Long,
)

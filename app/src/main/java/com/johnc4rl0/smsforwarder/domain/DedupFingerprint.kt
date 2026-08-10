package com.johnc4rl0.smsforwarder.domain

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Pure preimage construction for keyed HMAC-SHA256 dedup fingerprints.
 *
 * Fingerprint inputs (spec): source subscription, sender, service timestamp, raw PDUs.
 * The HMAC itself is injected (Keystore-backed in production); this object only builds
 * a stable canonical preimage and applies a provided MAC function.
 */
object DedupFingerprint {
    private val FIELD_SEP: Byte = 0x1F
    private val PDU_SEP: Byte = 0x1E

    /**
     * Canonical preimage bytes:
     * `subscriptionId | sender | serviceTimestamp | pdu0 | pdu1 | ...`
     * using unit-separator style delimiters so fields cannot collide.
     */
    fun buildPreimage(
        sourceSubscriptionId: Int,
        sender: String?,
        serviceTimestampMillis: Long?,
        rawPdus: List<ByteArray>,
    ): ByteArray {
        val senderBytes = (sender ?: "").toByteArray(StandardCharsets.UTF_8)
        val pduBytes = rawPdus.sumOf { 1 + 4 + it.size }
        val capacity = 4 + 1 + senderBytes.size + 1 + 8 + 1 + 4 + pduBytes
        val buffer = ByteBuffer.allocate(capacity)
        buffer.putInt(sourceSubscriptionId)
        buffer.put(FIELD_SEP)
        buffer.put(senderBytes)
        buffer.put(FIELD_SEP)
        buffer.putLong(serviceTimestampMillis ?: 0L)
        buffer.put(FIELD_SEP)
        buffer.putInt(rawPdus.size)
        for (pdu in rawPdus) {
            buffer.put(PDU_SEP)
            buffer.putInt(pdu.size)
            buffer.put(pdu)
        }
        val out = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(out)
        return out
    }

    /** Apply [mac] to the canonical preimage. */
    fun fingerprint(
        sourceSubscriptionId: Int,
        sender: String?,
        serviceTimestampMillis: Long?,
        rawPdus: List<ByteArray>,
        mac: (ByteArray) -> ByteArray,
    ): ByteArray {
        val preimage = buildPreimage(sourceSubscriptionId, sender, serviceTimestampMillis, rawPdus)
        return mac(preimage)
    }
}

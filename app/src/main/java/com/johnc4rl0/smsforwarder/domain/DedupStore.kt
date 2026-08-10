package com.johnc4rl0.smsforwarder.domain

/**
 * 24-hour keyed fingerprint store for duplicate SMS suppression.
 * Fingerprints use HMAC-SHA256 over source subscription, sender, service timestamp, and raw PDUs.
 */
interface DedupStore {
    /**
     * @return true if this fingerprint was already seen within the retention window.
     */
    suspend fun seenRecently(fingerprint: ByteArray): Boolean

    /**
     * Atomically check if [fingerprint] is active. If active, returns true.
     * If not active, records [fingerprint] with expiry = now + 24h and returns false.
     */
    suspend fun checkAndRemember(fingerprint: ByteArray, nowMillis: Long): Boolean

    /** Record [fingerprint] with expiry = now + 24h. */
    suspend fun remember(fingerprint: ByteArray, nowMillis: Long)

    suspend fun purgeExpired(nowMillis: Long)
}

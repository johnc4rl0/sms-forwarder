package com.johnc4rl0.smsforwarder.data.crypto

/**
 * AES-256-GCM string encryption with Android Keystore-backed keys.
 * Fresh nonce per value. Key must not require per-use auth so work can run while locked
 * after first post-reboot unlock.
 */
interface SecureStringCipher {
    fun encrypt(plaintext: String): EncryptedBlob

    fun decrypt(blob: EncryptedBlob): String

    /** True when Keystore material is usable. */
    fun isAvailable(): Boolean
}

/** Opaque ciphertext + IV for storage (Room/DataStore). */
data class EncryptedBlob(
    val ciphertext: ByteArray,
    val iv: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedBlob) return false
        return ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv)
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        return result
    }
}

/**
 * Separate Keystore HMAC-SHA256 key for deduplication fingerprints and verification codes.
 */
interface SecureHmac {
    fun mac(data: ByteArray): ByteArray

    fun isAvailable(): Boolean
}

/**
 * Factory / lifecycle for Keystore-backed crypto. On permanent failure, data layer purges
 * encrypted state and returns the user to onboarding.
 */
interface CryptoVault {
    val stringCipher: SecureStringCipher
    val hmac: SecureHmac

    fun ensureKeys()

    /** Wipe app Keystore aliases (e.g. corruption recovery). */
    fun destroyKeys()
}

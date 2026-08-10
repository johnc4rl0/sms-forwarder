package com.johnc4rl0.smsforwarder.data.crypto

/**
 * Compile-only crypto stubs. Data agent replaces with Keystore AES-GCM + HMAC.
 */
class StubSecureStringCipher : SecureStringCipher {
    override fun encrypt(plaintext: String): EncryptedBlob =
        EncryptedBlob(
            ciphertext = plaintext.toByteArray(Charsets.UTF_8),
            iv = ByteArray(12),
        )

    override fun decrypt(blob: EncryptedBlob): String =
        blob.ciphertext.toString(Charsets.UTF_8)

    override fun isAvailable(): Boolean = false
}

class StubSecureHmac : SecureHmac {
    override fun mac(data: ByteArray): ByteArray = data.copyOf(minOf(32, data.size)).copyOf(32)

    override fun isAvailable(): Boolean = false
}

class StubCryptoVault : CryptoVault {
    override val stringCipher: SecureStringCipher = StubSecureStringCipher()
    override val hmac: SecureHmac = StubSecureHmac()

    override fun ensureKeys() {
        // no-op
    }

    override fun destroyKeys() {
        // no-op
    }
}

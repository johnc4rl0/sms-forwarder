package com.johnc4rl0.smsforwarder.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed AES-256-GCM + HMAC-SHA256.
 *
 * Keys do **not** require per-use user authentication so forwarding can run after first
 * post-reboot unlock while the screen is locked (credential-protected storage).
 */
class AndroidKeystoreCryptoVault : CryptoVault {

    override val stringCipher: SecureStringCipher = KeystoreSecureStringCipher()
    override val hmac: SecureHmac = KeystoreSecureHmac()

    override fun ensureKeys() {
        ensureAesKey()
        ensureHmacKey()
    }

    override fun destroyKeys() {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(AES_ALIAS)) {
            keyStore.deleteEntry(AES_ALIAS)
        }
        if (keyStore.containsAlias(HMAC_ALIAS)) {
            keyStore.deleteEntry(HMAC_ALIAS)
        }
    }

    /** True only when both aliases already exist; does not create replacement keys. */
    fun hasKeyMaterial(): Boolean = try {
        val keyStore = loadKeyStore()
        keyStore.containsAlias(AES_ALIAS) && keyStore.containsAlias(HMAC_ALIAS)
    } catch (_: Exception) {
        false
    }

    private class KeystoreSecureStringCipher : SecureStringCipher {
        override fun encrypt(plaintext: String): EncryptedBlob {
            val key = getAesKeyOrThrow()
            val cipher = Cipher.getInstance(AES_TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return EncryptedBlob(ciphertext = ciphertext, iv = iv)
        }

        override fun decrypt(blob: EncryptedBlob): String {
            val key = getAesKeyOrThrow()
            val cipher = Cipher.getInstance(AES_TRANSFORM)
            val spec = GCMParameterSpec(GCM_TAG_BITS, blob.iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val plain = cipher.doFinal(blob.ciphertext)
            return plain.toString(Charsets.UTF_8)
        }

        override fun isAvailable(): Boolean =
            try {
                getAesKeyOrThrow()
                true
            } catch (_: Exception) {
                false
            }
    }

    private class KeystoreSecureHmac : SecureHmac {
        override fun mac(data: ByteArray): ByteArray {
            val key = getHmacKeyOrThrow()
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(key)
            return mac.doFinal(data)
        }

        override fun isAvailable(): Boolean =
            try {
                getHmacKeyOrThrow()
                true
            } catch (_: Exception) {
                false
            }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_ALIAS = "com.johnc4rl0.smsforwarder.AES_GCM_V1"
        const val HMAC_ALIAS = "com.johnc4rl0.smsforwarder.HMAC_SHA256_V1"
        private const val AES_TRANSFORM = "AES/GCM/NoPadding"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val GCM_TAG_BITS = 128
        private const val AES_KEY_BITS = 256
        private const val HMAC_KEY_BITS = 256

        private fun loadKeyStore(): KeyStore =
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        private fun ensureAesKey() {
            val keyStore = loadKeyStore()
            if (keyStore.containsAlias(AES_ALIAS)) return
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            )
            val spec = KeyGenParameterSpec.Builder(
                AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }

        private fun ensureHmacKey() {
            val keyStore = loadKeyStore()
            if (keyStore.containsAlias(HMAC_ALIAS)) return
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                ANDROID_KEYSTORE,
            )
            val spec = KeyGenParameterSpec.Builder(
                HMAC_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setKeySize(HMAC_KEY_BITS)
                .setUserAuthenticationRequired(false)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }

        private fun getAesKeyOrThrow(): SecretKey {
            val keyStore = loadKeyStore()
            val entry = keyStore.getEntry(AES_ALIAS, null) as? KeyStore.SecretKeyEntry
                ?: error("AES key missing from Keystore")
            return entry.secretKey
        }

        private fun getHmacKeyOrThrow(): SecretKey {
            val keyStore = loadKeyStore()
            val entry = keyStore.getEntry(HMAC_ALIAS, null) as? KeyStore.SecretKeyEntry
                ?: error("HMAC key missing from Keystore")
            return entry.secretKey
        }
    }
}

/**
 * Software AES-256-GCM + HMAC-SHA256 for unit tests and non-Keystore environments.
 * Not for production use with real message content.
 */
class SoftwareCryptoVault(
    aesKeyBytes: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
    hmacKeyBytes: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
) : CryptoVault {

    private val aesKey: SecretKey = javax.crypto.spec.SecretKeySpec(aesKeyBytes, "AES")
    private val hmacKey: SecretKey = javax.crypto.spec.SecretKeySpec(hmacKeyBytes, "HmacSHA256")
    private val random = SecureRandom()

    private var destroyed = false

    override val stringCipher: SecureStringCipher = object : SecureStringCipher {
        override fun encrypt(plaintext: String): EncryptedBlob {
            checkAvailable()
            val iv = ByteArray(12).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return EncryptedBlob(ciphertext = ciphertext, iv = iv)
        }

        override fun decrypt(blob: EncryptedBlob): String {
            checkAvailable()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, blob.iv))
            return cipher.doFinal(blob.ciphertext).toString(Charsets.UTF_8)
        }

        override fun isAvailable(): Boolean = !destroyed
    }

    override val hmac: SecureHmac = object : SecureHmac {
        override fun mac(data: ByteArray): ByteArray {
            checkAvailable()
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(hmacKey)
            return mac.doFinal(data)
        }

        override fun isAvailable(): Boolean = !destroyed
    }

    override fun ensureKeys() {
        destroyed = false
    }

    override fun destroyKeys() {
        destroyed = true
    }

    private fun checkAvailable() {
        check(!destroyed) { "Crypto keys destroyed" }
    }
}

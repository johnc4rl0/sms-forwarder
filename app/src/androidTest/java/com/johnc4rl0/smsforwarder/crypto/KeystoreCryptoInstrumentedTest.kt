package com.johnc4rl0.smsforwarder.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.johnc4rl0.smsforwarder.data.crypto.AndroidKeystoreCryptoVault
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Real Android Keystore AES-GCM + HMAC on device (not SoftwareCryptoVault).
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCryptoInstrumentedTest {

    private lateinit var vault: AndroidKeystoreCryptoVault

    @Before
    fun setUp() {
        vault = AndroidKeystoreCryptoVault()
        vault.ensureKeys()
    }

    @After
    fun tearDown() {
        // Leave keys; other tests may reuse. Destroy only if this class created unique aliases.
        // Vault uses app-wide aliases — do not destroyKeys() here or we race parallel tests.
    }

    @Test
    fun encryptDecryptRoundTrip_usesFreshNonce() {
        val plain = "secret-+15551234567-${UUID.randomUUID()}"
        val a = vault.stringCipher.encrypt(plain)
        val b = vault.stringCipher.encrypt(plain)
        assertThat(a.iv).isNotEqualTo(b.iv)
        assertThat(a.ciphertext).isNotEqualTo(b.ciphertext)
        assertThat(vault.stringCipher.decrypt(a)).isEqualTo(plain)
        assertThat(vault.stringCipher.decrypt(b)).isEqualTo(plain)
        assertThat(vault.stringCipher.isAvailable()).isTrue()
    }

    @Test
    fun hmacIsDeterministicAndAvailable() {
        val data = "sub:1|sender:+1|ts:1|pdu".toByteArray()
        val m1 = vault.hmac.mac(data)
        val m2 = vault.hmac.mac(data)
        assertThat(m1).isEqualTo(m2)
        assertThat(m1.size).isEqualTo(32)
        assertThat(vault.hmac.isAvailable()).isTrue()
    }

    @Test
    fun ensureKeysIsIdempotent() {
        vault.ensureKeys()
        vault.ensureKeys()
        assertThat(vault.stringCipher.isAvailable()).isTrue()
        assertThat(vault.hmac.isAvailable()).isTrue()
    }
}

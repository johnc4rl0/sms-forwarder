package com.johnc4rl0.smsforwarder.data.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SoftwareCryptoVaultTest {

    @Test
    fun encryptDecrypt_roundTrip() {
        val vault = SoftwareCryptoVault(
            aesKeyBytes = ByteArray(32) { 1 },
            hmacKeyBytes = ByteArray(32) { 2 },
        )
        vault.ensureKeys()
        val blob = vault.stringCipher.encrypt("hello +15551234567 secret")
        assertThat(blob.iv).hasLength(12)
        assertThat(blob.ciphertext).isNotEmpty()
        // Ciphertext must not contain plaintext
        assertThat(blob.ciphertext.toString(Charsets.UTF_8)).doesNotContain("15551234567")
        assertThat(vault.stringCipher.decrypt(blob)).isEqualTo("hello +15551234567 secret")
    }

    @Test
    fun encrypt_usesFreshNonceEachTime() {
        val vault = SoftwareCryptoVault(
            aesKeyBytes = ByteArray(32) { 3 },
            hmacKeyBytes = ByteArray(32) { 4 },
        )
        val a = vault.stringCipher.encrypt("same")
        val b = vault.stringCipher.encrypt("same")
        assertThat(a.iv.contentEquals(b.iv)).isFalse()
        assertThat(a.ciphertext.contentEquals(b.ciphertext)).isFalse()
    }

    @Test
    fun hmac_isDeterministicAndLength32() {
        val vault = SoftwareCryptoVault(
            aesKeyBytes = ByteArray(32) { 5 },
            hmacKeyBytes = ByteArray(32) { 6 },
        )
        val data = "sub|sender|ts|pdu".toByteArray()
        val m1 = vault.hmac.mac(data)
        val m2 = vault.hmac.mac(data)
        assertThat(m1).hasLength(32)
        assertThat(m1.contentEquals(m2)).isTrue()
        assertThat(vault.hmac.mac("other".toByteArray()).contentEquals(m1)).isFalse()
    }

    @Test
    fun destroyKeys_makesCryptoUnavailable() {
        val vault = SoftwareCryptoVault(
            aesKeyBytes = ByteArray(32) { 7 },
            hmacKeyBytes = ByteArray(32) { 8 },
        )
        assertThat(vault.stringCipher.isAvailable()).isTrue()
        vault.destroyKeys()
        assertThat(vault.stringCipher.isAvailable()).isFalse()
        assertThat(vault.hmac.isAvailable()).isFalse()
        assertThrows(IllegalStateException::class.java) {
            vault.stringCipher.encrypt("x")
        }
        vault.ensureKeys()
        assertThat(vault.stringCipher.isAvailable()).isTrue()
        assertThat(vault.stringCipher.encrypt("x").let { vault.stringCipher.decrypt(it) })
            .isEqualTo("x")
    }
}

package com.johnc4rl0.smsforwarder.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.MessageDigest

class DedupFingerprintTest {

    private val mac: (ByteArray) -> ByteArray = { data ->
        MessageDigest.getInstance("SHA-256").digest(data)
    }

    @Test
    fun sameInputs_sameFingerprint() {
        val a = DedupFingerprint.fingerprint(1, "+1", 100L, listOf(byteArrayOf(9)), mac)
        val b = DedupFingerprint.fingerprint(1, "+1", 100L, listOf(byteArrayOf(9)), mac)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun differentSender_differentFingerprint() {
        val a = DedupFingerprint.fingerprint(1, "A", 100L, listOf(byteArrayOf(1)), mac)
        val b = DedupFingerprint.fingerprint(1, "B", 100L, listOf(byteArrayOf(1)), mac)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun differentSubscription_differentFingerprint() {
        val a = DedupFingerprint.fingerprint(1, "A", 100L, emptyList(), mac)
        val b = DedupFingerprint.fingerprint(2, "A", 100L, emptyList(), mac)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun differentPdu_differentFingerprint() {
        val a = DedupFingerprint.fingerprint(1, "A", 100L, listOf(byteArrayOf(1, 2)), mac)
        val b = DedupFingerprint.fingerprint(1, "A", 100L, listOf(byteArrayOf(1, 3)), mac)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun preimage_includesAllFields() {
        val pre = DedupFingerprint.buildPreimage(5, "s", 99L, listOf(byteArrayOf(7, 8)))
        assertThat(pre).isNotEmpty()
        // Changing any field changes preimage
        val pre2 = DedupFingerprint.buildPreimage(5, "s", 100L, listOf(byteArrayOf(7, 8)))
        assertThat(pre).isNotEqualTo(pre2)
    }

    @Test
    fun multipartSms_multiplePdus_doesNotOverflowBuffer() {
        val pdus2 = listOf(ByteArray(140) { 1 }, ByteArray(140) { 2 })
        val pdus3 = listOf(ByteArray(140) { 1 }, ByteArray(140) { 2 }, ByteArray(140) { 3 })
        val pdus5 = List(5) { i -> ByteArray(140) { (i + 1).toByte() } }

        val pre2 = DedupFingerprint.buildPreimage(1, "+15551234567", 1000L, pdus2)
        val pre3 = DedupFingerprint.buildPreimage(1, "+15551234567", 1000L, pdus3)
        val pre5 = DedupFingerprint.buildPreimage(1, "+15551234567", 1000L, pdus5)

        assertThat(pre2).isNotEmpty()
        assertThat(pre3).isNotEmpty()
        assertThat(pre5).isNotEmpty()
    }
}

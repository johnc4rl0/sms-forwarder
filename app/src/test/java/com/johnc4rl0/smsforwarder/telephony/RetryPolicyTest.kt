package com.johnc4rl0.smsforwarder.telephony

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun delays_areOneFiveThirtyMinutes() {
        assertThat(RetryPolicy.RETRY_DELAYS_MS.toList())
            .containsExactly(60_000L, 300_000L, 1_800_000L)
            .inOrder()
        assertThat(RetryPolicy.MAX_RETRIES_AFTER_INITIAL).isEqualTo(3)
        assertThat(RetryPolicy.MAX_ATTEMPTS).isEqualTo(4)
    }

    @Test
    fun delayAfterFailedAttempt_mapsCorrectly() {
        assertThat(RetryPolicy.delayAfterFailedAttempt(1)).isEqualTo(60_000L)
        assertThat(RetryPolicy.delayAfterFailedAttempt(2)).isEqualTo(300_000L)
        assertThat(RetryPolicy.delayAfterFailedAttempt(3)).isEqualTo(1_800_000L)
        assertThat(RetryPolicy.delayAfterFailedAttempt(4)).isNull()
    }

    @Test
    fun canAttempt_allowsInitialPlusThreeRetries() {
        assertThat(RetryPolicy.canAttempt(0)).isTrue()
        assertThat(RetryPolicy.canAttempt(3)).isTrue()
        assertThat(RetryPolicy.canAttempt(4)).isFalse()
    }

    @Test
    fun isExpired_after24Hours() {
        val created = 1_000_000L
        assertThat(RetryPolicy.isExpired(created, created + RetryPolicy.JOB_TTL_MS)).isFalse()
        assertThat(RetryPolicy.isExpired(created, created + RetryPolicy.JOB_TTL_MS + 1)).isTrue()
    }

    @Test
    fun callbackTimeout_is15Minutes() {
        assertThat(RetryPolicy.CALLBACK_TIMEOUT_MS).isEqualTo(15L * 60L * 1000L)
    }
}

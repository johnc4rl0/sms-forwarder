package com.johnc4rl0.smsforwarder.telephony

import android.app.Activity
import android.telephony.SmsManager
import com.google.common.truth.Truth.assertThat
import com.johnc4rl0.smsforwarder.domain.model.ErrorCategory
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SendResultClassifierTest {

    @Test
    fun success_isResultOk() {
        assertThat(SendResultClassifier.isSuccess(Activity.RESULT_OK)).isTrue()
        assertThat(SendResultClassifier.isSuccess(SmsManager.RESULT_ERROR_GENERIC_FAILURE)).isFalse()
    }

    @Test
    fun transient_includesRadioNoServiceSimBusyRetry() {
        assertThat(SendResultClassifier.isTransient(SmsManager.RESULT_ERROR_RADIO_OFF)).isTrue()
        assertThat(SendResultClassifier.isTransient(SmsManager.RESULT_ERROR_NO_SERVICE)).isTrue()
        assertThat(SendResultClassifier.isTransient(SmsManager.RESULT_RADIO_NOT_AVAILABLE)).isTrue()
        assertThat(SendResultClassifier.isTransient(SmsManager.RESULT_RIL_SIM_BUSY)).isTrue()
        assertThat(SendResultClassifier.isTransient(SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY)).isTrue()
    }

    @Test
    fun genericFailure_isNotTransient() {
        assertThat(SendResultClassifier.isTransient(SmsManager.RESULT_ERROR_GENERIC_FAILURE)).isFalse()
        assertThat(SendResultClassifier.toErrorCategory(SmsManager.RESULT_ERROR_GENERIC_FAILURE))
            .isEqualTo(ErrorCategory.POLICY_OR_GENERIC)
    }

    @Test
    fun classifyAggregate_completePartialRetryableFailed() {
        assertThat(
            SendResultClassifier.classifyAggregate(
                successCount = 3,
                allTransient = false,
                partCount = 3,
                receivedCount = 3,
            ),
        ).isEqualTo(AggregateSendOutcome.COMPLETE)

        assertThat(
            SendResultClassifier.classifyAggregate(
                successCount = 1,
                allTransient = false,
                partCount = 3,
                receivedCount = 3,
            ),
        ).isEqualTo(AggregateSendOutcome.PARTIAL)

        assertThat(
            SendResultClassifier.classifyAggregate(
                successCount = 0,
                allTransient = true,
                partCount = 2,
                receivedCount = 2,
            ),
        ).isEqualTo(AggregateSendOutcome.RETRYABLE)

        assertThat(
            SendResultClassifier.classifyAggregate(
                successCount = 0,
                allTransient = false,
                partCount = 2,
                receivedCount = 2,
            ),
        ).isEqualTo(AggregateSendOutcome.FAILED)

        assertThat(
            SendResultClassifier.classifyAggregate(
                successCount = 0,
                allTransient = true,
                partCount = 2,
                receivedCount = 1,
            ),
        ).isEqualTo(AggregateSendOutcome.INCOMPLETE)
    }
}

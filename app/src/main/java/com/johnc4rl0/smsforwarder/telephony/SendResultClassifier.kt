package com.johnc4rl0.smsforwarder.telephony

import android.app.Activity
import android.telephony.SmsManager
import com.johnc4rl0.smsforwarder.domain.model.ErrorCategory

/**
 * Maps SmsManager sent-result codes to retry eligibility and [ErrorCategory].
 *
 * Retry only when every segment is definitely transient (radio/no-service/SIM busy/send-fail-retry).
 * Generic and policy failures are not retried.
 */
object SendResultClassifier {

    fun isSuccess(resultCode: Int): Boolean = resultCode == Activity.RESULT_OK

    /**
     * Definitely transient carrier/radio conditions per spec.
     * Generic failure and short-code/policy codes return false.
     */
    fun isTransient(resultCode: Int): Boolean = when (resultCode) {
        SmsManager.RESULT_ERROR_RADIO_OFF,
        SmsManager.RESULT_ERROR_NO_SERVICE,
        SmsManager.RESULT_RADIO_NOT_AVAILABLE,
        SmsManager.RESULT_RIL_SIM_BUSY,
        SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY,
        -> true
        else -> false
    }

    fun toErrorCategory(resultCode: Int): ErrorCategory = when (resultCode) {
        SmsManager.RESULT_ERROR_RADIO_OFF,
        SmsManager.RESULT_RADIO_NOT_AVAILABLE,
        -> ErrorCategory.TRANSIENT_RADIO

        SmsManager.RESULT_ERROR_NO_SERVICE -> ErrorCategory.NO_SERVICE
        SmsManager.RESULT_RIL_SIM_BUSY -> ErrorCategory.SIM_BUSY
        SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY -> ErrorCategory.SEND_FAIL_RETRY
        else -> ErrorCategory.POLICY_OR_GENERIC
    }

    /**
     * Classify aggregate multipart outcome after all segment callbacks (or timeout).
     *
     * @param successCount segments with RESULT_OK
     * @param allTransient every non-success result was transient (only meaningful when successCount == 0)
     * @param partCount expected segments
     * @param receivedCount callbacks actually received
     */
    fun classifyAggregate(
        successCount: Int,
        allTransient: Boolean,
        partCount: Int,
        receivedCount: Int,
    ): AggregateSendOutcome {
        if (receivedCount < partCount) {
            // Incomplete callbacks handled by timeout path as UNKNOWN.
            return AggregateSendOutcome.INCOMPLETE
        }
        if (successCount == partCount) return AggregateSendOutcome.COMPLETE
        if (successCount > 0) return AggregateSendOutcome.PARTIAL
        // Zero successes: retry only if every failure is transient.
        return if (allTransient) AggregateSendOutcome.RETRYABLE else AggregateSendOutcome.FAILED
    }
}

enum class AggregateSendOutcome {
    COMPLETE,
    PARTIAL,
    RETRYABLE,
    FAILED,
    INCOMPLETE,
}

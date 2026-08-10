package com.johnc4rl0.smsforwarder.telephony

import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Intent extra parsing on device (OEM may supply subscription index extras).
 */
@RunWith(AndroidJUnit4::class)
class InboundSmsParserInstrumentedTest {

    @Test
    fun ignoresSlotAndPhoneExtrasAsSubscriptionId() {
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            putExtra("slot", 0)
            putExtra("phone", 1)
        }
        val subId = InboundSmsParser.resolveSubscriptionId(intent)
        assertThat(subId).isNull()
    }

    @Test
    fun acceptsExplicitSubscriptionExtra() {
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            putExtra("subscription", 42)
        }
        assertThat(InboundSmsParser.resolveSubscriptionId(intent)).isEqualTo(42)
    }

    @Test
    fun acceptsPlatformSubscriptionIndexExtraWhenPresent() {
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 7)
        }
        val resolved = InboundSmsParser.resolveSubscriptionId(intent)
        assertThat(resolved).isEqualTo(7)
    }
}

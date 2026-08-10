package com.johnc4rl0.smsforwarder.telephony

import android.content.Intent
import android.provider.Telephony
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class InboundSmsParserTest {

    @Test
    fun resolveSubscriptionId_fromSubscriptionExtra() {
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        intent.putExtra("subscription", 7)
        assertThat(InboundSmsParser.resolveSubscriptionId(intent)).isEqualTo(7)
    }

    @Test
    fun resolveSubscriptionId_absentReturnsNull() {
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        assertThat(InboundSmsParser.resolveSubscriptionId(intent)).isNull()
    }

    @Test
    fun resolveSubscriptionId_invalidReturnsNull() {
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        intent.putExtra("subscription", -1)
        assertThat(InboundSmsParser.resolveSubscriptionId(intent)).isNull()
    }

    @Test
    fun resolveSubscriptionId_ignoresSlotAndPhoneExtras() {
        // Slot/phone indices must never be treated as subscription ids.
        val slotOnly = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        slotOnly.putExtra("slot", 0)
        assertThat(InboundSmsParser.resolveSubscriptionId(slotOnly)).isNull()

        val phoneOnly = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        phoneOnly.putExtra("phone", 1)
        assertThat(InboundSmsParser.resolveSubscriptionId(phoneOnly)).isNull()

        // Real subscription extra still wins when present.
        val both = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        both.putExtra("slot", 0)
        both.putExtra("subscription", 42)
        assertThat(InboundSmsParser.resolveSubscriptionId(both)).isEqualTo(42)
    }

    @Test
    fun isValidSubscriptionId() {
        assertThat(InboundSmsParser.isValidSubscriptionId(0)).isTrue()
        assertThat(InboundSmsParser.isValidSubscriptionId(2)).isTrue()
        assertThat(InboundSmsParser.isValidSubscriptionId(-1)).isFalse()
    }

    @Test
    fun parse_wrongActionReturnsNull() {
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        assertThat(InboundSmsParser.parse(intent)).isNull()
    }

    @Test
    fun parse_emptyPdusReturnsNull() {
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        assertThat(InboundSmsParser.parse(intent)).isNull()
    }

    @Test
    fun parse_withoutPdus_failsClosed_evenWhenSubscriptionIsPresent() {
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        intent.putExtra("subscription", 3)
        val result = InboundSmsParser.parse(intent, receivedAtMillis = 42L)
        assertThat(result).isNull()
        assertThat(InboundSmsParser.resolveSubscriptionId(intent)).isEqualTo(3)
    }

    @Test
    fun parse_validSyntheticPdu_preservesBodyAndSubscription() {
        val pdu = syntheticDeliverPdu("synthetic body")
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            putExtra("subscription", 3)
            putExtra("format", "3gpp")
            putExtra("pdus", arrayOf(pdu))
        }

        val parsed = InboundSmsParser.parse(intent, receivedAtMillis = 42L)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.body).isEqualTo("synthetic body")
        assertThat(parsed.subscriptionId).isEqualTo(3)
        assertThat(parsed.rawPdus).containsExactly(pdu)
    }

    private fun syntheticDeliverPdu(body: String): ByteArray {
        val pduPrefix = byteArrayOf(
            0x00, // SMSC supplied by the modem.
            0x04, // SMS-DELIVER, no user-data header.
            0x0B.toByte(), 0x91.toByte(), // Sender length and international address type.
            0x51, 0x55, 0x21, 0x43, 0x65, 0xF7.toByte(), // +15551234567.
            0x00, // Protocol identifier.
            0x00, // GSM 7-bit alphabet.
            0x62, 0x80.toByte(), 0x60, 0x02, 0x00, 0x00, 0x00, // Synthetic service time.
            body.length.toByte(),
        )
        return pduPrefix + packGsm7(body)
    }

    private fun packGsm7(value: String): ByteArray {
        val packed = ByteArray((value.length * 7 + 7) / 8)
        value.forEachIndexed { index, character ->
            val septet = character.code and 0x7F
            val bitOffset = index * 7
            val byteIndex = bitOffset / 8
            val shift = bitOffset % 8
            packed[byteIndex] = (packed[byteIndex].toInt() or (septet shl shift)).toByte()
            if (shift > 1) {
                packed[byteIndex + 1] =
                    (packed[byteIndex + 1].toInt() or (septet shr (8 - shift))).toByte()
            }
        }
        return packed
    }
}

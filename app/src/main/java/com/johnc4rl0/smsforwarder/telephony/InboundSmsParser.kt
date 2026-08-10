package com.johnc4rl0.smsforwarder.telephony

import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.johnc4rl0.smsforwarder.domain.model.InboundSms

/**
 * Parses SMS_RECEIVED intents into [InboundSms] without logging PDU/body/sender.
 */
object InboundSmsParser {

    /**
     * Documented subscription-id extras only.
     * Do **not** treat slot/phone indices as subscription ids (silent remap risk).
     */
    private val SUBSCRIPTION_EXTRA_KEYS = listOf(
        "subscription",
        "android.telephony.extra.SUBSCRIPTION_INDEX",
        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
    )

    /**
     * @return null if action is wrong or no messages could be reconstructed
     */
    fun parse(intent: Intent, receivedAtMillis: Long = System.currentTimeMillis()): InboundSms? {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return null

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return null

        // Reconstruct Unicode body in PDU order.
        val body = buildString {
            for (msg in messages) {
                if (msg != null) append(msg.messageBody.orEmpty())
            }
        }

        val first = messages.firstOrNull { it != null }
        val sender = first?.originatingAddress
        val serviceTs = first?.timestampMillis?.takeIf { it > 0L }

        return InboundSms(
            sender = sender,
            body = body,
            subscriptionId = resolveSubscriptionId(intent),
            serviceTimestampMillis = serviceTs,
            receivedAtMillis = receivedAtMillis,
            rawPdus = extractRawPdus(intent),
        )
    }

    /**
     * Resolve incoming subscription id. Public contract does not guarantee the extra across OEMs;
     * absent or invalid values return null so the pipeline can fail closed.
     * Only real subscription extras are considered — never slot/phone index.
     */
    fun resolveSubscriptionId(intent: Intent): Int? {
        for (key in SUBSCRIPTION_EXTRA_KEYS) {
            if (!intent.hasExtra(key)) continue
            val value = intent.getIntExtra(key, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            if (isValidSubscriptionId(value)) return value
        }
        // Bundle may store Long on some OEMs.
        val extras = intent.extras ?: return null
        for (key in SUBSCRIPTION_EXTRA_KEYS) {
            if (!extras.containsKey(key)) continue
            val raw = extras.get(key) ?: continue
            val asInt = when (raw) {
                is Int -> raw
                is Long -> raw.toInt()
                is String -> raw.toIntOrNull()
                else -> null
            }
            if (asInt != null && isValidSubscriptionId(asInt)) return asInt
        }
        return null
    }

    fun isValidSubscriptionId(id: Int): Boolean =
        id != SubscriptionManager.INVALID_SUBSCRIPTION_ID && id >= 0

    private fun extractRawPdus(intent: Intent): List<ByteArray> {
        val extras = intent.extras ?: return emptyList()
        @Suppress("DEPRECATION")
        val raw = extras.get("pdus") ?: return emptyList()
        val array = raw as? Array<*> ?: return emptyList()
        return array.mapNotNull { it as? ByteArray }
    }
}

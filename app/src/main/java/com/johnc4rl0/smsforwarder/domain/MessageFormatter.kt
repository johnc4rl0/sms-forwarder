package com.johnc4rl0.smsforwarder.domain

import com.johnc4rl0.smsforwarder.domain.model.LineSelection

/**
 * Pure header construction, field sanitization, and segment-count estimation
 * for forwarded SMS payloads.
 */
object MessageFormatter {
    const val LOOP_MARKER_PREFIX: String = "[SMS-FWD/"
    const val HEADER_VERSION_PREFIX: String = "[SMS-FWD/1]"
    const val MAX_HEADER_FIELD_LENGTH: Int = 64
    const val UNKNOWN_SENDER: String = "Unknown"

    /**
     * Collapse control characters and newlines, trim, and limit to [MAX_HEADER_FIELD_LENGTH].
     * Returns empty string when [value] is null/blank after sanitization (caller maps sender → Unknown).
     */
    fun sanitizeHeaderField(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val collapsed = buildString(value.length) {
            for (c in value) {
                when {
                    c == '\n' || c == '\r' || c == '\t' -> append(' ')
                    c.isISOControl() -> append(' ')
                    else -> append(c)
                }
            }
        }
        val singleSpaced = collapsed
            .replace(Regex(" {2,}"), " ")
            .trim()
        return if (singleSpaced.length <= MAX_HEADER_FIELD_LENGTH) {
            singleSpaced
        } else {
            singleSpaced.substring(0, MAX_HEADER_FIELD_LENGTH)
        }
    }

    /** Sender for the header line; [UNKNOWN_SENDER] when missing/blank after sanitize. */
    fun displaySender(sender: String?): String {
        val sanitized = sanitizeHeaderField(sender)
        return sanitized.ifEmpty { UNKNOWN_SENDER }
    }

    /**
     * Human-readable source line label for the header: prefer effective E.164,
     * then carrier display name, then `sub:<id>`.
     */
    fun displaySourceLine(source: LineSelection): String {
        val preferred = source.effectiveNumberE164
            ?: source.carrierDisplayName
            ?: "sub:${source.subscriptionId}"
        val sanitized = sanitizeHeaderField(preferred)
        return sanitized.ifEmpty { "sub:${source.subscriptionId}" }
    }

    /**
     * Exact forward payload:
     * ```
     * [SMS-FWD/1] From <sender> via <source line>
     * <original body>
     * ```
     * Original body is preserved unchanged (including Unicode).
     */
    fun buildForwardPayload(sender: String?, source: LineSelection, originalBody: String): String {
        val from = displaySender(sender)
        val via = displaySourceLine(source)
        return "$HEADER_VERSION_PREFIX From $from via $via\n$originalBody"
    }

    /**
     * True when the body (after leading whitespace) starts with the loop marker `[SMS-FWD/`.
     */
    fun hasLoopMarker(body: String): Boolean {
        val trimmedStart = body.trimStart()
        return trimmedStart.startsWith(LOOP_MARKER_PREFIX)
    }

    // --- Segment estimation (mirrors SmsManager.divideMessage character budgets) ---

    private const val GSM7_SINGLE = 160
    private const val GSM7_CONCAT = 153
    private const val UCS2_SINGLE = 70
    private const val UCS2_CONCAT = 67

    /**
     * Estimate how many SMS segments [payload] would produce.
     * Uses GSM-7 budgets when every character is in the basic GSM 7-bit alphabet;
     * otherwise UCS-2. Does not account for national language shift tables or
     * extended-escape double-septet costs beyond a conservative extended set.
     */
    fun estimateSegmentCount(payload: String): Int {
        if (payload.isEmpty()) return 1
        val gsm = isGsm7Compatible(payload)
        val single = if (gsm) GSM7_SINGLE else UCS2_SINGLE
        val concat = if (gsm) GSM7_CONCAT else UCS2_CONCAT
        // Count septets with escape for extended GSM chars when gsm
        val units = if (gsm) gsmSeptetCount(payload) else payload.length
        if (units <= single) return 1
        return (units + concat - 1) / concat
    }

    /** Strict GSM 7-bit safe basic set + extended set. Non-ASCII characters default to UCS-2. */
    private val GSM7_SAFE_BASIC: Set<Char> = (
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" +
            " !\"#\$%&'()*+,-./:;<=>?@_\r\n"
    ).toSet()

    private val GSM7_EXTENDED: Set<Char> = setOf(
        '|', '^', '€', '{', '}', '[', ']', '~', '\\',
    )

    fun isGsm7Compatible(text: String): Boolean =
        text.all { it in GSM7_SAFE_BASIC || it in GSM7_EXTENDED }

    private fun gsmSeptetCount(text: String): Int {
        var count = 0
        for (c in text) {
            count += if (c in GSM7_EXTENDED) 2 else 1
        }
        return count
    }
}

package com.johnc4rl0.smsforwarder.domain

/**
 * Pure E.164 validation and normalization helpers.
 *
 * Accepts destinations matching `+[country code][number]` with 8–15 digits total
 * (the leading `+` is not counted). Rejects local / non-E.164 forms.
 */
object E164 {
    private val E164_PATTERN = Regex("^\\+[1-9]\\d{7,14}$")

    /** True when [value] is a well-formed E.164 number (8–15 digits after `+`). */
    fun isValid(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return E164_PATTERN.matches(value.trim())
    }

    /**
     * Normalize for equality checks: trim, keep leading `+`, strip spaces/dashes/parens.
     * Does not invent a country code for local numbers — those remain invalid.
     */
    fun normalize(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        val builder = StringBuilder()
        var i = 0
        if (trimmed.startsWith("+")) {
            builder.append('+')
            i = 1
        }
        while (i < trimmed.length) {
            val c = trimmed[i]
            when {
                c.isDigit() -> builder.append(c)
                c == ' ' || c == '-' || c == '(' || c == ')' || c == '.' -> Unit
                else -> return null // unexpected character → not normalizable as phone
            }
            i++
        }
        val result = builder.toString()
        return result.takeIf { isValid(it) }
    }

    /**
     * Digits-only form (no `+`) for loose comparison when one side may lack formatting.
     * Returns null when no digits present.
     */
    fun digitsOnly(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val digits = value.filter { it.isDigit() }
        return digits.takeIf { it.isNotEmpty() }
    }

    /**
     * Loose digit comparison: returns true if digits are identical, OR if both digit strings
     * contain at least 7 subscriber digits and one string is a trailing national suffix of the other
     * (e.g. 10-digit national "5551234567" vs 11-digit E.164 "15551234567").
     */
    fun digitsMatchOrSuffix(raw1: String?, raw2: String?): Boolean {
        val d1 = digitsOnly(raw1) ?: return false
        val d2 = digitsOnly(raw2) ?: return false
        if (d1 == d2) return true
        if (d1.length >= 7 && d2.length >= 7) {
            if (d1.endsWith(d2) || d2.endsWith(d1)) return true
        }
        return false
    }

    /**
     * True when [candidate] is equal to any known local line number
     * (source, outbound, or other installed lines), comparing normalized E.164
     * or digit-only / national suffix forms.
     */
    fun isLocalNumber(candidate: String?, knownLocalNumbers: Collection<String?>): Boolean {
        if (candidate.isNullOrBlank()) return false
        for (known in knownLocalNumbers) {
            if (known.isNullOrBlank()) continue
            if (digitsMatchOrSuffix(candidate, known)) return true
        }
        return false
    }

    /**
     * True when [sender] should be treated as the configured destination
     * (loop / echo suppression).
     */
    fun senderMatchesDestination(sender: String?, destinationE164: String?): Boolean {
        if (destinationE164.isNullOrBlank() || sender.isNullOrBlank()) return false
        return digitsMatchOrSuffix(sender, destinationE164)
    }
}

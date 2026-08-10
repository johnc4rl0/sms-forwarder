package com.johnc4rl0.smsforwarder.ui.util

/**
 * UI-side phone formatting helpers. Domain policy owns authoritative validation;
 * these keep the Compose layer consistent for display and lightweight input checks.
 */

/** E.164: + followed by country code and number, 8–15 digits total after +. */
private val E164_REGEX = Regex("^\\+[1-9]\\d{7,14}$")

fun isValidE164(value: String): Boolean = E164_REGEX.matches(value.trim())

/**
 * Mask a number for dashboard / notification display.
 * Example: +15551234567 → +…4567
 */
fun maskE164(number: String?): String {
    if (number.isNullOrBlank()) return "—"
    val trimmed = number.trim()
    val digits = trimmed.filter { it.isDigit() }
    if (digits.length < 4) return "••••"
    return "+…${digits.takeLast(4)}"
}

fun formatActiveLineLabel(
    slotIndex: Int?,
    carrierDisplayName: String?,
    reportedNumberE164: String?,
    isEmbedded: Boolean,
): String {
    val slot = slotIndex?.let { "Slot $it" } ?: "Slot —"
    val kind = if (isEmbedded) "eSIM" else "SIM"
    val carrier = carrierDisplayName?.takeIf { it.isNotBlank() } ?: "Unknown carrier"
    val number = reportedNumberE164?.takeIf { it.isNotBlank() } ?: "number unknown"
    return "$slot · $kind · $carrier · $number"
}

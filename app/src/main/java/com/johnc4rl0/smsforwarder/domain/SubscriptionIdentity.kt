package com.johnc4rl0.smsforwarder.domain

import java.security.MessageDigest

/**
 * Outcome of comparing stored vs live subscription identity evidence.
 */
enum class IdentityComparisonResult {
    Same,
    Different,
    Unknown,
}

/**
 * Pure Kotlin policy and parsing for versioned subscription identity evidence.
 *
 * Requirements:
 * - Pure domain logic without Android framework dependencies.
 * - Strict separation of routing metadata (carrier, display name, phone number, slot) from security identity.
 * - Versioned tokens: "v1:icc:<sha256>" or "v1:fallback:sub:<subId>:card:<cardId>:port:<port>:emb:<isEmbedded>".
 * - Fail-closed: missing, sentinel, downgraded, or legacy unversioned tokens evaluate to [IdentityComparisonResult.Unknown].
 */
object SubscriptionIdentity {

    private const val PREFIX_V1 = "v1:"
    private const val PREFIX_ICC = "v1:icc:"
    private const val PREFIX_FALLBACK = "v1:fallback:"

    /**
     * Compare stored identity evidence against live identity evidence.
     *
     * @return [IdentityComparisonResult.Same], [IdentityComparisonResult.Different], or [IdentityComparisonResult.Unknown].
     */
    fun compare(storedToken: String?, liveToken: String?): IdentityComparisonResult {
        if (storedToken.isNullOrBlank() || liveToken.isNullOrBlank()) {
            return IdentityComparisonResult.Unknown
        }
        if (!storedToken.startsWith(PREFIX_V1) || !liveToken.startsWith(PREFIX_V1)) {
            // Unversioned legacy token (e.g. raw 64-hex SHA-256) -> fail closed as Unknown
            return IdentityComparisonResult.Unknown
        }

        val storedIsIcc = storedToken.startsWith(PREFIX_ICC)
        val liveIsIcc = liveToken.startsWith(PREFIX_ICC)

        if (storedIsIcc && liveIsIcc) {
            val storedHash = storedToken.removePrefix(PREFIX_ICC)
            val liveHash = liveToken.removePrefix(PREFIX_ICC)
            if (storedHash.isBlank() || liveHash.isBlank()) {
                return IdentityComparisonResult.Unknown
            }
            return if (storedHash == liveHash) {
                IdentityComparisonResult.Same
            } else {
                IdentityComparisonResult.Different
            }
        }

        val storedIsFallback = storedToken.startsWith(PREFIX_FALLBACK)
        val liveIsFallback = liveToken.startsWith(PREFIX_FALLBACK)

        if (storedIsFallback && liveIsFallback) {
            val storedParsed = parseFallback(storedToken)
            val liveParsed = parseFallback(liveToken)
            if (storedParsed == null || liveParsed == null) {
                return IdentityComparisonResult.Unknown
            }
            return if (storedParsed == liveParsed) {
                IdentityComparisonResult.Same
            } else {
                IdentityComparisonResult.Different
            }
        }

        // Downgraded / upgraded evidence classes (e.g. ICCID lost or gained) -> Unknown
        return IdentityComparisonResult.Unknown
    }

    /**
     * Create strong identity evidence based on SHA-256 hash of ICCID.
     */
    fun createIccEvidence(iccid: String): String? {
        val trimmed = iccid.trim()
        if (trimmed.isBlank()) return null
        val hash = sha256Hex(trimmed)
        return "$PREFIX_ICC$hash"
    }

    /**
     * Create fallback identity evidence based on stable, non-sentinel OS subscription/card/port/embedded state.
     * Does NOT include mutable carrier labels, display names, reported numbers, or slot indices.
     */
    fun createFallbackEvidence(
        subscriptionId: Int,
        cardId: Int,
        portIndex: Int = 0,
        isEmbedded: Boolean,
    ): String? {
        if (subscriptionId < 0) return null
        // cardId < 0 is sentinel (e.g. UNSPECIFIED_CARD_ID) unless it's embedded eSIM profile
        if (cardId < 0 && !isEmbedded) return null
        return "${PREFIX_FALLBACK}sub:$subscriptionId:card:$cardId:port:$portIndex:emb:$isEmbedded"
    }

    internal data class FallbackFields(
        val subscriptionId: Int,
        val cardId: Int,
        val portIndex: Int,
        val isEmbedded: Boolean,
    )

    internal fun parseFallback(token: String): FallbackFields? {
        if (!token.startsWith(PREFIX_FALLBACK)) return null
        val body = token.removePrefix(PREFIX_FALLBACK)
        val parts = body.split(":")
        if (parts.size != 8) return null
        // Expected format: sub:<id>:card:<id>:port:<id>:emb:<bool>
        if (parts[0] != "sub" || parts[2] != "card" || parts[4] != "port" || parts[6] != "emb") {
            return null
        }
        val subId = parts[1].toIntOrNull() ?: return null
        val cardId = parts[3].toIntOrNull() ?: return null
        val port = parts[5].toIntOrNull() ?: return null
        val emb = when (parts[7]) {
            "true" -> true
            "false" -> false
            else -> return null
        }
        if (subId < 0) return null
        if (cardId < 0 && !emb) return null
        return FallbackFields(
            subscriptionId = subId,
            cardId = cardId,
            portIndex = port,
            isEmbedded = emb,
        )
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { b -> "%02x".format(b) }
    }
}

package com.johnc4rl0.smsforwarder.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.johnc4rl0.smsforwarder.domain.SubscriptionCatalog
import com.johnc4rl0.smsforwarder.domain.model.ActiveLine
import com.johnc4rl0.smsforwarder.domain.model.LineSelection
import com.johnc4rl0.smsforwarder.domain.model.LineValidation
import com.johnc4rl0.smsforwarder.domain.model.PauseReason
import java.security.MessageDigest

/**
 * Live [SubscriptionCatalog] backed by [SubscriptionManager].
 *
 * Routing always uses subscriptionId — never remaps by slot or default SMS subscription.
 * Identity tokens are SHA-256 of ICCID when available (never store raw ICCID in logs).
 */
class AndroidSubscriptionCatalog(
    context: Context,
) : SubscriptionCatalog {

    private val appContext = context.applicationContext

    override fun listActiveLines(): List<ActiveLine> {
        if (!hasPhonePermission()) return emptyList()
        val sm = subscriptionManager() ?: return emptyList()
        val infos = try {
            sm.activeSubscriptionInfoList
        } catch (_: SecurityException) {
            null
        } ?: return emptyList()

        return infos.mapNotNull { info -> info.toActiveLine() }
    }

    override fun validate(selection: LineSelection): LineValidation {
        val active = listActiveLines().firstOrNull { it.subscriptionId == selection.subscriptionId }
            ?: return LineValidation.Invalid(PauseReason.SOURCE_SUBSCRIPTION_INACTIVE)

        val storedToken = selection.identityToken
        val liveToken = active.identityToken
        // Align with DefaultForwardingEngine.identityMismatch: stored token present and
        // live token missing or different → Invalid (fail closed).
        if (storedToken != null && (liveToken == null || storedToken != liveToken)) {
            return LineValidation.Invalid(PauseReason.SOURCE_IDENTITY_MISMATCH)
        }
        return LineValidation.Valid
    }

    private fun SubscriptionInfo.toActiveLine(): ActiveLine? {
        val subId = subscriptionId
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return null
        return ActiveLine(
            subscriptionId = subId,
            slotIndex = if (simSlotIndex >= 0) simSlotIndex else null,
            carrierDisplayName = carrierName?.toString()?.takeIf { it.isNotBlank() }
                ?: displayName?.toString()?.takeIf { it.isNotBlank() },
            reportedNumberE164 = number?.takeIf { it.isNotBlank() },
            isEmbedded = isEmbedded,
            identityToken = computeIdentityToken(this),
        )
    }

    private fun computeIdentityToken(info: SubscriptionInfo): String? {
        val icc = info.iccId?.takeIf { it.isNotBlank() }
        if (icc != null) {
            return sha256Hex(icc)
        }
        // Fallback when ICCID is unavailable: bind to slot, cardId, embedded flag, display name, and number
        // so SIM replacements on the same carrier do not produce colliding tokens.
        val carrier = info.carrierName?.toString().orEmpty()
        val display = info.displayName?.toString().orEmpty()
        val num = info.number?.takeIf { it.isNotBlank() }.orEmpty()
        val card = try {
            info.cardId.toString()
        } catch (_: Throwable) {
            ""
        }
        val raw = "fallback:sub:${info.subscriptionId}:slot:${info.simSlotIndex}:embedded:${info.isEmbedded}:carrier:$carrier:display:$display:num:$num:card:$card"
        return sha256Hex(raw)
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { b -> "%02x".format(b) }
    }

    private fun subscriptionManager(): SubscriptionManager? =
        appContext.getSystemService(SubscriptionManager::class.java)

    @Suppress("unused")
    private fun telephonyManager(): TelephonyManager? =
        appContext.getSystemService(TelephonyManager::class.java)

    private fun hasPhonePermission(): Boolean {
        val state = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        val numbers = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_PHONE_NUMBERS,
        ) == PackageManager.PERMISSION_GRANTED
        return state && numbers
    }
}

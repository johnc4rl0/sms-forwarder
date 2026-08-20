package com.johnc4rl0.smsforwarder.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.johnc4rl0.smsforwarder.domain.IdentityComparisonResult
import com.johnc4rl0.smsforwarder.domain.SubscriptionCatalog
import com.johnc4rl0.smsforwarder.domain.SubscriptionIdentity
import com.johnc4rl0.smsforwarder.domain.model.ActiveLine
import com.johnc4rl0.smsforwarder.domain.model.LineSelection
import com.johnc4rl0.smsforwarder.domain.model.LineValidation
import com.johnc4rl0.smsforwarder.domain.model.PauseReason

/**
 * Live [SubscriptionCatalog] backed by [SubscriptionManager].
 *
 * Routing always uses subscriptionId — never remaps by slot or default SMS subscription.
 * Identity tokens are versioned evidence via [SubscriptionIdentity] (never store raw ICCID).
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
        return when (SubscriptionIdentity.compare(storedToken, liveToken)) {
            IdentityComparisonResult.Same -> LineValidation.Valid
            IdentityComparisonResult.Different -> LineValidation.Invalid(PauseReason.SOURCE_IDENTITY_MISMATCH)
            IdentityComparisonResult.Unknown -> LineValidation.Invalid(PauseReason.SOURCE_IDENTITY_UNAVAILABLE)
        }
    }

    private fun SubscriptionInfo.toActiveLine(): ActiveLine? {
        val subId = subscriptionId
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID || subId < 0) return null
        val port = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            try {
                portIndex
            } catch (_: Throwable) {
                0
            }
        } else {
            0
        }
        val card = try {
            cardId
        } catch (_: Throwable) {
            -1
        }
        return ActiveLine(
            subscriptionId = subId,
            slotIndex = if (simSlotIndex >= 0) simSlotIndex else null,
            carrierDisplayName = carrierName?.toString()?.takeIf { it.isNotBlank() }
                ?: displayName?.toString()?.takeIf { it.isNotBlank() },
            reportedNumberE164 = number?.takeIf { it.isNotBlank() },
            isEmbedded = isEmbedded,
            identityToken = computeIdentityToken(this, port, card),
        )
    }

    private fun computeIdentityToken(info: SubscriptionInfo, portIndex: Int, cardId: Int): String? {
        val icc = info.iccId?.takeIf { it.isNotBlank() }
        if (icc != null) {
            return SubscriptionIdentity.createIccEvidence(icc)
        }
        return SubscriptionIdentity.createFallbackEvidence(
            subscriptionId = info.subscriptionId,
            cardId = cardId,
            portIndex = portIndex,
            isEmbedded = info.isEmbedded,
        )
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

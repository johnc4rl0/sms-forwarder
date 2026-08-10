package com.johnc4rl0.smsforwarder.telephony

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import com.johnc4rl0.smsforwarder.domain.model.LineSelection
import com.johnc4rl0.smsforwarder.domain.model.LineValidation
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live SubscriptionManager listing on a telephony-capable device.
 */
@RunWith(AndroidJUnit4::class)
class SubscriptionCatalogInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val catalog = AndroidSubscriptionCatalog(context)

    @Test
    fun listActiveLines_returnsDistinctSubscriptionIds() {
        assumeTrue(
            "READ_PHONE_STATE required",
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED,
        )

        val lines = catalog.listActiveLines()
        assertThat(lines).isNotEmpty()
        assertThat(lines.map { it.subscriptionId }.toSet()).hasSize(lines.size)

        // Dual-SIM devices: soft-assert distinct subscription ids when both present.
        if (lines.size >= 2) {
            assertThat(lines.map { it.subscriptionId }.distinct().size).isAtLeast(2)
            val slots = lines.mapNotNull { it.slotIndex }.toSet()
            assertThat(slots.size).isAtLeast(1)
        }

        lines.forEach { line ->
            assertThat(line.subscriptionId).isAtLeast(0)
            // Identity token is a hash — never raw ICCID length for a typical ICCID.
            line.identityToken?.let { token ->
                assertThat(token.length).isEqualTo(64) // sha256 hex
            }
        }
    }

    @Test
    fun validate_acceptsActiveSubscription_rejectsUnknown() {
        val lines = catalog.listActiveLines()
        assumeTrue("Need at least one active SIM", lines.isNotEmpty())

        val active = lines.first()
        val selection = LineSelection(
            subscriptionId = active.subscriptionId,
            slotIndex = active.slotIndex,
            carrierDisplayName = active.carrierDisplayName,
            reportedNumberE164 = active.reportedNumberE164,
            manualNumberE164 = null,
            identityToken = active.identityToken,
        )
        assertThat(catalog.validate(selection)).isEqualTo(LineValidation.Valid)

        val missing = selection.copy(subscriptionId = Int.MAX_VALUE - 7)
        assertThat(catalog.validate(missing)).isInstanceOf(LineValidation.Invalid::class.java)
    }
}

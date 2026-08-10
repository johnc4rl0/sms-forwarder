package com.johnc4rl0.smsforwarder.ui

import com.google.common.truth.Truth.assertThat
import com.johnc4rl0.smsforwarder.domain.model.ForwardingConfig
import com.johnc4rl0.smsforwarder.domain.model.LineSelection
import com.johnc4rl0.smsforwarder.domain.model.OperationalState
import com.johnc4rl0.smsforwarder.ui.util.isValidE164
import com.johnc4rl0.smsforwarder.ui.util.maskE164
import org.junit.Test

class PhoneFormattingTest {
    @Test
    fun e164_acceptsTypicalNumbers() {
        assertThat(isValidE164("+15551234567")).isTrue()
        assertThat(isValidE164("+447700900123")).isTrue()
    }

    @Test
    fun e164_rejectsInvalid() {
        assertThat(isValidE164("15551234567")).isFalse()
        assertThat(isValidE164("+0123")).isFalse()
        assertThat(isValidE164("+1")).isFalse()
        assertThat(isValidE164("")).isFalse()
    }

    @Test
    fun mask_showsLastFour() {
        assertThat(maskE164("+15551234567")).isEqualTo("+…4567")
        assertThat(maskE164(null)).isEqualTo("—")
    }
}

class SetupCompleteTest {
    private fun line(id: Int) = LineSelection(
        subscriptionId = id,
        slotIndex = 0,
        carrierDisplayName = "Test",
        reportedNumberE164 = "+1555000${id}000",
        manualNumberE164 = null,
        identityToken = "tok$id",
    )

    @Test
    fun incomplete_untilVerifiedAndActivatedState() {
        val base = ForwardingConfig(
            disclosureAccepted = true,
            source = line(1),
            outbound = line(2),
            destinationE164 = "+15559876543",
            destinationVerified = true,
            operationalState = OperationalState.NotConfigured,
        )
        assertThat(isSetupComplete(base)).isFalse()
        assertThat(
            isSetupComplete(base.copy(operationalState = OperationalState.Enabled)),
        ).isTrue()
        assertThat(
            isSetupComplete(base.copy(operationalState = OperationalState.ManuallyPaused)),
        ).isTrue()
    }

    @Test
    fun incomplete_withoutDestination() {
        val cfg = ForwardingConfig(
            disclosureAccepted = true,
            source = line(1),
            outbound = line(2),
            destinationVerified = false,
            operationalState = OperationalState.NotConfigured,
        )
        assertThat(isSetupComplete(cfg)).isFalse()
    }
}

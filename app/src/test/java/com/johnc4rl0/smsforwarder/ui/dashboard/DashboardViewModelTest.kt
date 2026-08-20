package com.johnc4rl0.smsforwarder.ui.dashboard

import com.google.common.truth.Truth.assertThat
import com.johnc4rl0.smsforwarder.domain.model.ActiveLine
import org.junit.Test

class DashboardViewModelTest {

    @Test
    fun findRepairLine_missingSavedSubscriptionDoesNotSelectAnotherLine() {
        val activeLines = listOf(
            ActiveLine(2, 1, "Carrier B", "+15552222222", false, "v1:icc:out"),
        )

        assertThat(findRepairLine(activeLines, subscriptionId = 1)).isNull()
    }

    @Test
    fun findRepairLine_returnsTheSavedSubscription() {
        val expected = ActiveLine(2, 1, "Carrier B", "+15552222222", false, "v1:icc:out")

        assertThat(findRepairLine(listOf(expected), subscriptionId = 2)).isEqualTo(expected)
    }
}

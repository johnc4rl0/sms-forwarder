package com.johnc4rl0.smsforwarder.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubscriptionIdentityTest {

    @Test
    fun strongIccEvidence_matchingIcc_isSame() {
        val token1 = SubscriptionIdentity.createIccEvidence("89014103211118510720")
        val token2 = SubscriptionIdentity.createIccEvidence("89014103211118510720")
        assertThat(token1).isNotNull()
        assertThat(token1).startsWith("v1:icc:")
        assertThat(SubscriptionIdentity.compare(token1, token2))
            .isEqualTo(IdentityComparisonResult.Same)
    }

    @Test
    fun strongIccEvidence_differentIcc_isDifferent() {
        val token1 = SubscriptionIdentity.createIccEvidence("89014103211118510720")
        val token2 = SubscriptionIdentity.createIccEvidence("89014103211118510721")
        assertThat(SubscriptionIdentity.compare(token1, token2))
            .isEqualTo(IdentityComparisonResult.Different)
    }

    @Test
    fun fallbackEvidence_sameStableFields_isSame() {
        val token1 = SubscriptionIdentity.createFallbackEvidence(
            subscriptionId = 1,
            cardId = 10,
            portIndex = 0,
            isEmbedded = false,
        )
        val token2 = SubscriptionIdentity.createFallbackEvidence(
            subscriptionId = 1,
            cardId = 10,
            portIndex = 0,
            isEmbedded = false,
        )
        assertThat(token1).isNotNull()
        assertThat(token1).startsWith("v1:fallback:")
        assertThat(SubscriptionIdentity.compare(token1, token2))
            .isEqualTo(IdentityComparisonResult.Same)
    }

    @Test
    fun fallbackEvidence_differentCardId_isDifferent() {
        val token1 = SubscriptionIdentity.createFallbackEvidence(
            subscriptionId = 1,
            cardId = 10,
            portIndex = 0,
            isEmbedded = false,
        )
        val token2 = SubscriptionIdentity.createFallbackEvidence(
            subscriptionId = 1,
            cardId = 20,
            portIndex = 0,
            isEmbedded = false,
        )
        assertThat(SubscriptionIdentity.compare(token1, token2))
            .isEqualTo(IdentityComparisonResult.Different)
    }

    @Test
    fun fallbackEvidence_differentSubId_isDifferent() {
        val token1 = SubscriptionIdentity.createFallbackEvidence(
            subscriptionId = 1,
            cardId = 10,
            portIndex = 0,
            isEmbedded = false,
        )
        val token2 = SubscriptionIdentity.createFallbackEvidence(
            subscriptionId = 2,
            cardId = 10,
            portIndex = 0,
            isEmbedded = false,
        )
        assertThat(SubscriptionIdentity.compare(token1, token2))
            .isEqualTo(IdentityComparisonResult.Different)
    }

    @Test
    fun fallbackEvidence_embeddedEsim_allowedWithNegativeCardId() {
        val token1 = SubscriptionIdentity.createFallbackEvidence(
            subscriptionId = 1,
            cardId = -1,
            portIndex = 0,
            isEmbedded = true,
        )
        val token2 = SubscriptionIdentity.createFallbackEvidence(
            subscriptionId = 1,
            cardId = -1,
            portIndex = 0,
            isEmbedded = true,
        )
        assertThat(token1).isNotNull()
        assertThat(SubscriptionIdentity.compare(token1, token2))
            .isEqualTo(IdentityComparisonResult.Same)
    }

    @Test
    fun fallbackEvidence_physicalSim_negativeCardId_returnsNull() {
        val token = SubscriptionIdentity.createFallbackEvidence(
            subscriptionId = 1,
            cardId = -1,
            portIndex = 0,
            isEmbedded = false,
        )
        assertThat(token).isNull()
    }

    @Test
    fun fallbackEvidence_negativeSubscriptionId_returnsNull() {
        val token = SubscriptionIdentity.createFallbackEvidence(
            subscriptionId = -1,
            cardId = 10,
            portIndex = 0,
            isEmbedded = false,
        )
        assertThat(token).isNull()
    }

    @Test
    fun compare_strongVsFallback_downgradeOrUpgrade_isUnknown() {
        val icc = SubscriptionIdentity.createIccEvidence("89014103211118510720")
        val fallback = SubscriptionIdentity.createFallbackEvidence(
            subscriptionId = 1,
            cardId = 10,
            portIndex = 0,
            isEmbedded = false,
        )
        assertThat(SubscriptionIdentity.compare(icc, fallback))
            .isEqualTo(IdentityComparisonResult.Unknown)
        assertThat(SubscriptionIdentity.compare(fallback, icc))
            .isEqualTo(IdentityComparisonResult.Unknown)
    }

    @Test
    fun compare_missingOrNull_isUnknown() {
        val icc = SubscriptionIdentity.createIccEvidence("89014103211118510720")
        assertThat(SubscriptionIdentity.compare(icc, null))
            .isEqualTo(IdentityComparisonResult.Unknown)
        assertThat(SubscriptionIdentity.compare(null, icc))
            .isEqualTo(IdentityComparisonResult.Unknown)
        assertThat(SubscriptionIdentity.compare(null, null))
            .isEqualTo(IdentityComparisonResult.Unknown)
        assertThat(SubscriptionIdentity.compare("", ""))
            .isEqualTo(IdentityComparisonResult.Unknown)
    }

    @Test
    fun compare_unversionedLegacyTokens_isUnknown() {
        // Old 64-hex tokens without "v1:" prefix
        val legacyStored = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val liveToken = SubscriptionIdentity.createIccEvidence("89014103211118510720")
        assertThat(SubscriptionIdentity.compare(legacyStored, liveToken))
            .isEqualTo(IdentityComparisonResult.Unknown)
        assertThat(SubscriptionIdentity.compare(legacyStored, legacyStored))
            .isEqualTo(IdentityComparisonResult.Unknown)
    }

    @Test
    fun compare_malformedTokens_isUnknown() {
        assertThat(SubscriptionIdentity.compare("v1:garbage", "v1:garbage"))
            .isEqualTo(IdentityComparisonResult.Unknown)
        assertThat(SubscriptionIdentity.compare("v1:fallback:invalid", "v1:fallback:invalid"))
            .isEqualTo(IdentityComparisonResult.Unknown)
    }
}

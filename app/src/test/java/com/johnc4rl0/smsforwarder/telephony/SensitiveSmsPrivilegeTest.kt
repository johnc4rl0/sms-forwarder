package com.johnc4rl0.smsforwarder.telephony

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure policy for when private sensitive-SMS privilege is required by API level.
 * Device appops grant checks are covered by install + health on hardware.
 */
class SensitiveSmsPrivilegeTest {

    @Test
    fun isRequired_false_beforeApi35() {
        assertThat(SensitiveSmsPrivilege.isRequired(31)).isFalse()
        assertThat(SensitiveSmsPrivilege.isRequired(34)).isFalse()
    }

    @Test
    fun isRequired_true_fromApi35() {
        assertThat(SensitiveSmsPrivilege.isRequired(35)).isTrue()
        assertThat(SensitiveSmsPrivilege.isRequired(36)).isTrue()
        assertThat(SensitiveSmsPrivilege.isRequired(37)).isTrue()
        assertThat(SensitiveSmsPrivilege.isRequired(SensitiveSmsPrivilege.REQUIRED_FROM_SDK_INT))
            .isTrue()
    }
}

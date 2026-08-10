package com.johnc4rl0.smsforwarder

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke checks that the connected hardware matches product assumptions.
 */
@RunWith(AndroidJUnit4::class)
class DeviceEnvironmentInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun minSdkAssumptionsHold() {
        assertThat(Build.VERSION.SDK_INT).isAtLeast(31)
    }

    @Test
    fun telephonyFeaturePresent() {
        assertThat(context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
            .isTrue()
    }
}

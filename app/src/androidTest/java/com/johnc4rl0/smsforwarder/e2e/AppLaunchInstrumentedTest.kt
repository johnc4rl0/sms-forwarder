package com.johnc4rl0.smsforwarder.e2e

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import com.johnc4rl0.smsforwarder.MainActivity
import com.johnc4rl0.smsforwarder.R
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * High-level launch journey on a physical device or emulator (UI Automator).
 * Does not call `pm clear` (that kills the instrumentation process).
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchInstrumentedTest {

    private lateinit var device: UiDevice
    private val pkg = "com.johnc4rl0.smsforwarder"

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
        device.pressHome()
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        assertThat(device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 15_000)).isTrue()
    }

    @Test
    fun launchesAndShowsSmsForwarderPackage() {
        assertThat(device.currentPackageName).isEqualTo(pkg)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Onboarding chrome or one of the stable main-shell titles. The completed
        // flow renders "Status" in MainShellScreen; it does not use the legacy
        // DashboardScreen title.
        val visibleChrome = listOf(
            R.string.onboarding_title,
            R.string.disclosure_title,
            R.string.status_title,
            R.string.outcomes_title,
            R.string.settings_title,
        ).any { resourceId ->
            device.wait(
                Until.hasObject(By.textContains(context.getString(resourceId))),
                3_000,
            )
        }
        assertThat(visibleChrome).isTrue()
    }
}

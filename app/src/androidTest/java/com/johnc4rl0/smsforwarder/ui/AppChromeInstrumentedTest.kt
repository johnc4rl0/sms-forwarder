package com.johnc4rl0.smsforwarder.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.johnc4rl0.smsforwarder.MainActivity
import com.johnc4rl0.smsforwarder.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose smoke: MainActivity boots into onboarding or main-shell chrome.
 *
 * Does not pm-clear mid-rule (kills the process under test). DataStore may already have
 * progressed past disclosure after earlier installs; we assert the stable setup chrome
 * or one of the main-shell titles that is visible after configuration.
 */
@RunWith(AndroidJUnit4::class)
class AppChromeInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appChromeIsShownOnLaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val setup = context.getString(R.string.onboarding_title)
        val disclosureTitle = context.getString(R.string.disclosure_title)
        val mainShellTitles = listOf(
            context.getString(R.string.status_title),
            context.getString(R.string.outcomes_title),
            context.getString(R.string.settings_title),
        )

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText(setup, substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty() ||
                composeRule.onAllNodesWithText(disclosureTitle, substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty() ||
                mainShellTitles.any { title ->
                    composeRule.onAllNodesWithText(title, substring = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
        }

        // Setup progress is shown while onboarding. If this device has already
        // completed setup, assert the actual main-shell title instead.
        val setupPresent = composeRule.onAllNodesWithText(setup, substring = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        if (setupPresent) {
            composeRule.onNodeWithText(setup, substring = true).assertIsDisplayed()
        } else if (composeRule.onAllNodesWithText(disclosureTitle, substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        ) {
            composeRule.onNodeWithText(disclosureTitle, substring = true).assertIsDisplayed()
        } else {
            val visibleMainShellTitle = mainShellTitles.firstOrNull { title ->
                composeRule.onAllNodesWithText(title, substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            assertThat(visibleMainShellTitle).isNotNull()
            composeRule.onAllNodesWithText(
                checkNotNull(visibleMainShellTitle),
                substring = true,
            )[0].assertIsDisplayed()
        }
    }
}

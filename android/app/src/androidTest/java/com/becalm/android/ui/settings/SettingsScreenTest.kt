package com.becalm.android.ui.settings

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.theme.BecalmTheme
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    // spec: PIPA-006

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun settings_shows_processing_banner_signout_note_and_dispatches_data_section_actions() {
        var sourcesClicks = 0
        var privacyClicks = 0
        var wipeClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                SettingsScreenContent(
                    state = SettingsUiState(
                        loading = false,
                        userEmail = "user@example.com",
                        processingPaused = true,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onToggleNotifications = {},
                    onTogglePipa = {},
                    onSourcesClick = { sourcesClicks++ },
                    onPrivacyClick = { privacyClicks++ },
                    onRequestSignOut = {},
                    onRequestWipe = { wipeClicks++ },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.processing_paused_banner)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.settings_sign_out_pipa_note)).performScrollTo()
        composeTestRule.onNodeWithTag("settings-sources-row").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("settings-privacy-row").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("settings-wipe-button").performScrollTo().performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, sourcesClicks)
            assertEquals(1, privacyClicks)
            assertEquals(1, wipeClicks)
        }
    }
    
    @Test
    fun settings_pipa_toggle_dispatches_requested_value() {
        var lastToggle: Boolean? = null

        composeTestRule.setContent {
            BecalmTheme {
                SettingsScreenContent(
                    state = SettingsUiState(
                        loading = false,
                        pipaConsentEnabled = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onToggleNotifications = {},
                    onTogglePipa = { lastToggle = it },
                    onSourcesClick = {},
                    onPrivacyClick = {},
                    onRequestSignOut = {},
                    onRequestWipe = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.settings_pipa_toggle_label)).performScrollTo()
        composeTestRule.onNodeWithTag("settings-pipa-toggle").performClick()

        composeTestRule.runOnIdle {
            assertEquals(true, lastToggle)
        }
    }

    @Test
    fun settings_screen_navigates_after_sign_out_and_consumes_error() {
        var navigateCount = 0
        var dismissCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                SettingsScreen(
                    navController = rememberNavController(),
                    stateOverride = SettingsUiState(
                        loading = false,
                        signedOut = true,
                        error = UiMessage.resource(R.string.settings_error_load_failed),
                    ),
                    onNavigateAfterSignOut = { navigateCount += 1 },
                    onErrorDismissed = { dismissCount += 1 },
                    onToggleNotifications = {},
                    onTogglePipaConsent = {},
                    onOpenSources = {},
                    onOpenPrivacy = {},
                    onSignOut = {},
                    onWipeLocalData = {},
                )
            }
        }

        val errorText = string(R.string.settings_error_load_failed)
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            runCatching {
                composeTestRule.onNodeWithText(errorText).assertIsDisplayed()
            }.isSuccess
        }
        composeTestRule.onNodeWithText(errorText).assertIsDisplayed()
        composeTestRule.waitUntil(timeoutMillis = 3_000) { navigateCount == 1 && dismissCount == 1 }
    }

    @Test
    fun settings_screen_confirms_sign_out_and_wipe_actions() {
        var signOutCount = 0
        var wipeCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                SettingsScreen(
                    navController = rememberNavController(),
                    stateOverride = SettingsUiState(
                        loading = false,
                        userEmail = "user@example.com",
                    ),
                    onErrorDismissed = {},
                    onToggleNotifications = {},
                    onTogglePipaConsent = {},
                    onOpenSources = {},
                    onOpenPrivacy = {},
                    onSignOut = { signOutCount += 1 },
                    onWipeLocalData = { wipeCount += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.action_sign_out)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(string(R.string.settings_sign_out_confirm_title)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.action_sign_out))[1].performClick()

        composeTestRule.onNodeWithTag("settings-wipe-button").performScrollTo().performClick()
        composeTestRule.onNodeWithText(string(R.string.settings_wipe_confirm_title)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.action_wipe_data))[1].performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, signOutCount)
            assertEquals(1, wipeCount)
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}

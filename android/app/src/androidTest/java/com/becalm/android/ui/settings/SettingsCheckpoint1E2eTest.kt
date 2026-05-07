package com.becalm.android.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.ui.theme.BecalmTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsCheckpoint1E2eTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun e2e_007_sign_out_keeps_local_data_until_explicit_wipe() {
        var signOutCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                SettingsScreen(
                    navController = rememberNavController(),
                    stateOverride = settingsState(),
                    onErrorDismissed = {},
                    onToggleNotifications = {},
                    onTogglePipaConsent = {},
                    onToggleCallLogMatchingConsent = {},
                    onCallLogPermissionDenied = {},
                    onOpenSources = {},
                    onOpenProcessingStatus = {},
                    onOpenPrivacy = {},
                    onSignOut = { signOutCount += 1 },
                    onWipeLocalData = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.settings_sign_out_pipa_note)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_sign_out)).performClick()
        composeTestRule.onNodeWithText(string(R.string.settings_sign_out_confirm_message)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.action_sign_out))[1].performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, signOutCount)
        }
    }

    @Test
    fun e2e_008_wipe_local_data_uses_explicit_confirm_and_does_not_crash_settings_shell() {
        var wipeCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                SettingsScreen(
                    navController = rememberNavController(),
                    stateOverride = settingsState(),
                    onErrorDismissed = {},
                    onToggleNotifications = {},
                    onTogglePipaConsent = {},
                    onToggleCallLogMatchingConsent = {},
                    onCallLogPermissionDenied = {},
                    onOpenSources = {},
                    onOpenProcessingStatus = {},
                    onOpenPrivacy = {},
                    onSignOut = {},
                    onWipeLocalData = { wipeCount += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.action_wipe_data)).performClick()
        composeTestRule.onNodeWithText(string(R.string.settings_wipe_confirm_message)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.action_wipe_data))[1].performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, wipeCount)
        }
        composeTestRule.onNodeWithText(string(R.string.settings_title)).assertIsDisplayed()
    }

    private fun settingsState(): SettingsUiState =
        SettingsUiState(
            userEmail = "user@example.com",
            loading = false,
            notificationsEnabled = true,
            pipaConsentEnabled = false,
            callLogMatchingConsentEnabled = false,
        )

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}

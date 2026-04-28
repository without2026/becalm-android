package com.becalm.android.integration.local.ui.settings

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.ui.settings.PrivacyManagementScreenContent
import com.becalm.android.ui.settings.PrivacyManagementUiState
import com.becalm.android.ui.settings.SettingsScreenContent
import com.becalm.android.ui.settings.SettingsUiState
import com.becalm.android.ui.theme.BecalmTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SettingsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `settings content shows paused banner signout note and dispatches actions`() {
        var sourcesClicks = 0
        var processingClicks = 0
        var privacyClicks = 0
        var wipeClicks = 0

        composeRule.setContent {
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
                    onSourcesClick = { sourcesClicks += 1 },
                    onProcessingStatusClick = { processingClicks += 1 },
                    onPrivacyClick = { privacyClicks += 1 },
                    onRequestSignOut = {},
                    onRequestWipe = { wipeClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.processing_paused_banner)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_sign_out_pipa_note)).assertIsDisplayed()
        composeRule.onNodeWithTag("settings-sources-row").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("settings-processing-status-row").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("settings-privacy-row").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("settings-wipe-button").performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals(1, sourcesClicks)
            assertEquals(1, processingClicks)
            assertEquals(1, privacyClicks)
            assertEquals(1, wipeClicks)
        }
    }

    @Test
    fun `settings pipa toggle invokes callback`() {
        var pipaToggle: Boolean? = null

        composeRule.setContent {
            BecalmTheme {
                SettingsScreenContent(
                    state = SettingsUiState(
                        loading = false,
                        pipaConsentEnabled = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onToggleNotifications = {},
                    onTogglePipa = { pipaToggle = it },
                    onSourcesClick = {},
                    onPrivacyClick = {},
                    onRequestSignOut = {},
                    onRequestWipe = {},
                )
            }
        }

        composeRule.onNodeWithTag("settings-pipa-toggle").performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals(true, pipaToggle)
        }
    }

    @Test
    fun `privacy management content shows all pipa action cards and count subtitle`() {
        var exportClicks = 0
        var withdrawClicks = 0
        var pauseClicks = 0
        var deleteClicks = 0
        var activityLogClicks = 0

        composeRule.setContent {
            BecalmTheme {
                PrivacyManagementScreenContent(
                    state = PrivacyManagementUiState(
                        loading = false,
                        commitmentCount = 4,
                        enrichmentCount = 2,
                        emailCount = 9,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onExportClick = { exportClicks += 1 },
                    onOpenConsentWithdraw = { withdrawClicks += 1 },
                    onOpenProcessingPause = { pauseClicks += 1 },
                    onOpenAccountDeletion = { deleteClicks += 1 },
                    onOpenActivityLog = { activityLogClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.privacy_export_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.privacy_withdraw_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.privacy_pause_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.privacy_delete_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.privacy_activity_log_title)).assertExists()
        composeRule.onNodeWithText(string(R.string.privacy_delete_subtitle_fmt, 4, 2, 9)).assertExists()

        composeRule.onNodeWithTag("privacy-export-card").performClick()
        composeRule.onNodeWithTag("privacy-withdraw-card").performClick()
        composeRule.onNodeWithTag("privacy-pause-card").performClick()
        composeRule.onNodeWithTag("privacy-delete-card").performClick()

        composeRule.runOnIdle {
            assertEquals(1, exportClicks)
            assertEquals(1, withdrawClicks)
            assertEquals(1, pauseClicks)
            assertEquals(1, deleteClicks)
            assertEquals(0, activityLogClicks)
        }
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}

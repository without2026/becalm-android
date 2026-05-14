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
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyManagementScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun privacy_management_shows_all_pipa_actions_and_count_subtitle() {
        composeTestRule.setContent {
            BecalmTheme {
                PrivacyManagementScreenContent(
                    state = PrivacyManagementUiState(
                        commitmentCount = 4,
                        enrichmentCount = 2,
                        emailCount = 9,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onExportClick = {},
                    onOpenConsentWithdraw = {},
                    onOpenProcessingPause = {},
                    onOpenAccountDeletion = {},
                    onOpenActivityLog = {},
                    onOpenSourceArchiveDelete = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("privacy-export-card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("privacy-withdraw-card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("privacy-pause-card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("privacy-delete-card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("privacy-activity-log-card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.privacy_delete_subtitle_fmt, 4, 2, 9))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun privacy_management_dispatches_each_action_card_click() {
        var exportClicks = 0
        var withdrawClicks = 0
        var pauseClicks = 0
        var deleteClicks = 0
        var activityLogClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                PrivacyManagementScreenContent(
                    state = PrivacyManagementUiState(),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onExportClick = { exportClicks++ },
                    onOpenConsentWithdraw = { withdrawClicks++ },
                    onOpenProcessingPause = { pauseClicks++ },
                    onOpenAccountDeletion = { deleteClicks++ },
                    onOpenActivityLog = { activityLogClicks++ },
                    onOpenSourceArchiveDelete = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("privacy-export-card").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("privacy-withdraw-card").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("privacy-pause-card").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("privacy-delete-card").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("privacy-activity-log-card").performScrollTo().performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, exportClicks)
            assertEquals(1, withdrawClicks)
            assertEquals(1, pauseClicks)
            assertEquals(1, deleteClicks)
            assertEquals(1, activityLogClicks)
        }
    }

    @Test
    fun privacy_management_screen_navigates_after_sign_out_and_consumes_error() {
        val effects = MutableSharedFlow<PrivacyManagementEffect>(extraBufferCapacity = 1)
        var navigateCount = 0
        var dismissCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                PrivacyManagementScreen(
                    navController = rememberNavController(),
                    stateOverride = PrivacyManagementUiState(
                        loading = false,
                        signedOut = true,
                        error = UiMessage.resource(R.string.privacy_export_failed),
                    ),
                    effectsOverride = effects,
                    onNavigateAfterSignOut = { navigateCount += 1 },
                    onErrorDismissed = { dismissCount += 1 },
                    onExportRequested = {},
                    onExportSaved = {},
                    onExportFailed = {},
                    onBack = {},
                    onOpenConsentWithdraw = {},
                    onOpenProcessingPause = {},
                    onOpenAccountDeletion = {},
                    onOpenActivityLog = {},
                    onDeleteSourceArchiveBefore = {},
                )
            }
        }

        val errorText = string(R.string.privacy_export_failed)
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            runCatching {
                composeTestRule.onNodeWithText(errorText).assertIsDisplayed()
            }.isSuccess
        }
        composeTestRule.onNodeWithText(errorText).assertIsDisplayed()
        composeTestRule.waitUntil(timeoutMillis = 3_000) { navigateCount == 1 && dismissCount == 1 }
    }

    @Test
    fun privacy_management_screen_confirms_export_request() {
        val effects = MutableSharedFlow<PrivacyManagementEffect>(extraBufferCapacity = 1)
        var exportRequestCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                PrivacyManagementScreen(
                    navController = rememberNavController(),
                    stateOverride = PrivacyManagementUiState(loading = false),
                    effectsOverride = effects,
                    onErrorDismissed = {},
                    onExportRequested = { exportRequestCount += 1 },
                    onExportSaved = {},
                    onExportFailed = {},
                    onBack = {},
                    onOpenConsentWithdraw = {},
                    onOpenProcessingPause = {},
                    onOpenAccountDeletion = {},
                    onOpenActivityLog = {},
                    onDeleteSourceArchiveBefore = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("privacy-export-card").performClick()
        composeTestRule.onNodeWithText(string(R.string.privacy_export_confirm_body)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_confirm)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, exportRequestCount)
        }
    }

    @Test
    fun privacy_management_screen_routes_export_effect_to_launcher_and_success_callback() {
        val effects = MutableSharedFlow<PrivacyManagementEffect>(extraBufferCapacity = 1)
        var launchedFileName: String? = null
        var launchedBytes: ByteArray? = null
        var savedCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                PrivacyManagementScreen(
                    navController = rememberNavController(),
                    stateOverride = PrivacyManagementUiState(loading = false),
                    effectsOverride = effects,
                    exportDocumentLauncher = ExportDocumentLauncher { fileName, bytes, onSaved, _ ->
                        launchedFileName = fileName
                        launchedBytes = bytes
                        onSaved()
                    },
                    onErrorDismissed = {},
                    onExportRequested = {},
                    onExportSaved = { savedCount += 1 },
                    onExportFailed = {},
                    onBack = {},
                    onOpenConsentWithdraw = {},
                    onOpenProcessingPause = {},
                    onOpenAccountDeletion = {},
                    onOpenActivityLog = {},
                    onDeleteSourceArchiveBefore = {},
                )
            }
        }

        composeTestRule.runOnIdle {
            effects.tryEmit(
                PrivacyManagementEffect.CreateExportDocument(
                    fileName = "becalm-export.zip",
                    bytes = "zip".toByteArray(),
                ),
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertEquals("becalm-export.zip", launchedFileName)
            assertEquals("zip", launchedBytes?.decodeToString())
            assertEquals(1, savedCount)
        }
    }

    @Test
    fun privacy_management_screen_routes_export_effect_failure_to_callback() {
        val effects = MutableSharedFlow<PrivacyManagementEffect>(extraBufferCapacity = 1)
        var failedMessage: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                PrivacyManagementScreen(
                    navController = rememberNavController(),
                    stateOverride = PrivacyManagementUiState(loading = false),
                    effectsOverride = effects,
                    exportDocumentLauncher = ExportDocumentLauncher { _, _, _, onFailed ->
                        onFailed("write failed")
                    },
                    onErrorDismissed = {},
                    onExportRequested = {},
                    onExportSaved = {},
                    onExportFailed = { failedMessage = it },
                    onBack = {},
                    onOpenConsentWithdraw = {},
                    onOpenProcessingPause = {},
                    onOpenAccountDeletion = {},
                    onOpenActivityLog = {},
                    onDeleteSourceArchiveBefore = {},
                )
            }
        }

        composeTestRule.runOnIdle {
            effects.tryEmit(
                PrivacyManagementEffect.CreateExportDocument(
                    fileName = "becalm-export.zip",
                    bytes = "zip".toByteArray(),
                ),
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertEquals("write failed", failedMessage)
        }
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}

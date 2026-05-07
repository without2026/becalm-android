package com.becalm.android.ui.settings

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.data.local.datastore.PipaActionLogEntry
import com.becalm.android.ui.theme.BecalmTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyCheckpoint6E2eTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun e2e_065_user_deletes_raw_originals_before_selected_date() {
        var cutoffDate: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                PrivacyManagementScreen(
                    navController = androidx.navigation.compose.rememberNavController(),
                    stateOverride = PrivacyManagementUiState(
                        loading = false,
                        sourceArchiveCount = 3,
                        sourceArchiveBytes = 12_288L,
                    ),
                    effectsOverride = kotlinx.coroutines.flow.emptyFlow(),
                    onNavigateAfterSignOut = {},
                    onErrorDismissed = {},
                    onExportRequested = {},
                    onExportSaved = {},
                    onExportFailed = {},
                    onBack = {},
                    onOpenConsentWithdraw = {},
                    onOpenProcessingPause = {},
                    onOpenAccountDeletion = {},
                    onOpenActivityLog = {},
                    onDeleteSourceArchiveBefore = { cutoffDate = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("privacy-source-archive-card").performClick()
        composeTestRule.onNodeWithTag("privacy-source-archive-cutoff").assertIsDisplayed()
        composeTestRule.onNodeWithTag("privacy-source-archive-cutoff").performTextInput("2026-04-30")
        composeTestRule.onNodeWithText(string(R.string.privacy_source_archive_delete_confirm)).performClick()

        composeTestRule.runOnIdle {
            assertEquals("2026-04-30", cutoffDate)
        }
    }

    @Test
    fun e2e_067_pipa_activity_log_uses_korean_local_only_explanations() {
        composeTestRule.setContent {
            BecalmTheme {
                ActivityLogContent(
                    state = PrivacyManagementUiState(
                        loading = false,
                        activityLog = listOf(
                            PipaActionLogEntry(
                                action = "source_archive_delete_before",
                                timestampIso = "2026-05-07T03:00:00Z",
                                details = mapOf(
                                    "cutoff_date" to "2026-04-30",
                                    "deleted_count" to "2",
                                    "failed_count" to "0",
                                ),
                            ),
                            PipaActionLogEntry(
                                action = "data_export",
                                timestampIso = "2026-05-07T04:00:00Z",
                                details = emptyMap(),
                            ),
                        ),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.privacy_activity_log_local_only_notice)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.privacy_activity_action_source_archive_delete_before)).assertIsDisplayed()
        composeTestRule.onNodeWithText("${string(R.string.privacy_activity_detail_cutoff_date)}: 2026-04-30", substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("${string(R.string.privacy_activity_detail_deleted_count)}: 2", substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.privacy_activity_action_data_export)).assertIsDisplayed()
    }

    @Test
    fun e2e_066_export_request_still_uses_explicit_confirmation() {
        var exportRequestCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                PrivacyManagementScreenContent(
                    state = PrivacyManagementUiState(loading = false),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onExportClick = { exportRequestCount += 1 },
                    onOpenConsentWithdraw = {},
                    onOpenProcessingPause = {},
                    onOpenAccountDeletion = {},
                    onOpenActivityLog = {},
                    onOpenSourceArchiveDelete = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("privacy-export-card").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, exportRequestCount)
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}

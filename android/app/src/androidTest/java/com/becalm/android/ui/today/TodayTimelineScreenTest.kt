package com.becalm.android.ui.today

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.ui.components.OverallSyncIndicator
import com.becalm.android.ui.components.SourceStatusChip
import com.becalm.android.ui.components.SourceStatusStrip
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.main.OverallSyncState
import com.becalm.android.ui.main.SourceStatusUi
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodayTimelineScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun today_content_shows_processing_banner_active_source_chips_source_warning_and_settings_action() {
        var openSettingsCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        processingPaused = true,
                        overall = OverallSyncState.Syncing(count = 1, total = 7),
                        overallSyncing = true,
                        sourceStatus = mapOf(
                            "voice" to SourceStatusUi(
                                status = SourceSyncStatus.Syncing,
                                errorMessage = null,
                                lastSyncedAt = null,
                            ),
                            "gmail" to SourceStatusUi(
                                status = SourceSyncStatus.Connected,
                                errorMessage = null,
                                lastSyncedAt = Instant.parse("2026-04-24T01:00:00Z"),
                            ),
                            "outlook_mail" to SourceStatusUi(
                                status = SourceSyncStatus.Error,
                                errorMessage = "token expired",
                                lastSyncedAt = null,
                            ),
                            "naver_imap" to SourceStatusUi(
                                status = SourceSyncStatus.Disconnected,
                                errorMessage = null,
                                lastSyncedAt = null,
                            ),
                        ),
                    ),
                    onOpenSettings = { openSettingsCount += 1 },
                    onPullRefresh = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.processing_paused_banner)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.today_syncing_fmt, 1, 7)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.today_source_attention_mixed_fmt, 1, 1)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Voice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Gmail").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Outlook Mail").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Naver Email").assertCountEquals(0)
        composeTestRule.onNodeWithContentDescription(string(R.string.label_settings)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, openSettingsCount)
        }
    }

    @Test
    fun today_content_shows_error_state() {
        composeTestRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        error = UiMessage.resource(R.string.today_error_load_failed),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.today_error_load_failed)).assertIsDisplayed()
    }

    @Test
    fun today_content_shows_empty_state() {
        composeTestRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        timeline = emptyList(),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.today_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.today_empty_message)).assertIsDisplayed()
    }

    @Test
    fun overall_sync_indicator_shows_synced_label() {
        composeTestRule.setContent {
            BecalmTheme {
                OverallSyncIndicator(
                    state = OverallSyncState.Synced(
                        Instant.parse("2026-04-24T01:30:00Z"),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.today_synced_time_fmt, ""), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun overall_sync_indicator_shows_partial_failure_label() {
        composeTestRule.setContent {
            BecalmTheme {
                OverallSyncIndicator(state = OverallSyncState.PartialFailure)
            }
        }

        composeTestRule.onNodeWithText(string(R.string.today_partial_failure)).assertIsDisplayed()
    }

    @Test
    fun source_status_strip_renders_provided_active_sources_only() {
        composeTestRule.setContent {
            BecalmTheme {
                SourceStatusStrip(
                    sources = listOf(
                        SourceStatusChip("voice", SourceSyncStatus.Syncing),
                        SourceStatusChip("gmail", SourceSyncStatus.Connected),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("Voice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Gmail").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Outlook Mail").assertCountEquals(0)
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}

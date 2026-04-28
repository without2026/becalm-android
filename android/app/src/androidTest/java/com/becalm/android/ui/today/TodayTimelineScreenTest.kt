package com.becalm.android.ui.today

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.ui.components.ChipState
import com.becalm.android.ui.components.OverallSyncIndicator
import com.becalm.android.ui.components.SourceStatusChip
import com.becalm.android.ui.components.SourceStatusStrip
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
    fun today_screen_shows_processing_banner_overall_sync_1_of_7_and_seven_source_labels() {
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
                                syncing = true,
                                statusLabel = "SYNCING",
                                errorMessage = null,
                                lastSyncedAt = null,
                            ),
                            "gmail" to SourceStatusUi(
                                syncing = false,
                                statusLabel = "CONNECTED",
                                errorMessage = null,
                                lastSyncedAt = Instant.parse("2026-04-24T01:00:00Z"),
                            ),
                        ),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.processing_paused_banner)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.today_syncing_fmt, 1, 7)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Voice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Gmail").assertIsDisplayed()
        composeTestRule.onNodeWithText("Outlook Mail").assertIsDisplayed()
        composeTestRule.onNodeWithText("Naver Email").assertIsDisplayed()
        composeTestRule.onNodeWithText("Daum Email").assertIsDisplayed()
        composeTestRule.onNodeWithText("Google Calendar").assertIsDisplayed()
        composeTestRule.onNodeWithText("Outlook Calendar").assertIsDisplayed()
    }

    @Test
    fun today_settings_icon_dispatches_callback() {
        var openSettingsCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(loading = false),
                    onOpenSettings = { openSettingsCount++ },
                    onPullRefresh = {},
                )
            }
        }

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
                        error = "network timeout",
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                )
            }
        }

        composeTestRule.onNodeWithText("network timeout").assertIsDisplayed()
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
    fun source_status_strip_renders_seven_product_sources() {
        composeTestRule.setContent {
            BecalmTheme {
                SourceStatusStrip(
                    sources = listOf(
                        SourceStatusChip("voice", ChipState.Syncing),
                        SourceStatusChip("gmail", ChipState.Idle),
                        SourceStatusChip("outlook_mail", ChipState.Error("expired")),
                        SourceStatusChip("naver_imap", ChipState.Idle),
                        SourceStatusChip("daum_imap", ChipState.Idle),
                        SourceStatusChip("google_calendar", ChipState.Idle),
                        SourceStatusChip("outlook_calendar", ChipState.Idle),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("Voice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Gmail").assertIsDisplayed()
        composeTestRule.onNodeWithTag("source-status-strip")
            .performScrollToNode(hasText("Outlook Calendar"))
        composeTestRule.onNodeWithText("Outlook Calendar").assertIsDisplayed()
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}

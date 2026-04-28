package com.becalm.android.integration.local.ui.today

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.ui.components.ChipState
import com.becalm.android.ui.components.OverallSyncIndicator
import com.becalm.android.ui.components.SourceStatusChip
import com.becalm.android.ui.components.SourceStatusStrip
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.today.OverallSyncState
import com.becalm.android.ui.today.SourceStatusUi
import com.becalm.android.ui.today.TodayTimelineContent
import com.becalm.android.ui.today.TodayUiState
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class TodayTimelineUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `today content shows processing banner settings action and seven source labels`() {
        var openSettingsCount = 0

        composeRule.setContent {
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
                    onOpenSettings = { openSettingsCount += 1 },
                    onPullRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.processing_paused_banner)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.today_syncing_fmt, 1, 7)).assertIsDisplayed()
        composeRule.onNodeWithText("Voice").assertIsDisplayed()
        composeRule.onNodeWithText("Gmail").assertExists()
        composeRule.onNodeWithTag("source-status-strip")
            .performScrollToNode(hasText("Outlook Calendar"))
        composeRule.onNodeWithText("Outlook Calendar").assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.label_settings)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, openSettingsCount)
        }
    }

    @Test
    fun `today content shows error state`() {
        composeRule.setContent {
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

        composeRule.onNodeWithText("network timeout").assertIsDisplayed()
    }

    @Test
    fun `today content shows empty state`() {
        composeRule.setContent {
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

        composeRule.onNodeWithText(string(R.string.today_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.today_empty_message)).assertIsDisplayed()
    }

    @Test
    fun `overall sync indicator shows synced label`() {
        composeRule.setContent {
            BecalmTheme {
                OverallSyncIndicator(
                    state = OverallSyncState.Synced(
                        Instant.parse("2026-04-24T01:30:00Z"),
                    ),
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.today_synced_time_fmt, ""), substring = true).assertExists()
    }

    @Test
    fun `overall sync indicator shows partial failure label`() {
        composeRule.setContent {
            BecalmTheme {
                OverallSyncIndicator(state = OverallSyncState.PartialFailure)
            }
        }

        composeRule.onNodeWithText(string(R.string.today_partial_failure)).assertExists()
    }

    @Test
    fun `source status strip renders seven product sources`() {
        composeRule.setContent {
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

        composeRule.onNodeWithText("Voice").assertExists()
        composeRule.onNodeWithText("Gmail").assertExists()
        composeRule.onNodeWithTag("source-status-strip")
            .performScrollToNode(hasText("Outlook Calendar"))
        composeRule.onNodeWithText("Outlook Calendar").assertExists()
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}

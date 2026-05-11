package com.becalm.android.ui.sources

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceResilienceCheckpoint6E2eTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun e2e_069_source_detail_renders_status_actions_and_recent_events() {
        composeTestRule.setContent {
            BecalmTheme {
                SourceDetailScreenContent(
                    state = sourceDetailState(
                        sourceType = "gmail",
                        status = SourceSyncStatus.Connected,
                        showReconnectButton = false,
                        showManualSyncButton = true,
                        showDisconnectButton = true,
                    ),
                    contentPadding = PaddingValues(),
                    onReconnect = {},
                    onManualSync = {},
                    onDisconnectClick = {},
                    onDisconnectDismiss = {},
                    onDisconnectConfirm = {},
                    onMeetingAudioAdd = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.sources_status_connected)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_sync_now)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.source_detail_recent_events_section)).assertIsDisplayed()
        composeTestRule.onNodeWithText("계약 검토").assertIsDisplayed()
        composeTestRule.onNodeWithTag("source-detail-list")
            .performScrollToNode(hasTestTag("source-detail-disconnect"))
        composeTestRule.onNodeWithText(string(R.string.action_disconnect)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.action_reconnect)).assertCountEquals(0)
    }

    @Test
    fun e2e_070_source_sync_error_is_visible_without_blocking_other_sources() {
        composeTestRule.setContent {
            BecalmTheme {
                SourcesListScreenContent(
                    state = SourcesListUiState(
                        items = listOf(
                            SourceStatusRow(
                                sourceType = "gmail",
                                status = SourceSyncStatus.Error,
                                lastSyncAt = null,
                                hasError = true,
                            ),
                            SourceStatusRow(
                                sourceType = "google_calendar",
                                status = SourceSyncStatus.Connected,
                                lastSyncAt = Instant.parse("2026-05-07T03:00:00Z"),
                                hasError = false,
                            ),
                        ),
                    ),
                    onBack = {},
                    onRowClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.raw_event_source_badge_gmail)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.sources_status_error)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.sources_last_error_generic)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.raw_event_source_badge_google_calendar)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.sources_status_connected)).assertIsDisplayed()
    }

    private fun sourceDetailState(
        sourceType: String,
        status: SourceSyncStatus,
        showReconnectButton: Boolean,
        showManualSyncButton: Boolean,
        showDisconnectButton: Boolean,
    ): SourceDetailUiState = SourceDetailUiState(
        sourceType = sourceType,
        status = status,
        lastSyncAt = Instant.parse("2026-05-07T03:00:00Z"),
        eventsSyncedCount = 12,
        hasError = status == SourceSyncStatus.Error,
        showReconnectButton = showReconnectButton,
        showDisconnectButton = showDisconnectButton,
        showManualSyncButton = showManualSyncButton,
        recentEvents = listOf(
            RecentEventSummary(
                id = "evt-1",
                timestamp = Instant.parse("2026-05-07T03:00:00Z"),
                title = "계약 검토",
            ),
        ),
    )

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}

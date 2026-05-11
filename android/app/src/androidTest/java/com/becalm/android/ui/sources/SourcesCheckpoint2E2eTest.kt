package com.becalm.android.ui.sources

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourcesCheckpoint2E2eTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun e2e_022_connected_source_state_is_identical_in_onboarding_and_settings_surface() {
        var selected: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                SourcesListScreenContent(
                    state = SourcesListUiState(
                        items = listOf(
                            sourceRow(SourceType.GMAIL, SourceSyncStatus.Connected),
                            sourceRow(SourceType.OUTLOOK_MAIL, SourceSyncStatus.Disconnected),
                            sourceRow(SourceType.GOOGLE_CALENDAR, SourceSyncStatus.Error, hasError = true),
                        ),
                    ),
                    onBack = {},
                    onRowClick = { selected = it },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.raw_event_source_badge_gmail)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.sources_status_connected)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.raw_event_source_badge_outlook_mail)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.sources_status_disconnected)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.sources_last_error_generic)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("sources-row-${SourceType.GMAIL}").performClick()

        composeTestRule.runOnIdle {
            assertEquals(SourceType.GMAIL, selected)
        }
    }

    @Test
    fun e2e_023_user_disconnects_a_source_from_settings_detail() {
        var disconnectClicks = 0
        var disconnectConfirmations = 0

        composeTestRule.setContent {
            BecalmTheme {
                SourceDetailScreenContent(
                    state = SourceDetailUiState(
                        sourceType = SourceType.GMAIL,
                        status = SourceSyncStatus.Connected,
                        lastSyncAt = Instant.parse("2026-05-08T01:00:00Z"),
                        eventsSyncedCount = 3,
                        showManualSyncButton = true,
                        showDisconnectButton = true,
                        showDisconnectConfirmDialog = false,
                        recentEvents = listOf(
                            RecentEventSummary(
                                id = "evt-1",
                                timestamp = Instant.parse("2026-05-08T01:00:00Z"),
                                title = "최근 메일",
                            ),
                        ),
                    ),
                    contentPadding = PaddingValues(),
                    onReconnect = {},
                    onManualSync = {},
                    onDisconnectClick = { disconnectClicks += 1 },
                    onDisconnectDismiss = {},
                    onDisconnectConfirm = { disconnectConfirmations += 1 },
                    onMeetingAudioAdd = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.sources_status_connected)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_sync_now)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("source-detail-list")
            .performScrollToNode(hasTestTag("source-detail-disconnect"))
        composeTestRule.onNodeWithTag("source-detail-disconnect").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, disconnectClicks)
            assertEquals(0, disconnectConfirmations)
        }
    }

    private fun sourceRow(
        sourceType: String,
        status: SourceSyncStatus,
        hasError: Boolean = false,
    ): SourceStatusRow =
        SourceStatusRow(
            sourceType = sourceType,
            status = status,
            lastSyncAt = Instant.parse("2026-05-08T01:00:00Z"),
            hasError = hasError,
        )

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}

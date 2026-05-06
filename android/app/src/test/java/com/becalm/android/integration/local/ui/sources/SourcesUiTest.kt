package com.becalm.android.integration.local.ui.sources

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.becalm.android.ui.sources.RecentEventSummary
import com.becalm.android.ui.sources.SourceDetailScreenContent
import com.becalm.android.ui.sources.SourceDetailUiState
import com.becalm.android.ui.sources.SourceStatusRow
import com.becalm.android.ui.sources.SourcesListScreenContent
import com.becalm.android.ui.sources.SourcesListUiState
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SourcesUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `sources list shows contacts pseudo source and row dispatch`() {
        var tappedSource: String? = null

        composeRule.setContent {
            BecalmTheme {
                SourcesListScreenContent(
                    state = SourcesListUiState(
                        items = listOf(
                            SourceStatusRow(
                                sourceType = "contacts",
                                status = "CONNECTED",
                                lastSyncAt = Instant.parse("2026-04-24T01:00:00Z"),
                                lastError = null,
                                enrichedCount = 7,
                            ),
                        ),
                    ),
                    onBack = {},
                    onRowClick = { tappedSource = it },
                )
            }
        }

        composeRule.onNodeWithText(string(com.becalm.android.R.string.sources_contacts_title)).assertIsDisplayed()
        composeRule.onNodeWithText("7", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("sources-row-contacts").performClick()

        composeRule.runOnIdle {
            assertEquals("contacts", tappedSource)
        }

    }

    @Test
    fun `sources list shows empty state`() {
        composeRule.setContent {
            BecalmTheme {
                SourcesListScreenContent(
                    state = SourcesListUiState(items = emptyList()),
                    onBack = {},
                    onRowClick = {},
                )
            }
        }

        composeRule.onNodeWithText(string(com.becalm.android.R.string.sources_empty_title)).assertIsDisplayed()
    }

    @Test
    fun `source detail shows actions recent events and disconnect confirm dialog`() {
        var reconnectClicks = 0
        var syncClicks = 0
        var disconnectClicks = 0
        var dismissClicks = 0
        var confirmClicks = 0

        composeRule.setContent {
            BecalmTheme {
                SourceDetailScreenContent(
                    state = SourceDetailUiState(
                        sourceType = "gmail",
                        status = "CONNECTED",
                        lastSyncAt = Instant.parse("2026-04-24T01:00:00Z"),
                        eventsSyncedCount = 3,
                        showReconnectButton = true,
                        showManualSyncButton = true,
                        showDisconnectButton = true,
                        recentEvents = listOf(
                            RecentEventSummary(
                                id = "event-1",
                                timestamp = Instant.parse("2026-04-24T00:30:00Z"),
                                title = "계약 메일",
                            ),
                        ),
                        showDisconnectConfirmDialog = true,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(),
                    onReconnect = { reconnectClicks += 1 },
                    onManualSync = { syncClicks += 1 },
                    onDisconnectClick = { disconnectClicks += 1 },
                    onDisconnectDismiss = { dismissClicks += 1 },
                    onDisconnectConfirm = { confirmClicks += 1 },
                    onMeetingAudioAdd = {},
                    onMeetingTranscriptAdd = {},
                )
            }
        }

        composeRule.onNodeWithText("계약 메일").assertExists()
        composeRule.onNodeWithTag("source-detail-reconnect").performClick()
        composeRule.onNodeWithTag("source-detail-sync-now").performClick()
        composeRule.onNodeWithTag("source-detail-list")
            .performScrollToNode(hasTestTag("source-detail-disconnect"))
        composeRule.onNodeWithTag("source-detail-disconnect").performClick()
        composeRule.onNodeWithText(string(com.becalm.android.R.string.source_detail_disconnect_confirm_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("source-detail-disconnect-cancel").performClick()

        composeRule.runOnIdle {
            assertEquals(1, reconnectClicks)
            assertEquals(1, syncClicks)
            assertEquals(1, disconnectClicks)
            assertEquals(1, dismissClicks)
            assertEquals(0, confirmClicks)
        }
    }

    @Test
    fun `meeting source detail shows format-scoped add buttons`() {
        var audioClicks = 0
        var transcriptClicks = 0

        composeRule.setContent {
            BecalmTheme {
                SourceDetailScreenContent(
                    state = SourceDetailUiState(
                        sourceType = "meeting",
                        status = "CONNECTED",
                        showMeetingAudioAddButton = true,
                        showMeetingTranscriptAddButton = true,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(),
                    onReconnect = {},
                    onManualSync = {},
                    onDisconnectClick = {},
                    onDisconnectDismiss = {},
                    onDisconnectConfirm = {},
                    onMeetingAudioAdd = { audioClicks += 1 },
                    onMeetingTranscriptAdd = { transcriptClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("source-detail-meeting-audio-add").assertIsDisplayed()
        composeRule.onNodeWithTag("source-detail-meeting-transcript-add").assertIsDisplayed()
        composeRule.onNodeWithText(string(com.becalm.android.R.string.source_detail_meeting_audio_formats)).assertIsDisplayed()
        composeRule.onNodeWithText(string(com.becalm.android.R.string.source_detail_meeting_transcript_formats)).assertIsDisplayed()
        composeRule.onNodeWithTag("source-detail-meeting-audio-add").performClick()
        composeRule.onNodeWithTag("source-detail-meeting-transcript-add").performClick()

        composeRule.runOnIdle {
            assertEquals(1, audioClicks)
            assertEquals(1, transcriptClicks)
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}

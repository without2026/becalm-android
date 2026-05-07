package com.becalm.android.ui.sources

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun source_detail_shows_reconnect_sync_disconnect_actions_and_recent_events() {
        setScreen(
            state = baseState(),
        )

        composeTestRule.onNodeWithText(string(R.string.source_detail_status_section)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_reconnect)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_sync_now)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("source-detail-list")
            .performScrollToNode(hasTestTag("source-detail-disconnect"))
        composeTestRule.onNodeWithText(string(R.string.action_disconnect)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.source_detail_recent_events_section))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("계약 검토").assertIsDisplayed()
    }

    @Test
    fun source_detail_connected_source_keeps_recovery_cta_quiet() {
        setScreen(
            state = baseState(
                status = SourceSyncStatus.Connected,
                showReconnectButton = false,
                showDisconnectButton = true,
                showManualSyncButton = true,
            ),
        )

        composeTestRule.onNodeWithText(string(R.string.sources_status_connected)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.action_reconnect)).assertCountEquals(0)
        composeTestRule.onNodeWithText(string(R.string.action_sync_now)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("source-detail-list")
            .performScrollToNode(hasTestTag("source-detail-disconnect"))
        composeTestRule.onNodeWithText(string(R.string.action_disconnect)).assertIsDisplayed()
    }

    @Test
    fun source_detail_error_source_prioritizes_reconnect_recovery() {
        setScreen(
            state = baseState(
                status = SourceSyncStatus.Error,
                hasError = true,
                showReconnectButton = true,
                showDisconnectButton = false,
                showManualSyncButton = false,
            ),
        )

        composeTestRule.onNodeWithText(string(R.string.sources_status_error)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.source_detail_last_error_generic)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("권한이 만료되었습니다.", substring = true).assertCountEquals(0)
        composeTestRule.onNodeWithText(string(R.string.action_reconnect)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.action_sync_now)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(string(R.string.action_disconnect)).assertCountEquals(0)
    }

    @Test
    fun source_detail_disconnect_dialog_dispatches_confirm_callback() {
        var confirmed = 0
        var dismissed = 0

        setScreen(
            state = baseState(showDisconnectConfirmDialog = true),
            onDisconnectDismiss = { dismissed++ },
            onDisconnectConfirm = { confirmed++ },
        )

        composeTestRule.onNodeWithText(string(R.string.source_detail_disconnect_confirm_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_confirm)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(0, dismissed)
        }
    }

    @Test
    fun source_detail_hides_disconnect_dialog_when_flag_is_false() {
        setScreen(
            state = baseState(showDisconnectConfirmDialog = false),
        )

        composeTestRule.onAllNodesWithText(string(R.string.source_detail_disconnect_confirm_title))
            .assertCountEquals(0)
    }

    private fun setScreen(
        state: SourceDetailUiState,
        onDisconnectDismiss: () -> Unit = {},
        onDisconnectConfirm: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            BecalmTheme {
                SourceDetailScreenContent(
                    state = state,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(),
                    onReconnect = {},
                    onManualSync = {},
                    onDisconnectClick = {},
                    onDisconnectDismiss = onDisconnectDismiss,
                    onDisconnectConfirm = onDisconnectConfirm,
                    onMeetingAudioAdd = {},
                    onMeetingTranscriptAdd = {},
                )
            }
        }
    }

    private fun baseState(
        status: SourceSyncStatus = SourceSyncStatus.Connected,
        hasError: Boolean = false,
        showReconnectButton: Boolean = true,
        showDisconnectButton: Boolean = true,
        showManualSyncButton: Boolean = true,
        showDisconnectConfirmDialog: Boolean = false,
    ): SourceDetailUiState = SourceDetailUiState(
        sourceType = "gmail",
        status = status,
        lastSyncAt = Instant.parse("2026-04-24T01:00:00Z"),
        eventsSyncedCount = 12,
        hasError = hasError,
        showReconnectButton = showReconnectButton,
        showDisconnectButton = showDisconnectButton,
        showManualSyncButton = showManualSyncButton,
        showDisconnectConfirmDialog = showDisconnectConfirmDialog,
        recentEvents = listOf(
            RecentEventSummary(
                id = "evt-1",
                timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                title = "계약 검토",
            ),
        ),
    )

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}

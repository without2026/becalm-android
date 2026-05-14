package com.becalm.android.ui.sources

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
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
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourcesListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun sources_list_shows_contacts_pseudo_source_enrichment_summary() {
        composeTestRule.setContent {
            BecalmTheme {
                SourcesListScreenContent(
                    state = SourcesListUiState(
                        items = listOf(
                            SourceStatusRow(
                                sourceType = "contacts",
                                status = SourceSyncStatus.Connected,
                                lastSyncAt = Instant.parse("2026-04-24T01:00:00Z"),
                                hasError = false,
                                enrichedCount = 7,
                            ),
                        ),
                    ),
                    onBack = {},
                    onRowClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("연락처").assertIsDisplayed()
        composeTestRule.onNodeWithText("7명", substring = true).assertIsDisplayed()
    }

    @Test
    fun sources_list_shows_empty_state() {
        composeTestRule.setContent {
            BecalmTheme {
                SourcesListScreenContent(
                    state = SourcesListUiState(items = emptyList()),
                    onBack = {},
                    onRowClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.sources_empty_title)).assertIsDisplayed()
    }

    @Test
    fun sources_list_uses_display_labels_instead_of_raw_source_or_status_ids() {
        composeTestRule.setContent {
            BecalmTheme {
                SourcesListScreenContent(
                    state = SourcesListUiState(
                        items = listOf(
                            SourceStatusRow(
                                sourceType = "gmail",
                                status = SourceSyncStatus.Syncing,
                                lastSyncAt = Instant.parse("2026-04-24T01:00:00Z"),
                                hasError = false,
                            ),
                            SourceStatusRow(
                                sourceType = "naver_imap",
                                status = SourceSyncStatus.Disconnected,
                                lastSyncAt = null,
                                hasError = false,
                                help = UiMessage.resource(R.string.sources_status_help_disconnected),
                                recommendedActionLabelRes = R.string.action_connect,
                            ),
                            SourceStatusRow(
                                sourceType = "outlook_mail",
                                status = SourceSyncStatus.Error,
                                lastSyncAt = null,
                                hasError = true,
                                help = UiMessage.resource(R.string.sources_status_help_error),
                                recommendedActionLabelRes = R.string.action_reconnect,
                            ),
                        ),
                    ),
                    onBack = {},
                    onRowClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.raw_event_source_badge_gmail)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.raw_event_source_badge_naver_imap)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.raw_event_source_badge_outlook_mail)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.sources_status_syncing)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.sources_status_disconnected)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.sources_status_error)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.sources_last_error_generic)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("sources-list", useUnmergedTree = true)
            .performScrollToNode(hasText(string(R.string.sources_status_help_error)))
        composeTestRule.onNodeWithText(string(R.string.sources_status_help_error)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.sources_status_help_disconnected)).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            string(
                R.string.sources_status_recommended_action_fmt,
                string(R.string.action_reconnect),
            ),
        ).assertIsDisplayed()

        composeTestRule.onAllNodesWithText("gmail").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("naver_imap").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("outlook_mail").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("SYNCING").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("NEVER_CONNECTED").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("ERROR").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("토큰이 만료되었습니다.").assertCountEquals(0)
    }

    @Test
    fun sources_list_dispatches_contacts_row_click() {
        var tappedSource: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                SourcesListScreenContent(
                    state = SourcesListUiState(
                        items = listOf(
                            SourceStatusRow(
                                sourceType = "contacts",
                                status = SourceSyncStatus.Connected,
                                lastSyncAt = Instant.parse("2026-04-24T01:00:00Z"),
                                hasError = false,
                                enrichedCount = 7,
                            ),
                        ),
                    ),
                    onBack = {},
                    onRowClick = { tappedSource = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("sources-row-contacts").performClick()

        composeTestRule.runOnIdle {
            assertEquals("contacts", tappedSource)
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}

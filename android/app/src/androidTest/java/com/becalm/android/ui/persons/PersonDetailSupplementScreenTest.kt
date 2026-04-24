package com.becalm.android.ui.persons

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonDetailSupplementScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun unassigned_events_content_shows_empty_state() {
        composeTestRule.setContent {
            BecalmTheme {
                UnassignedEventsContent(
                    loading = false,
                    unassignedEvents = emptyList(),
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.persons_unassigned_empty_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.persons_unassigned_empty_message))
            .assertIsDisplayed()
    }

    @Test
    fun unassigned_events_content_shows_rows() {
        composeTestRule.setContent {
            BecalmTheme {
                UnassignedEventsContent(
                    loading = false,
                    unassignedEvents = listOf(
                        UnassignedEventSummary(
                            id = "event-1",
                            sourceType = SourceType.VOICE,
                            title = "Unassigned voice event",
                            timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                        ),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("Unassigned voice event").assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.raw_event_source_badge_voice))
            .assertCountEquals(1)
    }

    @Test
    fun raw_event_detail_content_shows_email_sections_badges_and_expandable_body() {
        val longBody = buildString {
            append("A".repeat(520))
            append("TAIL")
        }

        composeTestRule.setContent {
            BecalmTheme {
                RawEventDetailContent(
                    state = RawEventDetailUiState(
                        eventId = "event-1",
                        sourceType = SourceType.GMAIL,
                        eventTitle = "Proposal",
                        timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                        snippet = "Summary",
                        emailBody = EmailBodyUi(
                            bodyPlain = longBody,
                            bodyHtml = null,
                        ),
                        attachmentCount = 2,
                        commitmentsExtractedCount = 3,
                        loading = false,
                    ),
                    onViewOriginalRequested = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Proposal").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.raw_event_attachments_count, 2))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.raw_event_commitments_extracted, 3))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("raw-event-body").assertTextContains("AAAA", substring = true)
        composeTestRule.onNodeWithText(string(R.string.raw_event_body_expand)).performClick()
        composeTestRule.onNodeWithTag("raw-event-body").assertTextContains("TAIL", substring = true)
        composeTestRule.onNodeWithText(string(R.string.raw_event_body_collapse)).performClick()
        composeTestRule.onNodeWithText(string(R.string.raw_event_body_expand)).assertIsDisplayed()
    }

    @Test
    fun raw_event_detail_content_shows_html_only_degrade_action() {
        var originalClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                RawEventDetailContent(
                    state = RawEventDetailUiState(
                        eventId = "event-1",
                        sourceType = SourceType.GMAIL,
                        eventTitle = "HTML mail",
                        timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                        emailBody = EmailBodyUi(
                            bodyPlain = null,
                            bodyHtml = "<p>body</p>",
                        ),
                        loading = false,
                    ),
                    onViewOriginalRequested = { originalClicks += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.raw_event_body_html_only_notice))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.raw_event_view_html_original)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, originalClicks)
        }
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}

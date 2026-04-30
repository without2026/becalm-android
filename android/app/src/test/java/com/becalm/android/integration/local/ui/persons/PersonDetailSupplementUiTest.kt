package com.becalm.android.integration.local.ui.persons

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.persons.EmailBodyUi
import com.becalm.android.ui.persons.RawEventDetailContent
import com.becalm.android.ui.persons.RawEventDetailUiState
import com.becalm.android.ui.persons.PersonMatchCandidateSummary
import com.becalm.android.ui.persons.UnassignedEventSummary
import com.becalm.android.ui.persons.UnassignedEventsContent
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
class PersonDetailSupplementUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `unassigned events content shows empty state`() {
        composeRule.setContent {
            BecalmTheme {
                UnassignedEventsContent(
                    loading = false,
                    unassignedEvents = emptyList(),
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.persons_unassigned_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.persons_unassigned_empty_message)).assertIsDisplayed()
    }

    @Test
    fun `unassigned events content shows rows`() {
        composeRule.setContent {
            BecalmTheme {
                UnassignedEventsContent(
                    loading = false,
                    unassignedEvents = listOf(
                        UnassignedEventSummary(
                            id = "event-1",
                            sourceType = SourceType.VOICE,
                            title = "미분류 음성 이벤트",
                            timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("미분류 음성 이벤트").assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.raw_event_source_badge_voice)).assertCountEquals(1)
    }

    @Test
    fun `unassigned events recommended candidate confirms match`() {
        var matchedAnchor: String? = null
        var matchedNickname: String? = null

        composeRule.setContent {
            BecalmTheme {
                UnassignedEventsContent(
                    loading = false,
                    unassignedEvents = listOf(
                        UnassignedEventSummary(
                            id = "event-candidate",
                            sourceType = SourceType.VOICE,
                            title = "통화 녹음",
                            timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                            candidates = listOf(
                                PersonMatchCandidateSummary(
                                    anchor = "+821012345678",
                                    displayName = "김지훈",
                                    detail = "+821012345678",
                                    role = "counterparty",
                                    evidence = "통화기록 번호와 파일명이 일치",
                                    confidence = 0.91,
                                ),
                            ),
                        ),
                    ),
                    onManualMatch = { _, anchor, nickname ->
                        matchedAnchor = anchor
                        matchedNickname = nickname
                    },
                )
            }
        }

        composeRule.onNodeWithText("김지훈 · 91%").assertIsDisplayed()
        composeRule.onNodeWithTag("unassigned-match-confirm-event-candidate")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("+821012345678", matchedAnchor)
            assertEquals("김지훈", matchedNickname)
        }
    }

    @Test
    fun `unassigned events manual match inputs route anchor and nickname`() {
        var matchedEventId: String? = null
        var matchedAnchor: String? = null
        var matchedNickname: String? = null

        composeRule.setContent {
            BecalmTheme {
                UnassignedEventsContent(
                    loading = false,
                    unassignedEvents = listOf(
                        UnassignedEventSummary(
                            id = "event-1",
                            sourceType = SourceType.GMAIL,
                            title = "예약 알림",
                            timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                        ),
                    ),
                    onManualMatch = { event, anchor, nickname ->
                        matchedEventId = event.id
                        matchedAnchor = anchor
                        matchedNickname = nickname
                    },
                )
            }
        }

        composeRule.onNodeWithTag("unassigned-match-anchor-event-1")
            .performTextInput("noreply@navercorp.com")
        composeRule.onNodeWithTag("unassigned-match-nickname-event-1")
            .performTextInput("네이버 예약팀")
        composeRule.onNodeWithText(string(R.string.persons_manual_match_action)).performClick()

        composeRule.runOnIdle {
            assertEquals("event-1", matchedEventId)
            assertEquals("noreply@navercorp.com", matchedAnchor)
            assertEquals("네이버 예약팀", matchedNickname)
        }
    }

    @Test
    fun `unassigned events add person defaults blank nickname to anchor`() {
        var matchedAnchor: String? = null
        var matchedNickname: String? = null

        composeRule.setContent {
            BecalmTheme {
                UnassignedEventsContent(
                    loading = false,
                    unassignedEvents = listOf(
                        UnassignedEventSummary(
                            id = "event-2",
                            sourceType = SourceType.VOICE,
                            title = "부재중 통화",
                            timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                        ),
                    ),
                    onManualMatch = { _, anchor, nickname ->
                        matchedAnchor = anchor
                        matchedNickname = nickname
                    },
                )
            }
        }

        composeRule.onNodeWithTag("unassigned-match-anchor-event-2")
            .performTextInput("+821012345678")
        composeRule.onNodeWithText(string(R.string.persons_manual_add_person_action)).performClick()

        composeRule.runOnIdle {
            assertEquals("+821012345678", matchedAnchor)
            assertEquals("+821012345678", matchedNickname)
        }
    }

    @Test
    fun `raw event detail content shows email sections badges and expandable body`() {
        val longBody = buildString {
            append("A".repeat(520))
            append("TAIL")
        }

        composeRule.setContent {
            BecalmTheme {
                RawEventDetailContent(
                    state = RawEventDetailUiState(
                        eventId = "event-1",
                        sourceType = SourceType.GMAIL,
                        eventTitle = "제안서",
                        timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                        snippet = "요약",
                        emailBody = EmailBodyUi(
                            bodyPlain = longBody,
                            bodyHtml = null,
                        ),
                        attachmentCount = 2,
                        commitmentsExtractedCount = 3,
                        loading = false,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("제안서").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.raw_event_attachments_count, 2)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.raw_event_commitments_extracted, 3)).assertIsDisplayed()
        composeRule.onNodeWithTag("raw-event-body").assertTextContains("AAAA", substring = true)
        composeRule.onNodeWithText(string(R.string.raw_event_body_expand)).performClick()
        composeRule.onNodeWithTag("raw-event-body").assertTextContains("TAIL", substring = true)
        composeRule.onNodeWithText(string(R.string.raw_event_body_collapse)).performClick()
        composeRule.onNodeWithText(string(R.string.raw_event_body_expand)).assertIsDisplayed()
    }

    @Test
    fun `raw event detail content shows html only degrade notice without fake action`() {
        composeRule.setContent {
            BecalmTheme {
                RawEventDetailContent(
                    state = RawEventDetailUiState(
                        eventId = "event-1",
                        sourceType = SourceType.GMAIL,
                        eventTitle = "HTML 메일",
                        timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                        emailBody = EmailBodyUi(
                            bodyPlain = null,
                            bodyHtml = "<p>body</p>",
                        ),
                        loading = false,
                    ),
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.raw_event_body_html_only_notice)).assertIsDisplayed()
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}

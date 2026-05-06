package com.becalm.android.ui.persons

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun person_detail_shows_source_event_timeline_card_and_action_count_badge() {
        setScreen(
            state = baseState(
                sourceEventCards = listOf(
                    sourceEventCard(
                        rawEventId = "event-1",
                        title = "팀 회의",
                        snippet = "다음 주까지 제안서 보내기",
                        commitmentsExtractedCount = 2,
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithText(string(R.string.person_detail_timeline_section_fmt, 1))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("팀 회의").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.raw_event_commitments_extracted, 2))
            .assertIsDisplayed()
    }

    @Test
    fun person_detail_renders_commitment_buckets_inside_source_event_card() {
        setScreen(
            state = baseState(
                sourceEventCards = listOf(
                    sourceEventCard(
                        myActions = listOf(
                            PersonDetailCommitmentSummary(
                                title = "제안서 보내기",
                                itemType = "action",
                                direction = "give",
                            ),
                        ),
                        theirActions = listOf(
                            PersonDetailCommitmentSummary(
                                title = "자료 확인하기",
                                itemType = "action",
                                direction = "take",
                            ),
                        ),
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithText("내가 해야 할 일").assertIsDisplayed()
        composeTestRule.onNodeWithText("제안서 보내기").assertIsDisplayed()
        composeTestRule.onNodeWithText("상대가 해야 할 일").assertIsDisplayed()
        composeTestRule.onNodeWithText("자료 확인하기").assertIsDisplayed()
    }

    @Test
    fun person_detail_event_row_tap_dispatches_raw_event_id() {
        var tappedEventId: String? = null

        setScreen(
            state = baseState(
                sourceEventCards = listOf(
                    sourceEventCard(
                        rawEventId = "event-7",
                        title = "콜 녹음",
                        snippet = "금요일까지 회신",
                        commitmentsExtractedCount = 1,
                    ),
                ),
            ),
            onEventTap = { tappedEventId = it },
        )

        composeTestRule.onNodeWithText("콜 녹음").performClick()

        composeTestRule.runOnIdle {
            assertEquals("event-7", tappedEventId)
        }
    }

    private fun setScreen(
        state: PersonDetailUiState,
        onEventTap: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            BecalmTheme {
                PersonDetailScreenContent(
                    state = state,
                    title = "김철수",
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onEventTap = onEventTap,
                )
            }
        }
    }

    private fun baseState(
        sourceEventCards: List<SourceEventCardProjection> = emptyList(),
    ): PersonDetailUiState = PersonDetailUiState(
        personId = "+821012345678",
        displayName = "김철수",
        nickname = "철수",
        companyName = "ABC Corp",
        jobTitle = "팀장",
        eventCount = 2,
        emailInteractionCount = 0,
        callInteractionCount = 1,
        meetingCount = 0,
        pendingCommitmentCount = 1,
        sourceEventCards = sourceEventCards,
        loading = false,
    )

    private fun sourceEventCard(
        rawEventId: String? = "event-1",
        title: String = "팀 회의",
        snippet: String? = "다음 주까지 제안서 보내기",
        commitmentsExtractedCount: Int = 0,
        myActions: List<PersonDetailCommitmentSummary> = emptyList(),
        theirActions: List<PersonDetailCommitmentSummary> = emptyList(),
    ): SourceEventCardProjection = SourceEventCardProjection(
        sourceEventKey = rawEventId ?: title,
        sourceType = "voice",
        rawEventId = rawEventId,
        occurredAt = Instant.parse("2026-04-24T01:00:00Z"),
        title = title,
        snippet = snippet,
        commitmentsExtractedCount = commitmentsExtractedCount,
        myActions = myActions,
        theirActions = theirActions,
    )

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}

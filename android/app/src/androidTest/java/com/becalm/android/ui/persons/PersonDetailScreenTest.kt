package com.becalm.android.ui.persons

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
    fun person_detail_shows_section_headers_and_action_count_badge_while_completed_section_is_collapsed() {
        setScreen(
            state = baseState(
                completedExpanded = false,
                interactionHistory = listOf(
                    InteractionRow.Event(
                        id = "event-1",
                        timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                        source = "voice",
                        summary = "팀 회의",
                        snippet = "다음 주까지 제안서 보내기",
                        commitmentsExtractedCount = 2,
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithText(string(R.string.person_detail_section_pending_fmt, 1))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.person_detail_section_completed_fmt, 1))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.person_detail_history_section))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.raw_event_commitments_extracted, 2))
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText("완료된 약속").assertCountEquals(0)
    }

    @Test
    fun person_detail_renders_completed_rows_when_section_is_expanded() {
        setScreen(
            state = baseState(completedExpanded = true),
        )

        composeTestRule.onNodeWithText("완료된 약속").assertIsDisplayed()
    }

    @Test
    fun person_detail_event_row_tap_dispatches_raw_event_id() {
        var tappedEventId: String? = null

        setScreen(
            state = baseState(
                interactionHistory = listOf(
                    InteractionRow.Event(
                        id = "event-7",
                        timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                        source = "voice",
                        summary = "콜 녹음",
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
                    onToggleCompletedExpanded = {},
                )
            }
        }
    }

    private fun baseState(
        completedExpanded: Boolean = false,
        interactionHistory: List<InteractionRow> = emptyList(),
    ): PersonDetailUiState = PersonDetailUiState(
        personRef = "+821012345678",
        displayName = "김철수",
        nickname = "철수",
        companyName = "ABC Corp",
        jobTitle = "팀장",
        eventCount = 2,
        pendingCommitmentCount = 1,
        completedExpanded = completedExpanded,
        pendingCommitments = listOf(
            InteractionRow.Commitment(
                timestamp = Instant.parse("2026-04-24T00:00:00Z"),
                title = "제안서 보내기",
                direction = "give",
                actionState = "pending",
            ),
        ),
        completedCommitments = listOf(
            InteractionRow.Commitment(
                timestamp = Instant.parse("2026-04-23T00:00:00Z"),
                title = "완료된 약속",
                direction = "take",
                actionState = "completed",
            ),
        ),
        interactionHistory = interactionHistory,
        loading = false,
    )

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}

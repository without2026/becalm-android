package com.becalm.android.integration.local.ui.persons

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.ui.persons.InteractionRow
import com.becalm.android.ui.persons.InteractionHistoryRow
import com.becalm.android.ui.persons.PersonDetailScreenContent
import com.becalm.android.ui.persons.PersonDetailUiState
import com.becalm.android.ui.persons.PersonRow
import com.becalm.android.ui.persons.PersonsScreenContent
import com.becalm.android.ui.persons.PersonsUiState
import com.becalm.android.ui.persons.UnassignedEventSummary
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
class PersonsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `persons screen shows offline badge unassigned section and enriched row meta`() {
        composeRule.setContent {
            BecalmTheme {
                PersonsScreenContent(
                    state = PersonsUiState(
                        people = listOf(
                            PersonRow(
                                personRef = "+821012345678",
                                displayName = "김철수",
                                companyName = "ABC Corp",
                                jobTitle = "팀장",
                                lastInteractionAt = Instant.parse("2026-04-24T01:00:00Z"),
                                interactionCount = 3,
                                pendingCommitmentCount = 2,
                                lastInteractionSnippet = "계약서 검토 요청",
                            ),
                        ),
                        unassignedEvents = listOf(
                            UnassignedEventSummary(
                                id = "event-unassigned",
                                sourceType = "voice",
                                title = "미분류 이벤트",
                                timestamp = Instant.parse("2026-04-24T01:05:00Z"),
                            ),
                        ),
                        showOfflineBadge = true,
                        offlineLastSyncAt = null,
                        loading = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onQueryChange = {},
                    onPersonClick = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.persons_unassigned_title)).assertIsDisplayed()
        composeRule.onNodeWithText("미분류 이벤트").assertExists()
        composeRule.onNodeWithText(string(R.string.persons_offline_badge_no_sync)).assertIsDisplayed()
        composeRule.onNodeWithText("김철수 · ABC Corp · 팀장").assertExists()
        composeRule.onNodeWithText(string(R.string.persons_pending_commitments_fmt, 2)).assertExists()
        composeRule.onNodeWithText("계약서 검토 요청").assertExists()
    }

    @Test
    fun `person detail shows sections while completed rows stay collapsed`() {
        composeRule.setContent {
            BecalmTheme {
                PersonDetailScreenContent(
                    state = PersonDetailUiState(
                        personRef = "+821012345678",
                        displayName = "김철수",
                        nickname = "철수",
                        companyName = "ABC Corp",
                        jobTitle = "팀장",
                        eventCount = 2,
                        pendingCommitmentCount = 1,
                        completedExpanded = false,
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
                        loading = false,
                    ),
                    title = "김철수",
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onEventTap = {},
                    onToggleCompletedExpanded = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.person_detail_section_pending_fmt, 1)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.person_detail_section_completed_fmt, 1)).assertIsDisplayed()
        composeRule.onAllNodesWithText("완료된 약속").assertCountEquals(0)
    }

    @Test
    fun `interaction history event row dispatches raw event tap`() {
        var tappedEventId: String? = null

        composeRule.setContent {
            BecalmTheme {
                InteractionHistoryRow(
                    row = InteractionRow.Event(
                        id = "event-7",
                        timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                        source = "voice",
                        summary = "콜 녹음",
                        snippet = "금요일까지 회신",
                        commitmentsExtractedCount = 1,
                    ),
                    onEventTap = { tappedEventId = it },
                )
            }
        }

        composeRule.onNodeWithTag("person-detail-event-event-7").performClick()

        composeRule.runOnIdle { assertEquals("event-7", tappedEventId) }
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}

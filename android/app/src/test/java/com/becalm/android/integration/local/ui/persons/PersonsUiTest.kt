package com.becalm.android.integration.local.ui.persons

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
                                personId = "+821012345678",
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
                    onBlockPerson = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.person_matching_required_banner_title)).assertExists()
        composeRule.onNodeWithText(string(R.string.persons_offline_badge_no_sync)).assertIsDisplayed()
        composeRule.onNodeWithText("김철수 · ABC Corp · 팀장").assertExists()
        composeRule.onNodeWithText(string(R.string.persons_pending_commitments_fmt, 2)).assertExists()
        composeRule.onNodeWithText("계약서 검토 요청").assertExists()
        composeRule.onNodeWithTag("persons-list")
            .performScrollToNode(hasText(string(R.string.persons_unassigned_title)))
        composeRule.onNodeWithText(string(R.string.persons_unassigned_title)).assertExists()
        composeRule.onNodeWithTag("persons-list")
            .performScrollToNode(hasText("미분류 이벤트"))
        composeRule.onNodeWithText("미분류 이벤트").assertExists()
    }

    @Test
    fun `persons search input routes typed query`() {
        var typedQuery: String? = null

        composeRule.setContent {
            BecalmTheme {
                PersonsScreenContent(
                    state = PersonsUiState(
                        people = listOf(
                            PersonRow(
                                personId = "kim@example.com",
                                displayName = "김철수",
                                lastInteractionAt = Instant.parse("2026-04-24T01:00:00Z"),
                                interactionCount = 1,
                            ),
                        ),
                        loading = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onQueryChange = { typedQuery = it },
                    onPersonClick = {},
                    onBlockPerson = {},
                )
            }
        }

        composeRule.onNodeWithTag("persons-search-input").performTextInput("김")

        composeRule.runOnIdle { assertEquals("김", typedQuery) }
    }

    @Test
    fun `person detail shows source filters and unified timeline`() {
        composeRule.setContent {
            BecalmTheme {
                PersonDetailScreenContent(
                    state = PersonDetailUiState(
                        personId = "+821012345678",
                        displayName = "김철수",
                        nickname = "철수",
                        companyName = "ABC Corp",
                        jobTitle = "팀장",
                        eventCount = 2,
                        pendingCommitmentCount = 1,
                        timeline = listOf(
                            InteractionRow.Event(
                                id = "event-7",
                                timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                                source = "voice",
                                summary = "콜 녹음",
                                snippet = "금요일까지 회신",
                                commitmentsExtractedCount = 1,
                            ),
                            InteractionRow.Commitment(
                                timestamp = Instant.parse("2026-04-24T00:00:00Z"),
                                title = "제안서 보내기",
                                direction = "give",
                                actionState = "pending",
                            ),
                            InteractionRow.Commitment(
                                timestamp = Instant.parse("2026-04-23T00:00:00Z"),
                                title = "완료된 약속",
                                direction = "take",
                                actionState = "completed",
                            ),
                        ),
                        loading = false,
                    ),
                    title = "김철수",
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onEventTap = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.person_detail_filter_all)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.person_detail_timeline_section_fmt, 3)).assertIsDisplayed()
        composeRule.onNodeWithTag("person-detail-list")
            .performScrollToNode(hasText("제안서 보내기"))
        composeRule.onNodeWithText("제안서 보내기").assertExists()
        composeRule.onNodeWithTag("person-detail-list")
            .performScrollToNode(hasText("완료된 약속"))
        composeRule.onNodeWithText("완료된 약속").assertExists()
        composeRule.onNodeWithTag("person-detail-list")
            .performScrollToNode(hasText(string(R.string.person_detail_filter_email)))
        composeRule.onNodeWithTag("person-detail-filter-email").performClick()
        composeRule.onNodeWithText(string(R.string.person_detail_timeline_section_fmt, 0)).assertExists()
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

package com.becalm.android.ui.persons

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.main.MainTabHeaderState
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeoplePipelineCheckpoint4E2eTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun e2e_042_calendar_event_renders_schedule_context_inside_person_timeline() {
        setPersonDetail(
            cards = listOf(
                card(
                    sourceEventKey = "calendar-1",
                    sourceType = SourceType.GOOGLE_CALENDAR,
                    title = "파트너 미팅",
                    schedules = listOf(
                        PersonDetailCommitmentSummary(
                            title = "오늘 오후 3시 미팅 확정",
                            itemType = CommitmentItemType.SCHEDULE,
                            status = "confirmed",
                        ),
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithText("파트너 미팅").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.commitment_item_type_schedule)).assertIsDisplayed()
        composeTestRule.onNodeWithText("오늘 오후 3시 미팅 확정").assertIsDisplayed()
    }

    @Test
    fun e2e_043_old_calendar_history_is_not_present_once_projection_applies_cutoff() {
        setPersonDetail(
            cards = listOf(
                card(
                    sourceEventKey = "calendar-current",
                    sourceType = SourceType.GOOGLE_CALENDAR,
                    title = "내일 회의",
                ),
            ),
        )

        composeTestRule.onNodeWithText("내일 회의").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("지난달 회의").assertCountEquals(0)
    }

    @Test
    fun e2e_047_user_confirms_unresolved_participant_as_an_existing_person() {
        var matchedAnchor: String? = null
        var matchedNickname: String? = null

        setUnassigned(
            events = listOf(
                UnassignedEventSummary(
                    id = "event-candidate",
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    title = "카카오톡 캡처",
                    timestamp = NOW,
                    candidates = listOf(
                        PersonMatchCandidateSummary(
                            anchor = "jane@acme.kr",
                            displayName = "Jane Kim",
                            detail = "jane@acme.kr",
                            role = "recipient",
                            evidence = "이메일 주소가 연락처와 일치",
                            confidence = 0.93,
                        ),
                    ),
                ),
            ),
            onManualMatch = { _, anchor, nickname ->
                matchedAnchor = anchor
                matchedNickname = nickname
            },
        )

        composeTestRule.onNodeWithText("Jane Kim · 93%").assertIsDisplayed()
        composeTestRule.onNodeWithTag("unassigned-match-confirm-event-candidate")
            .performScrollTo()
            .performClick()

        composeTestRule.runOnIdle {
            assertEquals("jane@acme.kr", matchedAnchor)
            assertEquals("Jane Kim", matchedNickname)
        }
    }

    @Test
    fun e2e_048_user_rejects_incorrect_suggestion_by_switching_to_another_person() {
        var matchedAnchor: String? = null
        var matchedNickname: String? = null

        setUnassigned(
            events = listOf(
                UnassignedEventSummary(
                    id = "event-wrong",
                    sourceType = SourceType.GMAIL,
                    title = "동명이인 메일",
                    timestamp = NOW,
                    candidates = listOf(
                        PersonMatchCandidateSummary(
                            anchor = "wrong@acme.kr",
                            displayName = "Jane Kim",
                            detail = "wrong@acme.kr",
                            role = "sender",
                            evidence = "이름만 일치",
                            confidence = 0.71,
                        ),
                    ),
                ),
            ),
            onManualMatch = { _, anchor, nickname ->
                matchedAnchor = anchor
                matchedNickname = nickname
            },
        )

        composeTestRule.onNodeWithText(string(R.string.person_match_other_person_action))
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag("unassigned-match-anchor-event-wrong")
            .performTextInput("right@acme.kr")
        composeTestRule.onNodeWithTag("unassigned-match-nickname-event-wrong")
            .performTextInput("Right Jane")
        composeTestRule.onNodeWithText(string(R.string.persons_manual_match_action)).performClick()

        composeTestRule.runOnIdle {
            assertEquals("right@acme.kr", matchedAnchor)
            assertEquals("Right Jane", matchedNickname)
        }
    }

    @Test
    fun e2e_049_people_tab_renders_contact_rows_only_without_work_context_snippets() {
        setPersons(
            people = listOf(
                PersonRow(
                    personId = "person-jane",
                    displayName = "Jane Kim",
                    lastInteractionAt = NOW,
                    interactionCount = 7,
                    pendingCommitmentCount = 2,
                    lastInteractionSnippet = "금요일까지 제안서를 보내 달라는 업무 맥락",
                ),
            ),
        )

        composeTestRule.onNodeWithText("Jane Kim").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.persons_interactions_count, 7)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("금요일까지 제안서를 보내 달라는 업무 맥락").assertCountEquals(0)
    }

    @Test
    fun e2e_050_person_detail_renders_source_timeline_plus_give_take_and_schedule_context() {
        setPersonDetail(
            cards = listOf(
                card(
                    sourceEventKey = "gmail-1",
                    sourceType = SourceType.GMAIL,
                    title = "제안서 메일",
                    snippet = "내가 초안을 보내고 상대가 가격표를 확인",
                    myActions = listOf(PersonDetailCommitmentSummary("제안서 초안 보내기", "action", "give")),
                    theirActions = listOf(PersonDetailCommitmentSummary("가격표 확인하기", "action", "take")),
                    schedules = listOf(PersonDetailCommitmentSummary("금요일 10시 리뷰", "schedule")),
                ),
            ),
        )

        composeTestRule.onNodeWithText("제안서 메일").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.person_detail_bucket_my_actions)).assertIsDisplayed()
        composeTestRule.onNodeWithText("제안서 초안 보내기").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.person_detail_bucket_their_actions)).assertIsDisplayed()
        composeTestRule.onNodeWithText("가격표 확인하기").assertIsDisplayed()
        composeTestRule.onNodeWithText("금요일 10시 리뷰").assertIsDisplayed()
    }

    @Test
    fun e2e_051_decision_context_does_not_render_as_a_noisy_timeline_action_bucket() {
        setPersonDetail(
            cards = listOf(
                card(
                    sourceEventKey = "gmail-no-decision-card",
                    sourceType = SourceType.GMAIL,
                    title = "의사결정 메일",
                    snippet = "A안으로 진행하기로 합의",
                    myActions = emptyList(),
                    theirActions = emptyList(),
                    schedules = emptyList(),
                ),
            ),
        )

        composeTestRule.onNodeWithText("의사결정 메일").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("A안 승인").assertCountEquals(0)
    }

    @Test
    fun e2e_052_tapping_person_timeline_card_opens_original_source_event() {
        var openedEventId: String? = null

        setPersonDetail(
            cards = listOf(
                card(
                    sourceEventKey = "call-1",
                    sourceType = SourceType.CALL_RECORDING,
                    rawEventId = "raw-call-1",
                    title = "통화 녹음",
                ),
            ),
            onEventTap = { openedEventId = it },
        )

        composeTestRule.onNodeWithTag("person-detail-source-card-call-1").performClick()

        composeTestRule.runOnIdle {
            assertEquals("raw-call-1", openedEventId)
        }
    }

    private fun setPersons(
        people: List<PersonRow>,
        onPersonClick: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            BecalmTheme {
                PersonsScreenContent(
                    state = PersonsUiState(
                        people = people,
                        personSections = buildPersonSections(people),
                        loading = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onQueryChange = {},
                    onPersonClick = onPersonClick,
                    headerState = MainTabHeaderState(),
                )
            }
        }
    }

    private fun setPersonDetail(
        cards: List<SourceEventCardProjection>,
        onEventTap: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            BecalmTheme {
                PersonDetailScreenContent(
                    state = PersonDetailUiState(
                        personId = "person-jane",
                        displayName = "Jane Kim",
                        eventCount = cards.size,
                        sourceEventCards = cards,
                        loading = false,
                    ),
                    title = "Jane Kim",
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onEventTap = onEventTap,
                )
            }
        }
    }

    private fun setUnassigned(
        events: List<UnassignedEventSummary>,
        onManualMatch: (UnassignedEventSummary, String, String) -> Unit,
    ) {
        composeTestRule.setContent {
            BecalmTheme {
                UnassignedEventsContent(
                    loading = false,
                    unassignedEvents = events,
                    onManualMatch = onManualMatch,
                )
            }
        }
    }

    private fun card(
        sourceEventKey: String,
        sourceType: String,
        rawEventId: String? = "raw-$sourceEventKey",
        title: String,
        snippet: String? = null,
        myActions: List<PersonDetailCommitmentSummary> = emptyList(),
        theirActions: List<PersonDetailCommitmentSummary> = emptyList(),
        schedules: List<PersonDetailCommitmentSummary> = emptyList(),
    ): SourceEventCardProjection =
        SourceEventCardProjection(
            sourceEventKey = sourceEventKey,
            sourceType = sourceType,
            rawEventId = rawEventId,
            occurredAt = NOW,
            title = title,
            snippet = snippet,
            myActions = myActions,
            theirActions = theirActions,
            schedules = schedules,
        )

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)

    private companion object {
        val NOW: Instant = Instant.parse("2026-05-05T00:00:00Z")
    }
}

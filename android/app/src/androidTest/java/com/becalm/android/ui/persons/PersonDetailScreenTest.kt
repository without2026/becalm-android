package com.becalm.android.ui.persons

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.ui.actions.PersonActionEvidenceDetailUi
import com.becalm.android.ui.actions.PersonActionEvidenceUi
import com.becalm.android.ui.actions.PersonActionItemUi
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
    fun person_detail_keeps_extracted_commitments_out_of_source_timeline_card() {
        setScreen(
            state = baseState(
                sourceEventCards = listOf(
                    sourceEventCard(
                        commitmentsExtractedCount = 2,
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

        composeTestRule.onNodeWithText(string(R.string.raw_event_commitments_extracted, 2)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.person_detail_bucket_my_actions)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("제안서 보내기").assertCountEquals(0)
        composeTestRule.onAllNodesWithText(string(R.string.person_detail_bucket_their_actions)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("자료 확인하기").assertCountEquals(0)
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

    @Test
    fun person_detail_next_action_tap_forwards_evidence_lookup_ids() {
        var openedActionId: String? = null
        var openedEvidenceKind: String? = null
        var openedEvidenceId: String? = null

        setScreen(
            state = baseState(
                sourceEventCards = listOf(sourceEventCard(rawEventId = "event-7")),
                topActions = listOf(personAction()),
            ),
            onOpenPersonActionEvidence = { actionId, evidenceKind, evidenceId ->
                openedActionId = actionId
                openedEvidenceKind = evidenceKind
                openedEvidenceId = evidenceId
            },
        )

        composeTestRule.onNodeWithTag("person-detail-action-pa-1").performClick()

        composeTestRule.runOnIdle {
            assertEquals("pa-1", openedActionId)
            assertEquals("source_event", openedEvidenceKind)
            assertEquals("source-1", openedEvidenceId)
        }
    }

    @Test
    fun person_detail_renders_first_action_as_primary_and_keeps_secondary_actions() {
        setScreen(
            state = baseState(
                sourceEventCards = listOf(sourceEventCard(rawEventId = "event-7")),
                topActions = listOf(
                    personAction(id = "pa-primary", title = "수정 계약서 회신"),
                    personAction(id = "pa-secondary-1", title = "자료 확인 요청"),
                    personAction(id = "pa-secondary-2", title = "미팅 날짜 제안"),
                ),
            ),
        )

        composeTestRule.onAllNodesWithTag("person-detail-primary-action", useUnmergedTree = true)
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("person-detail-secondary-actions-title").assertCountEquals(1)
        composeTestRule.onNodeWithText(string(R.string.person_detail_secondary_actions_title_fmt, 2))
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("person-detail-secondary-actions", useUnmergedTree = true)
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("person-detail-action-pa-primary").assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("person-detail-action-pa-secondary-1").assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("person-detail-action-pa-secondary-2").assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(
            "person-detail-secondary-action-pa-secondary-1",
            useUnmergedTree = true,
        ).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(
            "person-detail-secondary-action-pa-secondary-2",
            useUnmergedTree = true,
        ).assertCountEquals(1)
    }

    @Test
    fun person_detail_take_action_reminder_button_forwards_action_id() {
        var remindedActionId: String? = null

        setScreen(
            state = baseState(
                sourceEventCards = listOf(sourceEventCard(rawEventId = "event-7")),
                topActions = listOf(
                    personAction(
                        dueAt = Instant.parse("2026-06-05T03:00:00Z"),
                        reasonCodes = listOf("direction:take", "waiting_on"),
                    ),
                ),
            ),
            onRemindPersonAction = { remindedActionId = it },
        )

        composeTestRule.onAllNodesWithText(string(R.string.commitment_action_remind))
            .onFirst()
            .performClick()

        composeTestRule.runOnIdle {
            assertEquals("pa-1", remindedActionId)
        }
    }

    @Test
    fun person_detail_complete_button_forwards_action_id() {
        var completedActionId: String? = null

        setScreen(
            state = baseState(
                sourceEventCards = listOf(sourceEventCard(rawEventId = "event-7")),
                topActions = listOf(personAction()),
            ),
            onCompletePersonAction = { completedActionId = it },
        )

        composeTestRule.onAllNodesWithText(string(R.string.commitment_action_complete))
            .onFirst()
            .performClick()

        composeTestRule.runOnIdle {
            assertEquals("pa-1", completedActionId)
        }
    }

    @Test
    fun person_detail_dismiss_button_forwards_action_id() {
        var dismissedActionId: String? = null

        setScreen(
            state = baseState(
                sourceEventCards = listOf(sourceEventCard(rawEventId = "event-7")),
                topActions = listOf(personAction()),
            ),
            onDismissPersonAction = { dismissedActionId = it },
        )

        composeTestRule.onAllNodesWithText(string(R.string.schedule_action_dismiss))
            .onFirst()
            .performClick()

        composeTestRule.runOnIdle {
            assertEquals("pa-1", dismissedActionId)
        }
    }

    @Test
    fun person_detail_renders_action_evidence_dialog_with_why_and_original() {
        setScreen(
            state = baseState(
                sourceEventCards = listOf(sourceEventCard(rawEventId = "event-7")),
                evidenceDetail = PersonActionEvidenceDetailUi(
                    actionItemId = "pa-1",
                    evidenceLabel = "통화",
                    whyText = "통화에서 금요일까지 회신한다고 약속했습니다.",
                    originalTitle = "6/1 통화",
                    originalText = "나: 금요일까지 회신드릴게요.",
                    sourceType = "call_recording",
                ),
            ),
        )

        composeTestRule.onNodeWithText(string(R.string.commitment_action_evidence_why)).assertIsDisplayed()
        composeTestRule.onNodeWithText("통화에서 금요일까지 회신한다고 약속했습니다.").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.commitment_action_evidence_original)).assertIsDisplayed()
        composeTestRule.onNodeWithText("나: 금요일까지 회신드릴게요.").assertIsDisplayed()
    }

    private fun setScreen(
        state: PersonDetailUiState,
        onEventTap: (String) -> Unit = {},
        onOpenPersonActionEvidence: (String, String?, String?) -> Unit = { _, _, _ -> },
        onCompletePersonAction: (String) -> Unit = {},
        onDismissPersonAction: (String) -> Unit = {},
        onRemindPersonAction: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            BecalmTheme {
                PersonDetailScreenContent(
                    state = state,
                    title = "김철수",
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onEventTap = onEventTap,
                    onOpenPersonActionEvidence = onOpenPersonActionEvidence,
                    onCompletePersonAction = onCompletePersonAction,
                    onDismissPersonAction = onDismissPersonAction,
                    onRemindPersonAction = onRemindPersonAction,
                )
            }
        }
    }

    private fun baseState(
        sourceEventCards: List<SourceEventCardProjection> = emptyList(),
        topActions: List<PersonActionItemUi> = emptyList(),
        evidenceDetail: PersonActionEvidenceDetailUi? = null,
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
        topActions = topActions,
        evidenceDetail = evidenceDetail,
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

    private fun personAction(
        id: String = "pa-1",
        title: String = "계약서 회신",
        dueAt: Instant? = null,
        reasonCodes: List<String> = listOf("source:due"),
    ): PersonActionItemUi = PersonActionItemUi(
        id = id,
        personId = "+821012345678",
        personDisplayName = "김철수",
        actionKind = "follow_up",
        title = title,
        primaryVerb = "회신",
        shortReason = "통화에서 약속",
        commitmentId = "commitment-1",
        calendarEventId = null,
        sourceEventId = "event-7",
        sourceType = "call_recording",
        sourceRef = "raw:event-7",
        dueAt = dueAt,
        dueHint = null,
        urgencyScore = 0.95,
        confidence = 0.91,
        reasonCodes = reasonCodes,
        evidence = PersonActionEvidenceUi(
            kind = "source_event",
            id = "source-1",
            sourceRef = "raw:event-7",
            occurredAt = null,
            label = "통화",
            quote = "금요일까지 회신드릴게요.",
        ),
    )

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}

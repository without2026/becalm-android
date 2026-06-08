package com.becalm.android.integration.local.ui.persons

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.persons.ArchivedOriginalUi
import com.becalm.android.ui.persons.EmailBodyUi
import com.becalm.android.ui.persons.PersonMatchChoiceRow
import com.becalm.android.ui.persons.PersonMatchCandidateSummary
import com.becalm.android.ui.actions.PersonActionDraftDialog
import com.becalm.android.ui.actions.PersonActionEvidenceUi
import com.becalm.android.ui.actions.PersonActionItemUi
import com.becalm.android.ui.persons.PersonActionDraftSheetStatus
import com.becalm.android.ui.persons.PersonActionDraftSheetUiState
import com.becalm.android.ui.persons.PersonDetailScreenContent
import com.becalm.android.ui.persons.PersonDetailUiState
import com.becalm.android.ui.persons.RawEventCommitmentSummary
import com.becalm.android.ui.persons.RawEventDetailContent
import com.becalm.android.ui.persons.RawEventDetailUiState
import com.becalm.android.ui.persons.SourceEventCardProjection
import com.becalm.android.ui.persons.UnassignedEventSummary
import com.becalm.android.ui.persons.UnassignedEventsContent
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `person detail keeps one primary action and secondary actions visible`() {
        composeRule.setContent {
            BecalmTheme {
                PersonDetailScreenContent(
                    state = PersonDetailUiState(
                        personId = "person-1",
                        displayName = "김도현",
                        sourceEventCards = listOf(sourceEventCard("event-7")),
                        topActions = listOf(
                            personAction(id = "pa-primary", title = "수정 계약서 회신"),
                            personAction(id = "pa-secondary-1", title = "자료 확인 요청"),
                            personAction(id = "pa-secondary-2", title = "미팅 날짜 제안"),
                        ),
                        loading = false,
                    ),
                    title = "김도현",
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onEventTap = {},
                )
            }
        }

        composeRule.onAllNodesWithTag("person-detail-primary-action", useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.onNodeWithText("수정 계약서 회신").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.person_detail_secondary_actions_title_fmt, 2))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag("person-detail-secondary-actions", useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.onAllNodesWithTag("person-detail-action-pa-secondary-1").assertCountEquals(1)
        composeRule.onAllNodesWithTag("person-detail-action-pa-secondary-2").assertCountEquals(1)
    }

    @Test
    fun `P2-GAP-002 primary action promotes draft and evidence without hiding mutations`() {
        var openedDraftActionId: String? = null
        var openedEvidenceActionId: String? = null
        var dismissedActionId: String? = null
        var completedActionId: String? = null

        composeRule.setContent {
            BecalmTheme {
                PersonDetailScreenContent(
                    state = PersonDetailUiState(
                        personId = "person-1",
                        displayName = "김도현",
                        sourceEventCards = listOf(sourceEventCard("event-7")),
                        topActions = listOf(personAction(id = "pa-primary", title = "수정 계약서 회신")),
                        loading = false,
                    ),
                    title = "김도현",
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onEventTap = {},
                    onOpenPersonActionDraft = { openedDraftActionId = it },
                    onOpenPersonActionEvidence = { actionId, _, _ -> openedEvidenceActionId = actionId },
                    onDismissPersonAction = { dismissedActionId = it },
                    onCompletePersonAction = { completedActionId = it },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.person_action_draft_primary_follow_up)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.person_action_evidence_view)).assertIsDisplayed()
        composeRule.onNodeWithTag("person-detail-action-draft-pa-primary").performClick()
        composeRule.onNodeWithTag("person-detail-action-evidence-pa-primary").performClick()
        composeRule.onNodeWithText(string(R.string.schedule_action_dismiss)).performClick()
        composeRule.onNodeWithText(string(R.string.commitment_action_complete)).performClick()

        composeRule.runOnIdle {
            assertEquals("pa-primary", openedDraftActionId)
            assertEquals("pa-primary", openedEvidenceActionId)
            assertEquals("pa-primary", dismissedActionId)
            assertEquals("pa-primary", completedActionId)
        }
    }

    @Test
    fun `P1-GAP-002 draft sheet evidence action opens matching action evidence`() {
        var openedActionId: String? = null
        var openedEvidenceKind: String? = null
        var openedEvidenceId: String? = null

        composeRule.setContent {
            BecalmTheme {
                PersonDetailScreenContent(
                    state = PersonDetailUiState(
                        personId = "person-1",
                        displayName = "김도현",
                        sourceEventCards = listOf(sourceEventCard("event-7")),
                        topActions = listOf(personAction(id = "pa-draft", title = "수정 계약서 회신")),
                        draftSheet = PersonActionDraftSheetUiState(
                            actionItemId = "pa-draft",
                            actionTitle = "수정 계약서 회신",
                            status = PersonActionDraftSheetStatus.READY,
                            draftKind = "follow_up",
                            subject = "Re: 수정 계약서 회신",
                            body = "확인 후 회신드리겠습니다.",
                            recipientLabel = "김도현 대표",
                            provenanceLabels = listOf("통화"),
                        ),
                        loading = false,
                    ),
                    title = "김도현",
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onEventTap = {},
                    onOpenPersonActionEvidence = { actionId, evidenceKind, evidenceId ->
                        openedActionId = actionId
                        openedEvidenceKind = evidenceKind
                        openedEvidenceId = evidenceId
                    },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.person_action_draft_recipient_label))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.person_action_draft_sheet_label_follow_up))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.person_action_draft_header_recipient_fmt, "김도현 대표"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.person_action_draft_review_notice_context_fmt, "통화"))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("김도현 대표")
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag("person-action-draft-subject")
            .assertCountEquals(0)
        composeRule.onNodeWithTag("person-action-draft-evidence")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("pa-draft", openedActionId)
            assertEquals("source_event", openedEvidenceKind)
            assertEquals("source-pa-draft", openedEvidenceId)
        }
    }

    @Test
    fun `P1-GAP-002 draft sheet opens external mail app from explicit CTA`() {
        var openMailCount = 0
        var editedBody: String? = null

        composeRule.setContent {
            BecalmTheme {
                PersonActionDraftDialog(
                    state = PersonActionDraftSheetUiState(
                        actionItemId = "pa-draft",
                        actionTitle = "수정 계약서 회신",
                        status = PersonActionDraftSheetStatus.READY,
                        draftKind = "reply",
                        subject = "Re: 수정 계약서 회신",
                        body = "확인 후 회신드리겠습니다.",
                        recipientLabel = "김도현 <dh.kim@partner.co.kr>",
                        provenanceLabels = listOf("통화"),
                    ),
                    onSubjectChange = {},
                    onBodyChange = { editedBody = it },
                    onRetry = {},
                    onOpenExternalDraft = { openMailCount += 1 },
                    onDismiss = {},
                )
            }
        }

        composeRule.onAllNodesWithTag("person-action-draft-subject")
            .assertCountEquals(0)
        composeRule.onNodeWithText(string(R.string.person_action_draft_sheet_label_reply))
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            string(R.string.person_action_draft_header_recipient_fmt, "김도현 <dh.kim@partner.co.kr>"),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.person_action_draft_review_notice_context_fmt, "통화"))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("person-action-draft-body-input")
            .performTextInput("\n추가 확인")
        composeRule.runOnIdle {
            assertTrue(editedBody.orEmpty().contains("추가 확인"))
        }
        composeRule.onNodeWithTag("person-action-draft-copy")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("person-action-draft-open-mail")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, openMailCount)
        }
    }

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

        composeRule.onNodeWithText("김지훈")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("unassigned-match-confirm-event-candidate")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("+821012345678", matchedAnchor)
            assertEquals("김지훈", matchedNickname)
        }
    }

    @Test
    fun `unassigned events remain visible until match result is resolved`() {
        var matchedAnchor: String? = null
        val resolvedIds = mutableStateOf(emptySet<String>())

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
                    resolvedMatchEventIds = resolvedIds.value,
                    onManualMatch = { _, anchor, _ -> matchedAnchor = anchor },
                )
            }
        }

        composeRule.onNodeWithTag("unassigned-match-confirm-event-candidate")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("+821012345678", matchedAnchor)
        }
        composeRule.onNodeWithText("통화 녹음").assertIsDisplayed()

        composeRule.runOnIdle {
            resolvedIds.value = setOf("event-candidate")
        }
        composeRule.onNodeWithText(string(R.string.persons_unassigned_empty_title)).assertIsDisplayed()
    }

    @Test
    fun `unassigned events disable match actions while saving`() {
        composeRule.setContent {
            BecalmTheme {
                UnassignedEventsContent(
                    loading = false,
                    unassignedEvents = listOf(
                        UnassignedEventSummary(
                            id = "event-saving",
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
                    savingMatchEventIds = setOf("event-saving"),
                )
            }
        }

        composeRule.onNodeWithTag("unassigned-match-confirm-event-saving")
            .performScrollTo()
            .assertIsNotEnabled()
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
        composeRule.onNodeWithText(string(R.string.persons_manual_add_person_action))
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("event-1", matchedEventId)
            assertEquals("noreply@navercorp.com", matchedAnchor)
            assertEquals("네이버 예약팀", matchedNickname)
        }
    }

    @Test
    fun `unassigned events manual match name only input routes as new person anchor`() {
        var matchedEventId: String? = null
        var matchedAnchor: String? = null
        var matchedNickname: String? = null

        composeRule.setContent {
            BecalmTheme {
                UnassignedEventsContent(
                    loading = false,
                    unassignedEvents = listOf(
                        UnassignedEventSummary(
                            id = "event-name-only",
                            sourceType = SourceType.GMAIL,
                            title = "회의 요청",
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

        composeRule.onNodeWithTag("unassigned-match-nickname-event-name-only")
            .performTextInput("김민지")
        composeRule.onNodeWithText(string(R.string.persons_manual_add_person_action))
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("event-name-only", matchedEventId)
            assertEquals("김민지", matchedAnchor)
            assertEquals("김민지", matchedNickname)
        }
    }

    @Test
    fun `unassigned events self action routes selected event`() {
        var selfMatchedEventId: String? = null

        composeRule.setContent {
            BecalmTheme {
                UnassignedEventsContent(
                    loading = false,
                    unassignedEvents = listOf(
                        UnassignedEventSummary(
                            id = "event-self",
                            sourceType = SourceType.GMAIL,
                            title = "내가 보낸 메일",
                            timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                        ),
                    ),
                    onSelfMatch = { event ->
                        selfMatchedEventId = event.id
                    },
                )
            }
        }

        composeRule.onNodeWithTag("unassigned-match-self-event-self")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("event-self", selfMatchedEventId)
        }
    }

    @Test
    fun `unassigned events suggested self can be rejected and opens manual match`() {
        var notSelfEventId: String? = null
        val notSelfIds = mutableStateOf(setOf<String>())

        composeRule.setContent {
            BecalmTheme {
                UnassignedEventsContent(
                    loading = false,
                    unassignedEvents = listOf(
                        UnassignedEventSummary(
                            id = "event-suggested-self",
                            sourceType = SourceType.GMAIL,
                            title = "Jake가 보낸 메일",
                            suggestedLabel = "Jake",
                            timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                        ),
                    ),
                    notSelfMatchEventIds = notSelfIds.value,
                    onNotSelfMatch = { event ->
                        notSelfEventId = event.id
                        notSelfIds.value = notSelfIds.value + event.id
                    },
                )
            }
        }

        composeRule.onNodeWithTag("unassigned-match-not-self-event-suggested-self")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithTag("unassigned-match-anchor-event-suggested-self").assertIsDisplayed()
        composeRule.onNodeWithTag("unassigned-match-not-self-followup-event-suggested-self").assertIsDisplayed()
        composeRule.onAllNodesWithTag("unassigned-match-self-event-suggested-self").assertCountEquals(0)
        composeRule.runOnIdle {
            assertEquals("event-suggested-self", notSelfEventId)
        }
    }

    @Test
    fun `unassigned events source suggested self routes to contact matching without person confirm`() {
        var notSelfEventId: String? = null
        val notSelfIds = mutableStateOf(setOf<String>())

        composeRule.setContent {
            BecalmTheme {
                UnassignedEventsContent(
                    loading = false,
                    matchChoices = listOf(
                        PersonMatchChoiceRow(
                            anchor = "minji@corp.com",
                            displayName = "김민지",
                            detail = "minji@corp.com",
                            hasInteractions = true,
                        ),
                    ),
                    notSelfMatchEventIds = notSelfIds.value,
                    unassignedEvents = listOf(
                        UnassignedEventSummary(
                            id = "event-source-suggested-self",
                            sourceType = SourceType.GMAIL,
                            title = "Jake가 보낸 메일",
                            timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                            candidates = listOf(
                                PersonMatchCandidateSummary(
                                    anchor = "Jake",
                                    displayName = "Jake",
                                    detail = null,
                                    role = "sender",
                                    evidence = "Jake가 정리하겠습니다.",
                                    confidence = 0.72,
                                    isSelfSuggestion = true,
                                ),
                            ),
                        ),
                    ),
                    onNotSelfMatch = { event ->
                        notSelfEventId = event.id
                        notSelfIds.value = notSelfIds.value + event.id
                    },
                )
            }
        }

        composeRule.onNodeWithTag("unassigned-match-not-self-event-source-suggested-self")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag("unassigned-match-confirm-event-source-suggested-self")
            .assertCountEquals(0)
        composeRule.onAllNodesWithTag("unassigned-match-other-event-source-suggested-self")
            .assertCountEquals(0)

        composeRule.onNodeWithTag("unassigned-match-not-self-event-source-suggested-self")
            .performClick()

        composeRule.onNodeWithTag("unassigned-match-choice-event-source-suggested-self-minji@corp.com")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("unassigned-match-not-self-followup-event-source-suggested-self").assertIsDisplayed()
        composeRule.onAllNodesWithTag("unassigned-match-self-event-source-suggested-self").assertCountEquals(0)
        composeRule.onAllNodesWithTag("unassigned-match-choice-event-source-suggested-self-Jake")
            .assertCountEquals(0)
        composeRule.runOnIdle {
            assertEquals("event-source-suggested-self", notSelfEventId)
        }
    }

    @Test
    fun `unassigned events other person renders existing people and matches selected row`() {
        var matchedAnchor: String? = null
        var matchedNickname: String? = null

        composeRule.setContent {
            BecalmTheme {
                UnassignedEventsContent(
                    loading = false,
                    matchChoices = listOf(
                        PersonMatchChoiceRow(
                            anchor = "minji@corp.com",
                            displayName = "김민지",
                            detail = "minji@corp.com",
                            hasInteractions = true,
                        ),
                        PersonMatchChoiceRow(
                            anchor = "+82109998888",
                            displayName = "박서연",
                            detail = "+82109998888",
                            hasInteractions = false,
                        ),
                    ),
                    unassignedEvents = listOf(
                        UnassignedEventSummary(
                            id = "event-other",
                            sourceType = SourceType.VOICE,
                            title = "회의 녹음",
                            timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                            candidates = listOf(
                                PersonMatchCandidateSummary(
                                    anchor = "SPEAKER_02",
                                    displayName = "SPEAKER_02",
                                    detail = null,
                                    role = "speaker",
                                    evidence = "자료 공유 약속",
                                    confidence = 0.42,
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

        composeRule.onNodeWithTag("unassigned-match-confirm-event-other")
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag("unassigned-match-other-event-other")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("unassigned-match-choice-event-other-minji@corp.com")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("unassigned-match-choice-event-other-+82109998888")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("unassigned-match-choice-event-other-minji@corp.com")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(string(R.string.persons_manual_match_action))
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("minji@corp.com", matchedAnchor)
            assertEquals("김민지", matchedNickname)
        }
    }

    @Test
    fun `unassigned events add person requires display name for technical anchor`() {
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
        composeRule.onNodeWithText(string(R.string.persons_manual_add_person_name_required))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.persons_manual_add_person_action))
            .performScrollTo()
            .assertIsNotEnabled()

        composeRule.runOnIdle {
            assertEquals(null, matchedAnchor)
            assertEquals(null, matchedNickname)
        }
    }

    @Test
    fun `unassigned events add person can use typed name as anchor`() {
        var matchedAnchor: String? = null
        var matchedNickname: String? = null

        composeRule.setContent {
            BecalmTheme {
                UnassignedEventsContent(
                    loading = false,
                    unassignedEvents = listOf(
                        UnassignedEventSummary(
                            id = "event-new-name",
                            sourceType = SourceType.VOICE,
                            title = "회의 녹음",
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

        composeRule.onNodeWithTag("unassigned-match-anchor-event-new-name")
            .performTextInput("김민지")
        composeRule.onNodeWithText(string(R.string.persons_manual_add_person_action))
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("김민지", matchedAnchor)
            assertEquals("김민지", matchedNickname)
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
    fun `raw event detail content shows extracted commitments before long body and keeps body toggle reachable`() {
        val longBody = buildString {
            append("A".repeat(900))
            append("TAIL")
        }

        composeRule.setContent {
            BecalmTheme {
                Box(modifier = Modifier.height(280.dp)) {
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
                            extractedCommitments = listOf(
                                RawEventCommitmentSummary(
                                    id = "commitment-1",
                                    title = "하단 약속",
                                    itemType = "action",
                                    direction = "give",
                                    status = "pending",
                                    quote = "하단까지 스크롤되어야 합니다.",
                                ),
                            ),
                            loading = false,
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("raw-event-detail-list").assertIsDisplayed()
        composeRule.onNodeWithTag("raw-event-detail-list")
            .performScrollToNode(hasTestTag("raw-event-why-action"))
        composeRule.onNodeWithText(string(R.string.commitment_action_evidence_why))
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            string(R.string.raw_event_action_reason_single_fmt, string(R.string.commitments_filter_give), "하단 약속"),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("raw-event-detail-list")
            .performScrollToNode(hasTestTag("raw-event-extracted-commitments"))
        composeRule.onNodeWithText(string(R.string.raw_event_extracted_commitments_title))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("raw-event-extracted-commitments").assertIsDisplayed()
        composeRule.onNodeWithTag("raw-event-detail-list")
            .performScrollToNode(hasTestTag("raw-event-body-toggle"))
    }

    @Test
    fun `raw event detail content shows meeting transcript from archived original`() {
        assertRawEventTranscript(
            sourceType = SourceType.MEETING,
            title = "Carbon Black 회의",
        )
    }

    @Test
    fun `raw event detail content shows call transcript from archived original`() {
        assertRawEventTranscript(
            sourceType = SourceType.CALL_RECORDING,
            title = "HS0007 통화",
        )
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

    private fun assertRawEventTranscript(
        sourceType: String,
        title: String,
    ) {
        composeRule.setContent {
            BecalmTheme {
                RawEventDetailContent(
                    state = RawEventDetailUiState(
                        eventId = "event-transcript",
                        sourceType = sourceType,
                        eventTitle = title,
                        timestamp = Instant.parse("2026-04-24T01:00:00Z"),
                        archivedOriginal = ArchivedOriginalUi(
                            bodyText = "[00:00-00:05] SPEAKER_01: 다음 주 수요일에 다시 이야기해요.",
                            deletedFromDevice = false,
                            truncated = false,
                        ),
                        loading = false,
                    ),
                )
            }
        }

        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.raw_event_transcript_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("raw-event-body").assertTextContains("SPEAKER_01", substring = true)
        composeRule.onNodeWithTag("raw-event-body").assertTextContains("다음 주 수요일", substring = true)
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)

    private fun sourceEventCard(rawEventId: String): SourceEventCardProjection =
        SourceEventCardProjection(
            sourceEventKey = rawEventId,
            sourceType = "call_recording",
            rawEventId = rawEventId,
            occurredAt = Instant.parse("2026-04-24T01:00:00Z"),
            title = "수정 계약서 논의",
            snippet = "다음 주까지 제안서 보내기",
        )

    private fun personAction(
        id: String,
        title: String,
    ): PersonActionItemUi =
        PersonActionItemUi(
            id = id,
            personId = "person-1",
            personDisplayName = "김도현",
            actionKind = "follow_up",
            title = title,
            primaryVerb = "회신",
            shortReason = "6/1 통화에서 약속",
            commitmentId = "commitment-$id",
            calendarEventId = null,
            sourceEventId = "event-7",
            sourceType = "call_recording",
            sourceRef = "raw:event-7",
            dueAt = null,
            dueHint = "오늘까지",
            urgencyScore = 0.91,
            confidence = 0.9,
            reasonCodes = listOf("source:due"),
            evidence = PersonActionEvidenceUi(
                kind = "source_event",
                id = "source-$id",
                sourceRef = "raw:event-7",
                occurredAt = null,
                label = "통화",
                quote = "오늘까지 회신드릴게요.",
            ),
        )
}

package com.becalm.android.integration.local.ui.commitments

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentScheduleStatus
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.commitment.CommitmentEditValidator
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.domain.commitment.ManualCommitmentDraft
import com.becalm.android.ui.commitments.CommitmentCreateMode
import com.becalm.android.ui.commitments.CommitmentDetailActionState
import com.becalm.android.ui.commitments.CommitmentHistoryPresentation
import com.becalm.android.ui.commitments.CommitmentSheetAction
import com.becalm.android.ui.commitments.CommitmentSourcePresentation
import com.becalm.android.ui.commitments.CreateSheetContent
import com.becalm.android.ui.commitments.CreateUiState
import com.becalm.android.ui.commitments.DetailSheetContent
import com.becalm.android.ui.commitments.EditReadOnly
import com.becalm.android.ui.commitments.EditSheetContent
import com.becalm.android.ui.commitments.EditUiState
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
class CommitmentSheetsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `commitment detail content shows source history and action chips`() {
        var remindClicks = 0
        var editClicks = 0

        composeRule.setContent {
            BecalmTheme {
                DetailSheetContent(
                    entity = commitmentEntity(),
                    quote = "금요일까지 보내겠습니다",
                    actionState = CommitmentState.PENDING,
                    source = CommitmentSourcePresentation(sourceLabel = "voice:4/24 10:00"),
                    history = CommitmentHistoryPresentation(
                        lastEditedAt = Instant.parse("2026-04-24T01:30:00Z"),
                        lastEditedLabel = "마지막 수정: 4/24 10:30 (본인)",
                        disputedLabel = "이의 제기됨",
                        disputeRaisedAt = Instant.parse("2026-04-24T01:30:00Z"),
                        showSupersedeLink = true,
                    ),
                    actionButtons = CommitmentDetailActionState(
                        availableActions = setOf(
                            CommitmentSheetAction.REMIND,
                            CommitmentSheetAction.FOLLOW_UP,
                            CommitmentSheetAction.COMPLETE,
                        ),
                        editEnabled = true,
                    ),
                    counterpartyDisplayName = "김철수",
                    onRemind = { remindClicks += 1 },
                    onFollowUp = {},
                    onComplete = {},
                    onCancel = {},
                    onEdit = { editClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText("제안서 보내기").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.commitment_item_type_action)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.commitments_filter_give)).assertIsDisplayed()
        composeRule.onNodeWithText("PENDING").assertIsDisplayed()
        composeRule.onNodeWithText("voice:4/24 10:00").assertIsDisplayed()
        composeRule.onAllNodesWithText("마지막 수정: 4/24 10:30 (본인)").assertCountEquals(1)
        composeRule.onAllNodesWithText(string(R.string.commitment_detail_superseded_link)).assertCountEquals(1)
        composeRule.onNodeWithTag("commitment-detail-remind").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("commitment-detail-edit").performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals(1, remindClicks)
            assertEquals(1, editClicks)
        }
    }

    @Test
    fun `commitment detail content renders schedule item as read only trackable`() {
        composeRule.setContent {
            BecalmTheme {
                DetailSheetContent(
                    entity = commitmentEntity(
                        itemType = CommitmentItemType.SCHEDULE,
                        direction = null,
                        scheduleStatus = CommitmentScheduleStatus.CHANGED,
                    ),
                    quote = "9월 19일 11시로 바꿨어요",
                    actionState = CommitmentState.PENDING,
                    source = CommitmentSourcePresentation(sourceLabel = "voice:4/24 10:00"),
                    history = CommitmentHistoryPresentation(),
                    actionButtons = CommitmentDetailActionState(
                        availableActions = emptySet(),
                        editEnabled = false,
                    ),
                    counterpartyDisplayName = "김철수",
                    onRemind = {},
                    onFollowUp = {},
                    onComplete = {},
                    onCancel = {},
                    onEdit = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.commitment_item_type_schedule)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.commitment_subtype_schedule_changed)).assertIsDisplayed()
        composeRule.onAllNodesWithText("PENDING").assertCountEquals(0)
        composeRule.onAllNodesWithText(string(R.string.commitment_action_remind)).assertCountEquals(0)
        composeRule.onAllNodesWithText(string(R.string.commitment_action_edit)).assertCountEquals(0)
    }

    @Test
    fun `commitment create content shows required inputs direction and save`() {
        var title: String? = null
        var quote: String? = null
        var counterpartyRef: String? = null
        var dueHint: String? = null
        var direction: String? = null
        var saveClicks = 0

        composeRule.setContent {
            BecalmTheme {
                var draft by remember {
                    mutableStateOf(
                        ManualCommitmentDraft(
                            title = "",
                            direction = "give",
                            quote = "",
                            counterpartyRef = null,
                            dueAtMillis = null,
                            dueHint = null,
                            dueIsApproximate = false,
                        ),
                    )
                }
                CreateSheetContent(
                    state = CreateUiState(
                        mode = CommitmentCreateMode.MANUAL,
                        draft = draft,
                    ),
                    onTitleChange = {
                        title = it
                        draft = draft.copy(title = it)
                    },
                    onDirectionChange = {
                        direction = it
                        draft = draft.copy(direction = it)
                    },
                    onQuoteChange = {
                        quote = it
                        draft = draft.copy(quote = it)
                    },
                    onCounterpartyRefChange = {
                        counterpartyRef = it
                        draft = draft.copy(counterpartyRef = it)
                    },
                    onDueAtMillisChange = {},
                    onDueHintChange = {
                        dueHint = it
                        draft = draft.copy(dueHint = it)
                    },
                    onApproxChange = {},
                    onSave = { saveClicks += 1 },
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.commitment_manual_field_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("commitment-create-title").performTextInput("새 약속")
        composeRule.onNodeWithText(string(R.string.commitment_manual_field_direction_take)).performClick()
        composeRule.onNodeWithTag("commitment-create-form")
            .performScrollToNode(hasTestTag("commitment-create-quote"))
        composeRule.onNodeWithTag("commitment-create-quote").performTextInput("자료 보내주세요")
        composeRule.onNodeWithTag("commitment-create-form")
            .performScrollToNode(hasTestTag("commitment-create-person-ref"))
        composeRule.onNodeWithTag("commitment-create-person-ref").performTextInput("kim@example.com")
        composeRule.onNodeWithTag("commitment-create-form")
            .performScrollToNode(hasTestTag("commitment-create-due-hint"))
        composeRule.onNodeWithTag("commitment-create-due-hint").performTextInput("금요일 오후")
        composeRule.onNodeWithTag("commitment-create-save").performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals("새 약속", title)
            assertEquals("자료 보내주세요", quote)
            assertEquals("kim@example.com", counterpartyRef)
            assertEquals("금요일 오후", dueHint)
            assertEquals("take", direction)
            assertEquals(1, saveClicks)
        }
    }

    @Test
    fun `commitment edit content routes text field changes`() {
        var title: String? = null
        var dueHint: String? = null
        var counterpartyRef: String? = null

        composeRule.setContent {
            BecalmTheme {
                var state by remember {
                    mutableStateOf(
                        EditUiState(
                            loading = false,
                            readOnly = EditReadOnly(
                                quote = "다음주까지 전달할게요",
                                quoteDisputed = false,
                                sourceLabel = "voice · 4/24 10:00 KST",
                            ),
                            title = "기존 약속",
                            dueAtMillis = null,
                            dueIsApproximate = false,
                            dueHint = "",
                            counterpartyRef = "person-1",
                            direction = "give",
                        ),
                    )
                }
                EditSheetContent(
                    state = state,
                    onTitleChange = {
                        title = it
                        state = state.copy(title = it)
                    },
                    onDueAtMillisChange = {},
                    onDueIsApproximateChange = {},
                    onDueHintChange = {
                        dueHint = it
                        state = state.copy(dueHint = it)
                    },
                    onCounterpartyRefChange = {
                        counterpartyRef = it
                        state = state.copy(counterpartyRef = it)
                    },
                    onDirectionChange = {},
                    onToggleDispute = {},
                    onSave = {},
                    onCancel = {},
                    onConfirmDelete = {},
                )
            }
        }

        composeRule.onNodeWithTag("commitment-edit-title").performTextReplacement("수정된 약속")
        composeRule.onNodeWithTag("commitment-edit-due-hint").performTextInput("내일 오전")
        composeRule.onNodeWithTag("commitment-edit-person-ref").performTextReplacement("lee@example.com")

        composeRule.runOnIdle {
            assertEquals("수정된 약속", title)
            assertEquals("내일 오전", dueHint)
            assertEquals("lee@example.com", counterpartyRef)
        }
    }

    @Test
    fun `commitment edit content shows initial values and delete affordance`() {
        var saved = 0
        var deleted = 0

        composeRule.setContent {
            BecalmTheme {
                EditSheetContent(
                    state = EditUiState(
                        loading = false,
                        readOnly = EditReadOnly(
                            quote = "다음주까지 전달할게요",
                            quoteDisputed = false,
                            sourceLabel = "voice · 4/24 10:00 KST",
                        ),
                        title = "기존 약속",
                        dueAtMillis = null,
                        dueIsApproximate = false,
                        dueHint = "",
                        counterpartyRef = "person-1",
                        direction = "give",
                        fieldErrors = mapOf(CommitmentEditValidator.Field.TITLE to "제목 필요"),
                    ),
                    onTitleChange = {},
                    onDueAtMillisChange = {},
                    onDueIsApproximateChange = {},
                    onDueHintChange = {},
                    onCounterpartyRefChange = {},
                    onDirectionChange = {},
                    onToggleDispute = {},
                    onSave = { saved += 1 },
                    onCancel = {},
                    onConfirmDelete = { deleted += 1 },
                )
            }
        }

        composeRule.onNodeWithText("다음주까지 전달할게요").assertIsDisplayed()
        composeRule.onNodeWithText("voice · 4/24 10:00 KST").assertIsDisplayed()
        composeRule.onNodeWithText("기존 약속").assertIsDisplayed()
        composeRule.onNodeWithTag("commitment-edit-delete").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText(string(R.string.commitment_edit_delete_confirm_body)).assertExists()
        composeRule.onNodeWithTag("commitment-edit-delete-confirm-ok").performClick()
        composeRule.onNodeWithTag("commitment-edit-save").performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals(1, deleted)
            assertEquals(1, saved)
        }
    }

    private fun commitmentEntity(
        itemType: String = CommitmentItemType.ACTION,
        direction: String? = "give",
        scheduleStatus: String? = null,
        decisionStatus: String? = null,
    ): CommitmentEntity = CommitmentEntity(
        id = "commitment-1",
        userId = "user-1",
        itemType = itemType,
        direction = direction,
        scheduleStatus = scheduleStatus,
        decisionStatus = decisionStatus,
        counterpartyRaw = "김철수",
        counterpartyRef = "person-1",
        title = "제안서 보내기",
        description = null,
        quote = "금요일까지 보내겠습니다",
        sourceEventTitle = "통화",
        sourceEventOccurredAt = Instant.parse("2026-04-24T01:00:00Z"),
        dueAt = Instant.parse("2026-04-25T01:00:00Z"),
        dueHint = null,
        dueIsApproximate = false,
        actionState = "pending",
        sourceType = SourceType.VOICE,
        sourceRef = "raw-1",
        confidence = 0.9,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
        createdAt = Instant.parse("2026-04-24T01:00:00Z"),
        updatedAt = Instant.parse("2026-04-24T01:00:00Z"),
        lastEditedBy = "user-1",
        lastEditedAt = Instant.parse("2026-04-24T01:30:00Z"),
        quoteDisputed = false,
        quoteDisputedAt = null,
        deletedAt = null,
        supersedesCommitmentId = "old-1",
    )

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}

package com.becalm.android.integration.local.ui.commitments

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.CommitmentItemType
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
        composeRule.onNodeWithText("GIVE").assertIsDisplayed()
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
    fun `commitment create content shows required inputs direction and save`() {
        var title: String? = null
        var direction: String? = null
        var saveClicks = 0

        composeRule.setContent {
            BecalmTheme {
                CreateSheetContent(
                    state = CreateUiState(
                        mode = CommitmentCreateMode.MANUAL,
                        draft = ManualCommitmentDraft(
                            title = "",
                            direction = "give",
                            quote = "",
                            personRef = null,
                            dueAtMillis = null,
                            dueHint = null,
                            dueIsApproximate = false,
                        ),
                    ),
                    onTitleChange = { title = it },
                    onDirectionChange = { direction = it },
                    onQuoteChange = {},
                    onPersonRefChange = {},
                    onDueAtMillisChange = {},
                    onDueHintChange = {},
                    onApproxChange = {},
                    onSave = { saveClicks += 1 },
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.commitment_manual_field_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("commitment-create-title").performTextInput("새 약속")
        composeRule.onNodeWithText(string(R.string.commitment_manual_field_direction_take)).performClick()
        composeRule.onNodeWithTag("commitment-create-save").performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals("새 약속", title)
            assertEquals("take", direction)
            assertEquals(1, saveClicks)
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
                        personRef = "person-1",
                        direction = "give",
                        fieldErrors = mapOf(CommitmentEditValidator.Field.TITLE to "제목 필요"),
                    ),
                    onTitleChange = {},
                    onDueAtMillisChange = {},
                    onDueIsApproximateChange = {},
                    onDueHintChange = {},
                    onPersonRefChange = {},
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

    private fun commitmentEntity(): CommitmentEntity = CommitmentEntity(
        id = "commitment-1",
        userId = "user-1",
        itemType = CommitmentItemType.ACTION,
        direction = "give",
        scheduleStatus = null,
        decisionStatus = null,
        counterpartyRaw = "김철수",
        personRef = "person-1",
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

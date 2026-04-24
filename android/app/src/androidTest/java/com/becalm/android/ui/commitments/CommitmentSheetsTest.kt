package com.becalm.android.ui.commitments

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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.commitment.CommitmentEditValidator
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.domain.commitment.ManualCommitmentDraft
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommitmentSheetsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun commitment_detail_content_shows_source_history_and_action_chips() {
        var remindClicks = 0
        var editClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                DetailSheetContent(
                    entity = commitmentEntity(),
                    quote = "Send the deck by Friday",
                    actionState = CommitmentState.PENDING,
                    source = CommitmentSourcePresentation(sourceLabel = "voice:4/24 10:00"),
                    history = CommitmentHistoryPresentation(
                        lastEditedAt = Instant.parse("2026-04-24T01:30:00Z"),
                        lastEditedLabel = "Last edited: 4/24 10:30 (me)",
                        disputedLabel = "Disputed",
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
                    counterpartyDisplayName = "Alice Kim",
                    onRemind = { remindClicks += 1 },
                    onFollowUp = {},
                    onComplete = {},
                    onCancel = {},
                    onEdit = { editClicks += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText("Send proposal").assertIsDisplayed()
        composeTestRule.onNodeWithText("GIVE").assertIsDisplayed()
        composeTestRule.onNodeWithText("PENDING").assertIsDisplayed()
        composeTestRule.onNodeWithText("voice:4/24 10:00").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Last edited: 4/24 10:30 (me)").assertCountEquals(1)
        composeTestRule.onAllNodesWithText(string(R.string.commitment_detail_superseded_link))
            .assertCountEquals(1)
        composeTestRule.onNodeWithTag("commitment-detail-remind")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithTag("commitment-detail-edit")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.runOnIdle {
            assertEquals(1, remindClicks)
            assertEquals(1, editClicks)
        }
    }

    @Test
    fun commitment_create_content_shows_required_inputs_direction_and_save() {
        var title: String? = null
        var direction: String? = null
        var saveClicks = 0

        composeTestRule.setContent {
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

        composeTestRule.onNodeWithText(string(R.string.commitment_manual_field_title)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("commitment-create-title").performTextInput("New commitment")
        composeTestRule.onNodeWithText(string(R.string.commitment_manual_field_direction_take)).performClick()
        composeTestRule.onNodeWithTag("commitment-create-save")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.runOnIdle {
            assertEquals("New commitment", title)
            assertEquals("take", direction)
            assertEquals(1, saveClicks)
        }
    }

    @Test
    fun commitment_edit_content_shows_initial_values_and_delete_affordance() {
        var saved = 0
        var deleted = 0

        composeTestRule.setContent {
            BecalmTheme {
                EditSheetContent(
                    state = EditUiState(
                        loading = false,
                        readOnly = EditReadOnly(
                            quote = "I will send it next week",
                            quoteDisputed = false,
                            sourceLabel = "voice · 4/24 10:00 KST",
                        ),
                        title = "Existing commitment",
                        dueAtMillis = null,
                        dueIsApproximate = false,
                        dueHint = "",
                        personRef = "person-1",
                        direction = "give",
                        fieldErrors = mapOf(CommitmentEditValidator.Field.TITLE to "Title required"),
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

        composeTestRule.onNodeWithText("I will send it next week").assertIsDisplayed()
        composeTestRule.onNodeWithText("voice · 4/24 10:00 KST").assertIsDisplayed()
        composeTestRule.onNodeWithText("Existing commitment").assertIsDisplayed()
        composeTestRule.onNodeWithTag("commitment-edit-delete")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithText(string(R.string.commitment_edit_delete_confirm_body))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("commitment-edit-delete-confirm-ok").performClick()
        composeTestRule.onNodeWithTag("commitment-edit-save")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.runOnIdle {
            assertEquals(1, deleted)
            assertEquals(1, saved)
        }
    }

    @Test
    fun commitment_create_sheet_consumes_dismiss_event() {
        val dismissEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var dismissCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                CommitmentCreateSheet(
                    supersedeOf = null,
                    onDismiss = { dismissCount += 1 },
                    stateOverride = CreateUiState(
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
                    dismissEventsOverride = dismissEvents,
                    onTitleChange = {},
                    onDirectionChange = {},
                    onQuoteChange = {},
                    onPersonRefChange = {},
                    onDueAtMillisChange = {},
                    onDueHintChange = {},
                    onApproxChange = {},
                    onSave = {},
                    onCancel = {},
                )
            }
        }

        composeTestRule.runOnIdle {
            dismissEvents.tryEmit(Unit)
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertEquals(1, dismissCount)
        }
    }

    @Test
    fun commitment_detail_sheet_consumes_open_edit_effect() {
        val effects = MutableSharedFlow<CommitmentDetailEffect>(extraBufferCapacity = 1)
        var dismissCount = 0
        var editCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                CommitmentDetailSheet(
                    commitmentId = "commitment-1",
                    onDismiss = { dismissCount += 1 },
                    onEdit = { editCount += 1 },
                    stateOverride = DetailUiState(
                        entity = commitmentEntity(),
                        quote = "Send the deck by Friday",
                        counterpartyDisplayName = "Alice Kim",
                        actionState = CommitmentState.PENDING,
                        source = CommitmentSourcePresentation(sourceLabel = "voice:4/24 10:00"),
                        actionButtons = CommitmentDetailActionState(),
                        history = CommitmentHistoryPresentation(),
                        loading = false,
                    ),
                    effectsOverride = effects,
                    onRemind = {},
                    onFollowUp = {},
                    onComplete = {},
                    onCancel = {},
                )
            }
        }

        composeTestRule.runOnIdle {
            effects.tryEmit(CommitmentDetailEffect.OpenEdit("commitment-1"))
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertEquals(1, dismissCount)
            assertEquals(1, editCount)
        }
    }

    @Test
    fun commitment_detail_sheet_routes_action_callbacks() {
        var remindCount = 0
        var followUpCount = 0
        var completeCount = 0
        var cancelCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                CommitmentDetailSheet(
                    commitmentId = "commitment-1",
                    onDismiss = {},
                    onEdit = {},
                    stateOverride = DetailUiState(
                        entity = commitmentEntity(),
                        quote = "Send the deck by Friday",
                        counterpartyDisplayName = "Alice Kim",
                        actionState = CommitmentState.PENDING,
                        source = CommitmentSourcePresentation(sourceLabel = "voice:4/24 10:00"),
                        actionButtons = CommitmentDetailActionState(
                            availableActions = setOf(
                                CommitmentSheetAction.REMIND,
                                CommitmentSheetAction.FOLLOW_UP,
                                CommitmentSheetAction.COMPLETE,
                            ),
                            editEnabled = true,
                        ),
                        history = CommitmentHistoryPresentation(),
                        loading = false,
                    ),
                    effectsOverride = MutableSharedFlow(extraBufferCapacity = 1),
                    onRemind = { remindCount += 1 },
                    onFollowUp = { followUpCount += 1 },
                    onComplete = { completeCount += 1 },
                    onCancel = { cancelCount += 1 },
                )
            }
        }

        composeTestRule.onNodeWithTag("commitment-detail-remind")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithText(string(R.string.commitment_action_follow_up)).performClick()
        composeTestRule.onNodeWithText(string(R.string.commitment_action_complete)).performClick()
        composeTestRule.onNodeWithText(string(R.string.commitment_action_cancel)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, remindCount)
            assertEquals(1, followUpCount)
            assertEquals(1, completeCount)
            assertEquals(1, cancelCount)
        }
    }

    @Test
    fun commitment_create_sheet_routes_save_and_cancel_callbacks() {
        var saveCount = 0
        var cancelCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                CommitmentCreateSheet(
                    supersedeOf = null,
                    onDismiss = {},
                    stateOverride = CreateUiState(
                        mode = CommitmentCreateMode.MANUAL,
                        draft = ManualCommitmentDraft(
                            title = "New commitment",
                            direction = "give",
                            quote = "",
                            personRef = null,
                            dueAtMillis = null,
                            dueHint = null,
                            dueIsApproximate = false,
                        ),
                    ),
                    dismissEventsOverride = MutableSharedFlow(extraBufferCapacity = 1),
                    onTitleChange = {},
                    onDirectionChange = {},
                    onQuoteChange = {},
                    onPersonRefChange = {},
                    onDueAtMillisChange = {},
                    onDueHintChange = {},
                    onApproxChange = {},
                    onSave = { saveCount += 1 },
                    onCancel = { cancelCount += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.action_cancel)).performClick()
        composeTestRule.onNodeWithTag("commitment-create-save")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.runOnIdle {
            assertEquals(1, saveCount)
            assertEquals(1, cancelCount)
        }
    }

    @Test
    fun commitment_edit_sheet_consumes_dismiss_event_and_not_found() {
        val dismissEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var dismissCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                CommitmentEditSheet(
                    commitmentId = "commitment-1",
                    onDismiss = { dismissCount += 1 },
                    stateOverride = EditUiState(
                        loading = false,
                        notFound = true,
                    ),
                    dismissEventsOverride = dismissEvents,
                    onTitleChange = {},
                    onDueAtMillisChange = {},
                    onDueIsApproximateChange = {},
                    onDueHintChange = {},
                    onPersonRefChange = {},
                    onDirectionChange = {},
                    onToggleDispute = {},
                    onSave = {},
                    onCancel = {},
                    onConfirmDelete = {},
                )
            }
        }

        composeTestRule.runOnIdle {
            dismissEvents.tryEmit(Unit)
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertEquals(2, dismissCount)
        }
    }

    @Test
    fun commitment_edit_sheet_routes_cancel_and_delete_confirm_callbacks() {
        var cancelCount = 0
        var deleteCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                CommitmentEditSheet(
                    commitmentId = "commitment-1",
                    onDismiss = {},
                    stateOverride = EditUiState(
                        loading = false,
                        readOnly = EditReadOnly(
                            quote = "I will send it next week",
                            quoteDisputed = false,
                            sourceLabel = "voice · 4/24 10:00 KST",
                        ),
                        title = "Existing commitment",
                        dueAtMillis = null,
                        dueIsApproximate = false,
                        dueHint = "",
                        personRef = "person-1",
                        direction = "give",
                    ),
                    dismissEventsOverride = MutableSharedFlow(extraBufferCapacity = 1),
                    onTitleChange = {},
                    onDueAtMillisChange = {},
                    onDueIsApproximateChange = {},
                    onDueHintChange = {},
                    onPersonRefChange = {},
                    onDirectionChange = {},
                    onToggleDispute = {},
                    onSave = {},
                    onCancel = { cancelCount += 1 },
                    onConfirmDelete = { deleteCount += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.action_cancel)).performClick()
        composeTestRule.onNodeWithTag("commitment-edit-delete")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithTag("commitment-edit-delete-confirm-ok").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, cancelCount)
            assertEquals(1, deleteCount)
        }
    }

    private fun commitmentEntity(): CommitmentEntity = CommitmentEntity(
        id = "commitment-1",
        userId = "user-1",
        itemType = CommitmentItemType.ACTION,
        direction = "give",
        scheduleStatus = null,
        decisionStatus = null,
        counterpartyRaw = "Alice Kim",
        personRef = "person-1",
        title = "Send proposal",
        description = null,
        quote = "Send the deck by Friday",
        sourceEventTitle = "Call",
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

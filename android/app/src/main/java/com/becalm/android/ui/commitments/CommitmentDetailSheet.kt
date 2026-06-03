package com.becalm.android.ui.commitments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentDecisionStatus
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentScheduleStatus
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmSheetSkeleton
import com.becalm.android.ui.components.CommitmentWire
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.components.EvidenceCard
import com.becalm.android.ui.components.EventSourceBadge
import com.becalm.android.ui.components.SheetCloseRow
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.navigation.BecalmRoute
import kotlinx.coroutines.flow.Flow

// ─── CommitmentDetailSheet ────────────────────────────────────────────────────

/**
 * Bottom-sheet host for CMT-003 + EDIT-008 + MAN-004. Opened via
 * [BecalmRoute.CommitmentDetail]; renders the full quote, source context,
 * counterparty, due info, action controls, last-edited footer, and supersede backlink.
 *
 * VM wiring note: the sheet resolves two Hilt view-models via `hiltViewModel()`.
 * [detailViewModel] owns the reactive observe-by-id flow; [managementViewModel] is
 * reused only for its existing [CommitmentManagementViewModel.onRemind] /
 * `onFollowUp` / `onComplete` / `onCancel` handlers. When the sheet is opened via
 * a reminder deep link with no active Management screen in the back stack, a
 * detached second [CommitmentManagementViewModel] instance is created. That is
 * acceptable because both instances talk to the same singleton
 * [com.becalm.android.data.repository.CommitmentRepository]; the transient Room
 * list it collects is immediately garbage-collected when the sheet dismisses.
 * This is a deliberate trade-off per plan §5.2.
 *
 * Clicking the [편집] button invokes [onEdit], which the nav host wires up to
 * [BecalmRoute.CommitmentEdit] (wave 4 C8). The sheet is dismissed first so the
 * back-stack pops cleanly on the edit sheet's own dismiss.
 *
 * @param commitmentId UUID of the commitment to display. Threaded via
 *   [SavedStateHandle] into [CommitmentDetailViewModel]; the parameter exists on
 *   the composable signature so callers — including the nav host — pass the id
 *   explicitly even though the VM also reads it from SavedState.
 * @param onDismiss Invoked when the sheet dismisses (swipe-down or action click).
 * @param onEdit Invoked when the user taps `[편집]`. The nav host navigates to
 *   [BecalmRoute.CommitmentEdit]; tests pass a no-op.
 * @param detailViewModel VM that owns the reactive entity flow.
 * @param managementViewModel VM that owns the state-machine handlers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun CommitmentDetailSheet(
    commitmentId: String,
    onDismiss: () -> Unit,
    onEdit: () -> Unit = {},
    detailViewModel: CommitmentDetailViewModel? = null,
    managementViewModel: CommitmentManagementViewModel? = null,
    stateOverride: DetailUiState? = null,
    effectsOverride: Flow<CommitmentDetailEffect>? = null,
    onReminderToggle: ((Boolean) -> Unit)? = null,
    onFollowUp: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onSpeakerAliasChange: ((String, String) -> Unit)? = null,
) {
    val resolvedDetailViewModel = if (stateOverride == null || effectsOverride == null) {
        detailViewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<CommitmentDetailViewModel>()
    } else {
        detailViewModel
    }
    val resolvedManagementViewModel = if (
        onReminderToggle == null || onFollowUp == null || onComplete == null || onCancel == null
    ) {
        managementViewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<CommitmentManagementViewModel>()
    } else {
        managementViewModel
    }
    val state = if (stateOverride != null) {
        stateOverride
    } else {
        val collectedState by requireNotNull(resolvedDetailViewModel).uiState.collectAsStateWithLifecycle()
        collectedState
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entity = state.entity

    LaunchedEffect(effectsOverride, resolvedDetailViewModel) {
        (effectsOverride ?: requireNotNull(resolvedDetailViewModel).effects).collect { effect ->
            when (effect) {
                is CommitmentDetailEffect.OpenEdit -> {
                    onDismiss()
                    onEdit()
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        SheetCloseRow(onClose = onDismiss)
        when {
            state.loading -> {
                BecalmSheetSkeleton(modifier = Modifier.heightIn(min = 160.dp))
            }
            state.error != null || entity == null -> {
                val errorLabel = state.error?.let { uiMessageStringResource(it) }
                    ?: stringResource(R.string.commitment_detail_empty_error)
                ErrorState(
                    title = stringResource(R.string.commitment_detail_empty_error),
                    message = errorLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp),
                )
            }
            else -> {
                DetailSheetContent(
                    entity = entity,
                    quote = state.quote,
                    actionState = state.actionState,
                    source = state.source,
                    history = state.history,
                    meetingTranscript = state.meetingTranscript,
                    actionButtons = state.actionButtons,
                    counterpartyDisplayName = state.counterpartyDisplayName,
                    onReminderToggle = onReminderToggle ?: { enabled ->
                        requireNotNull(resolvedManagementViewModel).onToggleReminder(commitmentId, enabled)
                    },
                    onFollowUp = onFollowUp ?: {
                        requireNotNull(resolvedManagementViewModel).onFollowUp(commitmentId)
                        onDismiss()
                    },
                    onComplete = onComplete ?: {
                        requireNotNull(resolvedManagementViewModel).onComplete(commitmentId)
                        onDismiss()
                    },
                    onCancel = onCancel ?: {
                        requireNotNull(resolvedManagementViewModel).onCancel(commitmentId)
                        onDismiss()
                    },
                    onEdit = {
                        requireNotNull(resolvedDetailViewModel).onEditClick()
                    },
                    onSpeakerAliasChange = onSpeakerAliasChange ?: { speakerId, displayName ->
                        if (resolvedDetailViewModel != null) {
                            resolvedDetailViewModel.onSpeakerAliasChange(speakerId, displayName)
                        }
                    },
                )
            }
        }
    }
}

// ─── Content ──────────────────────────────────────────────────────────────────

@Composable
internal fun DetailSheetContent(
    entity: CommitmentEntity,
    quote: String,
    actionState: CommitmentState,
    source: CommitmentSourcePresentation,
    history: CommitmentHistoryPresentation,
    meetingTranscript: MeetingTranscriptPresentation? = null,
    actionButtons: CommitmentDetailActionState,
    counterpartyDisplayName: String?,
    onReminderToggle: (Boolean) -> Unit,
    onFollowUp: () -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onEdit: () -> Unit,
    onSpeakerAliasChange: (String, String) -> Unit = { _, _ -> },
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(scrollState)
            .testTag("commitment-detail-content"),
    ) {
        // 1. Title
        Text(
            text = entity.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 2. Direction + action_state chip row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SimpleChip(text = itemTypeLabel(entity))
            subtypeLabel(entity)?.let { SimpleChip(text = it) }
            if (entity.itemType == CommitmentItemType.ACTION) {
                SimpleChip(text = stringForActionState(actionState))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Source section — keep source title close to quote so users can verify provenance.
        val hasSourceEvidence = source.sourceLabel != null ||
            (!source.isManual && !source.sourceType.isNullOrBlank())
        if (hasSourceEvidence) {
            EvidenceCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(text = stringResource(R.string.commitment_detail_source_label))
                    if (!source.isManual && !source.sourceType.isNullOrBlank()) {
                        EventSourceBadge(sourceType = source.sourceType)
                    }
                    source.sourceLabel?.let { label ->
                        Text(
                            text = commitmentStringResource(label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        // 4. Quote section (read-only; disputed badge if applicable)
        EvidenceCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(text = stringResource(R.string.commitment_detail_quote_label))
                Text(
                    text = quote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (history.disputeRaisedAt != null) {
                    Text(
                        text = stringResource(
                            R.string.commitment_detail_disputed_label_fmt,
                            CommitmentDetailFormatter.formatShortKst(history.disputeRaisedAt),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 5. Counterparty line
        if (counterpartyDisplayName != null) {
            SectionLabel(text = stringResource(R.string.commitment_detail_counterparty_label))
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = counterpartyDisplayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // 6. Due line
        val dueAt = entity.dueAt?.takeUnless { entity.dueIsApproximate }
        if (dueAt != null) {
            SectionLabel(text = stringResource(R.string.commitment_detail_due_label))
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = CommitmentDetailFormatter.formatShortKst(dueAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (meetingTranscript != null) {
            SectionLabel(text = stringResource(R.string.commitment_detail_meeting_transcript_label))
            Spacer(modifier = Modifier.height(4.dp))
            if (meetingTranscript.speakerIds.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    meetingTranscript.speakerIds.forEach { speakerId ->
                        OutlinedTextField(
                            value = meetingTranscript.aliases[speakerId] ?: speakerId,
                            onValueChange = { value -> onSpeakerAliasChange(speakerId, value) },
                            label = {
                                Text(
                                    text = stringResource(
                                        R.string.commitment_detail_speaker_alias_label,
                                        speakerId,
                                    ),
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("meeting-speaker-alias-$speakerId"),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = meetingTranscript.bodyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .testTag("commitment-meeting-transcript"),
            )
            if (meetingTranscript.truncated) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.commitment_detail_meeting_transcript_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 7. Action button strip
        if (actionButtons.availableActions.isNotEmpty() || actionButtons.editEnabled || actionButtons.reminderToggleVisible) {
            ActionButtonRow(
                actionState = actionState,
                isDeleted = entity.deletedAt != null,
                actionButtons = actionButtons,
                onReminderToggle = onReminderToggle,
                onFollowUp = onFollowUp,
                onComplete = onComplete,
                onCancel = onCancel,
                onEdit = onEdit,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 8. Footer — last-edited banner + supersede backlink
        if (history.lastEditedAt != null) {
            Text(
                text = stringResource(
                    R.string.commitment_detail_last_edited_fmt,
                    CommitmentDetailFormatter.formatShortKst(history.lastEditedAt),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (history.showSupersedeLink) {
            // Disabled per EDIT-008 MVP scope: the backlink is visible but not
            // actionable. Strikethrough makes the "disabled" state visually explicit.
            TextButton(
                onClick = {},
                enabled = false,
            ) {
                Text(
                    text = stringResource(R.string.commitment_detail_superseded_link),
                    style = MaterialTheme.typography.labelMedium.copy(
                        textDecoration = TextDecoration.LineThrough,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

// ─── Action button strip ──────────────────────────────────────────────────────

@Composable
private fun ActionButtonRow(
    actionState: CommitmentState,
    isDeleted: Boolean,
    actionButtons: CommitmentDetailActionState,
    onReminderToggle: (Boolean) -> Unit,
    onFollowUp: () -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onEdit: () -> Unit,
) {
    // Enable gates per plan §task description "CMT-003 matrix":
    //   [알림]   — due_at이 확정된 action/schedule에서 on/off 토글
    //   [팔로업]  — PENDING / REMINDED
    //   [완료]   — PENDING / REMINDED / FOLLOWED_UP / OVERDUE
    //   [취소]   — PENDING / REMINDED / FOLLOWED_UP / OVERDUE
    //   [편집]   — not CANCELLED, not soft-deleted (EDIT-001)
    val actions = buildList {
        if (CommitmentSheetAction.REMIND in actionButtons.availableActions) {
            add(
                CommitmentDetailActionSpec(
                    kind = CommitmentDetailActionKind.REMIND,
                    label = stringResource(R.string.commitment_action_remind),
                    testTag = "commitment-detail-remind",
                    onClick = { onReminderToggle(true) },
                ),
            )
        }
        if (actionButtons.reminderToggleVisible) {
            add(
                CommitmentDetailActionSpec(
                    kind = CommitmentDetailActionKind.REMINDER_TOGGLE,
                    label = stringResource(
                        if (actionButtons.reminderEnabled) {
                            R.string.commitment_action_reminder_on
                        } else {
                            R.string.commitment_action_reminder_off
                        },
                    ),
                    testTag = "commitment-detail-reminder-toggle",
                    onClick = { onReminderToggle(!actionButtons.reminderEnabled) },
                ),
            )
        }
        if (CommitmentSheetAction.FOLLOW_UP in actionButtons.availableActions) {
            add(
                CommitmentDetailActionSpec(
                    kind = CommitmentDetailActionKind.FOLLOW_UP,
                    label = stringResource(R.string.commitment_action_follow_up),
                    testTag = "commitment-detail-follow-up",
                    onClick = onFollowUp,
                ),
            )
        }
        if (CommitmentSheetAction.COMPLETE in actionButtons.availableActions) {
            add(
                CommitmentDetailActionSpec(
                    kind = CommitmentDetailActionKind.COMPLETE,
                    label = stringResource(R.string.commitment_action_complete),
                    testTag = "commitment-detail-complete",
                    onClick = onComplete,
                ),
            )
        }
        if (CommitmentSheetAction.CANCEL in actionButtons.availableActions) {
            add(
                CommitmentDetailActionSpec(
                    kind = CommitmentDetailActionKind.CANCEL,
                    label = stringResource(R.string.commitment_action_cancel),
                    testTag = "commitment-detail-cancel",
                    onClick = onCancel,
                ),
            )
        }
    }
    val editEnabled = actionButtons.editEnabled && !isDeleted
    val primary = actions.firstOrNull { it.kind == CommitmentDetailActionKind.COMPLETE }
        ?: actions.firstOrNull { it.kind == CommitmentDetailActionKind.FOLLOW_UP }
        ?: actions.firstOrNull { it.kind == CommitmentDetailActionKind.REMIND }
        ?: if (editEnabled) {
            CommitmentDetailActionSpec(
                kind = CommitmentDetailActionKind.EDIT,
                label = stringResource(R.string.commitment_action_edit),
                testTag = "commitment-detail-edit",
                onClick = onEdit,
            )
        } else {
            null
        }
    val secondary = actions.filter { action ->
        action != primary &&
            action.kind in setOf(CommitmentDetailActionKind.REMIND, CommitmentDetailActionKind.REMINDER_TOGGLE, CommitmentDetailActionKind.FOLLOW_UP)
    }
    val overflow = buildList {
        if (editEnabled && primary?.kind != CommitmentDetailActionKind.EDIT) {
            add(
                CommitmentDetailActionSpec(
                    kind = CommitmentDetailActionKind.EDIT,
                    label = stringResource(R.string.commitment_action_edit),
                    testTag = "commitment-detail-edit",
                    onClick = onEdit,
                ),
            )
        }
        actions
            .filter { action -> action != primary && action !in secondary }
            .forEach(::add)
    }
    var overflowExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        primary?.let { action ->
            BecalmButton(
                text = action.label,
                onClick = action.onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("commitment-detail-primary-action"),
            )
        }
        if (secondary.isNotEmpty() || overflow.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                secondary.forEach { action ->
                    BecalmButton(
                        text = action.label,
                        onClick = action.onClick,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(action.testTag),
                        variant = BecalmButtonVariant.Secondary,
                    )
                }
                if (overflow.isNotEmpty()) {
                    Box {
                        IconButton(
                            onClick = { overflowExpanded = true },
                            modifier = Modifier.testTag("commitment-detail-actions-more"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.commitment_action_more),
                            )
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                        ) {
                            overflow.forEach { action ->
                                DropdownMenuItem(
                                    text = { Text(text = action.label) },
                                    onClick = {
                                        overflowExpanded = false
                                        action.onClick()
                                    },
                                    modifier = Modifier.testTag(action.testTag),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class CommitmentDetailActionKind {
    REMIND,
    REMINDER_TOGGLE,
    FOLLOW_UP,
    COMPLETE,
    CANCEL,
    EDIT,
}

private data class CommitmentDetailActionSpec(
    val kind: CommitmentDetailActionKind,
    val label: String,
    val testTag: String,
    val onClick: () -> Unit,
)

// ─── Helpers ──────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SimpleChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun stringForActionState(state: CommitmentState): String = when (state) {
    CommitmentState.PENDING -> stringResource(R.string.commitment_action_state_pending)
    CommitmentState.REMINDED -> stringResource(R.string.commitment_action_state_reminded)
    CommitmentState.FOLLOWED_UP -> stringResource(R.string.commitment_action_state_followed_up)
    CommitmentState.COMPLETED -> stringResource(R.string.commitment_action_state_completed)
    CommitmentState.OVERDUE -> stringResource(R.string.commitment_action_state_overdue)
    CommitmentState.CANCELLED -> stringResource(R.string.commitment_action_state_cancelled)
}

@Composable
private fun itemTypeLabel(entity: CommitmentEntity): String = when (entity.itemType) {
    CommitmentItemType.ACTION -> stringResource(R.string.commitment_item_type_action)
    CommitmentItemType.SCHEDULE -> stringResource(R.string.commitment_item_type_schedule)
    CommitmentItemType.DECISION -> stringResource(R.string.commitment_item_type_decision)
    else -> stringResource(R.string.commitment_item_type_action)
}

@Composable
private fun subtypeLabel(entity: CommitmentEntity): String? = when (entity.itemType) {
    CommitmentItemType.ACTION -> when (entity.direction?.lowercase()) {
        CommitmentWire.DIRECTION_GIVE -> stringResource(R.string.commitments_filter_give)
        CommitmentWire.DIRECTION_TAKE -> stringResource(R.string.commitments_filter_take)
        else -> null
    }
    CommitmentItemType.SCHEDULE -> when (entity.scheduleStatus) {
        CommitmentScheduleStatus.CONFIRMED -> stringResource(R.string.commitment_subtype_schedule_confirmed)
        CommitmentScheduleStatus.TENTATIVE -> stringResource(R.string.commitment_subtype_schedule_tentative)
        CommitmentScheduleStatus.CHANGED -> stringResource(R.string.commitment_subtype_schedule_changed)
        CommitmentScheduleStatus.POSTPONED -> stringResource(R.string.commitment_subtype_schedule_postponed)
        CommitmentScheduleStatus.CANCELLED -> stringResource(R.string.commitment_subtype_schedule_cancelled)
        CommitmentScheduleStatus.FOLLOW_UP -> stringResource(R.string.commitment_subtype_schedule_follow_up)
        else -> null
    }
    CommitmentItemType.DECISION -> when (entity.decisionStatus) {
        CommitmentDecisionStatus.APPROVED -> stringResource(R.string.commitment_subtype_decision_approved)
        CommitmentDecisionStatus.REJECTED -> stringResource(R.string.commitment_subtype_decision_rejected)
        CommitmentDecisionStatus.CHOSEN -> stringResource(R.string.commitment_subtype_decision_chosen)
        CommitmentDecisionStatus.DEFERRED -> stringResource(R.string.commitment_subtype_decision_deferred)
        CommitmentDecisionStatus.ONGOING -> stringResource(R.string.commitment_subtype_decision_ongoing)
        else -> null
    }
    else -> null
}

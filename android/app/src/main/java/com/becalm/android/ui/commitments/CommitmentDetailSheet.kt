package com.becalm.android.ui.commitments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.becalm.android.ui.components.BecalmSheetSkeleton
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.navigation.BecalmRoute
import kotlinx.coroutines.flow.Flow

// ─── CommitmentDetailSheet ────────────────────────────────────────────────────

/**
 * Bottom-sheet host for CMT-003 + EDIT-008 + MAN-004. Opened via
 * [BecalmRoute.CommitmentDetail]; renders the full quote, source context,
 * counterparty, due info, 5 action buttons, last-edited footer, and supersede
 * backlink.
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
    onRemind: (() -> Unit)? = null,
    onFollowUp: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    val resolvedDetailViewModel = if (stateOverride == null || effectsOverride == null) {
        detailViewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<CommitmentDetailViewModel>()
    } else {
        detailViewModel
    }
    val resolvedManagementViewModel = if (
        onRemind == null || onFollowUp == null || onComplete == null || onCancel == null
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
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
        when {
            state.loading -> {
                BecalmSheetSkeleton(modifier = Modifier.heightIn(min = 160.dp))
            }
            state.error != null || entity == null -> {
                val errorLabel = if (state.error == CommitmentDetailViewModel.EMPTY_ERROR_KEY) {
                    stringResource(R.string.commitment_detail_empty_error)
                } else {
                    state.error ?: stringResource(R.string.commitment_detail_empty_error)
                }
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
                    actionButtons = state.actionButtons,
                    counterpartyDisplayName = state.counterpartyDisplayName,
                    onRemind = onRemind ?: {
                        requireNotNull(resolvedManagementViewModel).onRemind(commitmentId)
                        onDismiss()
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
    actionButtons: CommitmentDetailActionState,
    counterpartyDisplayName: String?,
    onRemind: () -> Unit,
    onFollowUp: () -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onEdit: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(scrollState),
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

        // 3. Quote section (read-only; disputed badge if applicable)
        SectionLabel(text = stringResource(R.string.commitment_detail_quote_label))
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = quote,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (history.disputeRaisedAt != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = history.disputedLabel.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Source section — manual vs LLM-extracted diverge per MAN-004
        Text(
            text = source.sourceLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

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

        // 7. Action button strip
        if (actionButtons.availableActions.isNotEmpty() || actionButtons.editEnabled) {
            ActionButtonRow(
                actionState = actionState,
                isDeleted = entity.deletedAt != null,
                actionButtons = actionButtons,
                onRemind = onRemind,
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
                text = history.lastEditedLabel.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
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
    onRemind: () -> Unit,
    onFollowUp: () -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onEdit: () -> Unit,
) {
    // Enable gates per plan §task description "CMT-003 matrix":
    //   [리마인드] — PENDING only
    //   [팔로업]  — PENDING / REMINDED
    //   [완료]   — PENDING / REMINDED / FOLLOWED_UP / OVERDUE
    //   [취소]   — PENDING / REMINDED / FOLLOWED_UP / OVERDUE
    //   [편집]   — not CANCELLED, not soft-deleted (EDIT-001)
    val remindEnabled = CommitmentSheetAction.REMIND in actionButtons.availableActions
    val followUpEnabled = CommitmentSheetAction.FOLLOW_UP in actionButtons.availableActions
    val completeEnabled = CommitmentSheetAction.COMPLETE in actionButtons.availableActions
    val cancelEnabled = completeEnabled
    val editEnabled = actionButtons.editEnabled && !isDeleted

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedButton(
            onClick = onRemind,
            enabled = remindEnabled,
            modifier = Modifier
                .widthIn(min = 0.dp)
                .testTag("commitment-detail-remind"),
        ) { Text(text = stringResource(R.string.commitment_action_remind)) }
        OutlinedButton(
            onClick = onFollowUp,
            enabled = followUpEnabled,
            modifier = Modifier.widthIn(min = 0.dp),
        ) { Text(text = stringResource(R.string.commitment_action_follow_up)) }
        OutlinedButton(
            onClick = onComplete,
            enabled = completeEnabled,
            modifier = Modifier.widthIn(min = 0.dp),
        ) { Text(text = stringResource(R.string.commitment_action_complete)) }
        OutlinedButton(
            onClick = onCancel,
            enabled = cancelEnabled,
            modifier = Modifier.widthIn(min = 0.dp),
        ) { Text(text = stringResource(R.string.commitment_action_cancel)) }
        OutlinedButton(
            onClick = onEdit,
            enabled = editEnabled,
            modifier = Modifier
                .widthIn(min = 0.dp)
                .testTag("commitment-detail-edit"),
        ) { Text(text = stringResource(R.string.commitment_action_edit)) }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SimpleChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun stringForActionState(state: CommitmentState): String = when (state) {
    CommitmentState.PENDING -> "PENDING"
    CommitmentState.REMINDED -> "REMINDED"
    CommitmentState.FOLLOWED_UP -> "FOLLOWED_UP"
    CommitmentState.COMPLETED -> "COMPLETED"
    CommitmentState.OVERDUE -> "OVERDUE"
    CommitmentState.CANCELLED -> "CANCELLED"
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
        "give" -> stringResource(R.string.commitments_filter_give)
        "take" -> stringResource(R.string.commitments_filter_take)
        else -> null
    }
    CommitmentItemType.SCHEDULE -> when (entity.scheduleStatus) {
        CommitmentScheduleStatus.CONFIRMED -> stringResource(R.string.commitment_subtype_schedule_confirmed)
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

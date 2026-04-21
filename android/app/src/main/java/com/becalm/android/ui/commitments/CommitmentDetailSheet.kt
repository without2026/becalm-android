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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.navigation.BecalmRoute
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

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
    detailViewModel: CommitmentDetailViewModel = hiltViewModel(),
    managementViewModel: CommitmentManagementViewModel = hiltViewModel(),
) {
    val state by detailViewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        when {
            state.loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.error != null || state.entity == null -> {
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
                    entity = state.entity!!,
                    actionState = state.actionState,
                    counterpartyDisplayName = state.counterpartyDisplayName,
                    onRemind = {
                        managementViewModel.onRemind(commitmentId)
                        onDismiss()
                    },
                    onFollowUp = {
                        managementViewModel.onFollowUp(commitmentId)
                        onDismiss()
                    },
                    onComplete = {
                        managementViewModel.onComplete(commitmentId)
                        onDismiss()
                    },
                    onCancel = {
                        managementViewModel.onCancel(commitmentId)
                        onDismiss()
                    },
                    onEdit = {
                        // Dismiss the detail sheet first so the edit sheet lands
                        // as the single visible modal. The nav host wires this
                        // callback to BecalmRoute.CommitmentEdit (EDIT-001..008).
                        onDismiss()
                        onEdit()
                    },
                )
            }
        }
    }
}

// ─── Content ──────────────────────────────────────────────────────────────────

@Composable
private fun DetailSheetContent(
    entity: CommitmentEntity,
    actionState: CommitmentState,
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
            SimpleChip(text = entity.direction.uppercase())
            SimpleChip(text = stringForActionState(actionState))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Quote section (read-only; disputed badge if applicable)
        SectionLabel(text = stringResource(R.string.commitment_detail_quote_label))
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = entity.quote,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (entity.quoteDisputed) {
            Spacer(modifier = Modifier.height(6.dp))
            val disputedAt = entity.quoteDisputedAt
            val disputedWhen = disputedAt?.let(::formatKstShort).orEmpty()
            Text(
                text = stringResource(R.string.commitment_detail_disputed_badge_fmt, disputedWhen),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Source section — manual vs LLM-extracted diverge per MAN-004
        val isManual = entity.sourceType == SourceType.MANUAL
        val sourceText = if (isManual) {
            stringResource(
                R.string.commitment_detail_manual_source_fmt,
                formatKstShort(entity.createdAt),
            )
        } else {
            stringResource(
                R.string.commitment_detail_llm_source_fmt,
                entity.sourceEventTitle ?: entity.sourceType,
                formatKstShort(entity.sourceEventOccurredAt),
            )
        }
        Text(
            text = sourceText,
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
        val dueAt = entity.dueAt
        if (dueAt != null) {
            SectionLabel(text = stringResource(R.string.commitment_detail_due_label))
            Spacer(modifier = Modifier.height(2.dp))
            val prefix = if (entity.dueIsApproximate) "~" else ""
            Text(
                text = prefix + formatKstShort(dueAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (entity.dueIsApproximate && !entity.dueHint.isNullOrBlank()) {
                Text(
                    text = entity.dueHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 7. Action button strip
        ActionButtonRow(
            actionState = actionState,
            isDeleted = entity.deletedAt != null,
            onRemind = onRemind,
            onFollowUp = onFollowUp,
            onComplete = onComplete,
            onCancel = onCancel,
            onEdit = onEdit,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 8. Footer — last-edited banner + supersede backlink
        if (entity.lastEditedAt != null) {
            Text(
                text = stringResource(
                    R.string.commitment_detail_last_edited_fmt,
                    formatKstShort(entity.lastEditedAt),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (entity.supersedesCommitmentId != null) {
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
    val remindEnabled = actionState == CommitmentState.PENDING
    val followUpEnabled =
        actionState == CommitmentState.PENDING || actionState == CommitmentState.REMINDED
    val completeEnabled = actionState == CommitmentState.PENDING ||
        actionState == CommitmentState.REMINDED ||
        actionState == CommitmentState.FOLLOWED_UP ||
        actionState == CommitmentState.OVERDUE
    val cancelEnabled = completeEnabled
    val editEnabled = actionState != CommitmentState.CANCELLED && !isDeleted

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedButton(
            onClick = onRemind,
            enabled = remindEnabled,
            modifier = Modifier.widthIn(min = 0.dp),
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
            modifier = Modifier.widthIn(min = 0.dp),
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

/**
 * Formats [instant] as `"M/d HH:mm"` in [KST_ZONE]. Used for the last-edited banner,
 * the disputed badge timestamp, and the source/due lines. Kept inline (rather than
 * shared via `TimeFormat`) because this sheet is the only caller of this exact
 * format; generalising prematurely would violate CLAUDE.md "Simplicity First".
 */
private fun formatKstShort(instant: Instant): String {
    val ldt = instant.toLocalDateTime(KST_ZONE)
    val hour = ldt.hour.toString().padStart(2, '0')
    val minute = ldt.minute.toString().padStart(2, '0')
    return "${ldt.monthNumber}/${ldt.dayOfMonth} $hour:$minute"
}

private val KST_ZONE: TimeZone = TimeZone.of("Asia/Seoul")

private fun stringForActionState(state: CommitmentState): String = when (state) {
    CommitmentState.PENDING -> "PENDING"
    CommitmentState.REMINDED -> "REMINDED"
    CommitmentState.FOLLOWED_UP -> "FOLLOWED_UP"
    CommitmentState.COMPLETED -> "COMPLETED"
    CommitmentState.OVERDUE -> "OVERDUE"
    CommitmentState.CANCELLED -> "CANCELLED"
}

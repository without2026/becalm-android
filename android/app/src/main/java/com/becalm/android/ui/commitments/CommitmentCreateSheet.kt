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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.becalm.android.R
import com.becalm.android.domain.commitment.CommitmentManualValidator.Field
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// ─── CommitmentCreateSheet ────────────────────────────────────────────────────

/**
 * Bottom-sheet host for the manual-create commitment form (MAN-001..006) and
 * the supersede-create flow (EDIT-007).
 *
 * Opened from:
 * - The `[+ 약속 추가]` FAB on [CommitmentManagementScreen]
 *   (`supersedeOf = null` → plain manual add).
 * - The `[이건 다른 약속입니다]` button on [CommitmentEditSheet]
 *   (`supersedeOf = oldId` → supersede).
 *
 * Both flows ultimately call
 * [com.becalm.android.data.repository.CommitmentRepository.saveManualCommitment]
 * with `source_type = 'manual'`; the entry mode (MANUAL vs SUPERSEDE) only
 * affects sheet-header copy and whether the quote field is editable.
 *
 * ## Quote in SUPERSEDE mode (EDIT-007 invariant 1)
 *
 * In supersede mode the user is correcting the *interpretation* of an
 * existing event, not inventing a new one. The evidentiary quote string is
 * therefore rendered as a plain [Text] — **never** a [OutlinedTextField] —
 * and [CommitmentCreateViewModel.onSave] copies the old row's quote verbatim
 * into the draft before validation. The VM also silently drops any
 * [CommitmentCreateViewModel.onQuoteChange] call in supersede mode as a
 * defence-in-depth guard against future caller bypasses.
 *
 * @param supersedeOf UUID of the commitment being superseded, or `null` for
 *   plain manual add. Threaded via [androidx.lifecycle.SavedStateHandle] into
 *   the VM by Hilt; the parameter exists on the Composable signature so the
 *   nav host can pass it explicitly.
 * @param onDismiss Invoked whenever the sheet should dismiss itself (swipe,
 *   cancel button, successful save).
 * @param viewModel Hilt-provided ViewModel instance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun CommitmentCreateSheet(
    @Suppress("UNUSED_PARAMETER") supersedeOf: String?,
    onDismiss: () -> Unit,
    viewModel: CommitmentCreateViewModel? = null,
    stateOverride: CreateUiState? = null,
    dismissEventsOverride: Flow<Unit>? = null,
    onTitleChange: ((String) -> Unit)? = null,
    onDirectionChange: ((String) -> Unit)? = null,
    onQuoteChange: ((String) -> Unit)? = null,
    onPersonRefChange: ((String) -> Unit)? = null,
    onDueAtMillisChange: ((Long?) -> Unit)? = null,
    onDueHintChange: ((String) -> Unit)? = null,
    onApproxChange: ((Boolean) -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    // `supersedeOf` is consumed by the Hilt-injected [CommitmentCreateViewModel]
    // via its SavedStateHandle; the parameter stays on this signature so the nav
    // host can pass it explicitly at call time (see BecalmNavHost wiring).
    val createViewModel = if (
        stateOverride == null ||
            dismissEventsOverride == null ||
            onTitleChange == null ||
            onDirectionChange == null ||
            onQuoteChange == null ||
            onPersonRefChange == null ||
            onDueAtMillisChange == null ||
            onDueHintChange == null ||
            onApproxChange == null ||
            onSave == null ||
            onCancel == null
    ) {
        viewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<CommitmentCreateViewModel>()
    } else {
        viewModel
    }
    val state = if (stateOverride != null) {
        stateOverride
    } else {
        val collectedState by requireNotNull(createViewModel).uiState.collectAsStateWithLifecycle()
        collectedState
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Consume one-shot dismiss events from the VM (save success).
    LaunchedEffect(dismissEventsOverride, createViewModel) {
        (dismissEventsOverride ?: requireNotNull(createViewModel).dismiss).collect { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        CreateSheetContent(
            state = state,
            onTitleChange = onTitleChange ?: requireNotNull(createViewModel)::onTitleChange,
            onDirectionChange = onDirectionChange ?: requireNotNull(createViewModel)::onDirectionChange,
            onQuoteChange = onQuoteChange ?: requireNotNull(createViewModel)::onQuoteChange,
            onPersonRefChange = onPersonRefChange ?: requireNotNull(createViewModel)::onPersonRefChange,
            onDueAtMillisChange = onDueAtMillisChange ?: requireNotNull(createViewModel)::onDueAtMillisChange,
            onDueHintChange = onDueHintChange ?: requireNotNull(createViewModel)::onDueHintChange,
            onApproxChange = onApproxChange ?: requireNotNull(createViewModel)::onApproxChange,
            onSave = onSave ?: requireNotNull(createViewModel)::onSave,
            onCancel = onCancel ?: onDismiss,
        )
    }
}

// ─── Content ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateSheetContent(
    state: CreateUiState,
    onTitleChange: (String) -> Unit,
    onDirectionChange: (String) -> Unit,
    onQuoteChange: (String) -> Unit,
    onPersonRefChange: (String) -> Unit,
    onDueAtMillisChange: (Long?) -> Unit,
    onDueHintChange: (String) -> Unit,
    onApproxChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .testTag("commitment-create-form")
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
                val headerRes = when (state.mode) {
                    CommitmentCreateMode.MANUAL -> R.string.commitment_manual_sheet_title_new
                    CommitmentCreateMode.SUPERSEDE -> R.string.commitment_manual_sheet_title_supersede
                }
                Text(
                    text = stringResource(headerRes),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            item {
                OutlinedTextField(
                    value = state.draft.title,
                    onValueChange = onTitleChange,
                    label = { Text(text = stringResource(R.string.commitment_manual_field_title)) },
                    isError = state.fieldErrors.containsKey(Field.TITLE),
                    supportingText = {
                        state.fieldErrors[Field.TITLE]?.let { Text(text = it) }
                    },
                    singleLine = true,
                    enabled = !state.saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("commitment-create-title"),
                )
            }

            item {
                Column {
                    SectionLabel(text = stringResource(R.string.commitment_edit_field_direction))
                    Spacer(modifier = Modifier.height(4.dp))
                    DirectionRow(
                        current = state.draft.direction,
                        enabled = !state.saving,
                        onChange = onDirectionChange,
                    )
                    state.fieldErrors[Field.DIRECTION]?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            item {
                // In SUPERSEDE mode the quote is legally evidentiary (EDIT-007
                // invariant 1) — render as read-only text, never as TextField.
                when (state.mode) {
                    CommitmentCreateMode.MANUAL -> {
                        OutlinedTextField(
                            value = state.draft.quote,
                            onValueChange = onQuoteChange,
                            label = { Text(text = stringResource(R.string.commitment_manual_quote_hint)) },
                            isError = state.fieldErrors.containsKey(Field.QUOTE),
                            supportingText = {
                                state.fieldErrors[Field.QUOTE]?.let { Text(text = it) }
                            },
                            enabled = !state.saving,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("commitment-create-quote"),
                        )
                    }
                    CommitmentCreateMode.SUPERSEDE -> {
                        SectionLabel(
                            text = stringResource(R.string.commitment_manual_supersede_quote_label),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.supersedeSource?.quote.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = state.draft.personRef.orEmpty(),
                    onValueChange = onPersonRefChange,
                    label = { Text(text = stringResource(R.string.commitment_manual_field_person_ref)) },
                    placeholder = {
                        Text(text = stringResource(R.string.commitment_edit_field_person_placeholder))
                    },
                    isError = state.fieldErrors.containsKey(Field.PERSON_REF),
                    supportingText = {
                        state.fieldErrors[Field.PERSON_REF]?.let { Text(text = it) }
                    },
                    singleLine = true,
                    enabled = !state.saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("commitment-create-person-ref"),
                )
            }

            item {
                Column {
                    SectionLabel(text = stringResource(R.string.commitment_edit_field_due))
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = state.draft.dueAtMillis?.let { formatKstFromMillis(it) }
                                ?: stringResource(R.string.commitment_manual_no_due_date),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { showDatePicker = true },
                            enabled = !state.saving,
                        ) {
                            Text(text = stringResource(R.string.commitment_manual_field_due_pick))
                        }
                        if (state.draft.dueAtMillis != null) {
                            OutlinedButton(
                                onClick = { onDueAtMillisChange(null) },
                                enabled = !state.saving,
                            ) {
                                Text(text = stringResource(R.string.commitment_edit_field_due_clear))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.commitment_manual_field_due_approximate),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = state.draft.dueIsApproximate,
                            onCheckedChange = onApproxChange,
                            enabled = !state.saving,
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = state.draft.dueHint.orEmpty(),
                    onValueChange = onDueHintChange,
                    label = { Text(text = stringResource(R.string.commitment_manual_field_due_hint)) },
                    singleLine = true,
                    enabled = !state.saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("commitment-create-due-hint"),
                )
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }
        }

        state.saveError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onCancel,
                enabled = !state.saving,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.commitment_manual_cancel))
            }
            Button(
                onClick = onSave,
                enabled = !state.saving,
                modifier = Modifier
                    .weight(1f)
                    .testTag("commitment-create-save"),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .heightIn(max = 16.dp),
                    )
                }
                Text(text = stringResource(R.string.commitment_manual_save))
            }
        }
    }

    // ── Date picker dialog ──
    if (showDatePicker) {
        val initialMillis = state.draft.dueAtMillis
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDueAtMillisChange(dateState.selectedDateMillis)
                        showDatePicker = false
                    },
                ) { Text(text = stringResource(R.string.commitment_manual_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(text = stringResource(R.string.commitment_manual_cancel))
                }
            },
        ) {
            DatePicker(state = dateState)
        }
    }
}

// ─── Direction radio row ──────────────────────────────────────────────────────

@Composable
private fun DirectionRow(
    current: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DirectionOption(
            label = stringResource(R.string.commitment_manual_field_direction_give),
            selected = current == "give",
            enabled = enabled,
            onSelect = { onChange("give") },
        )
        DirectionOption(
            label = stringResource(R.string.commitment_manual_field_direction_take),
            selected = current == "take",
            enabled = enabled,
            onSelect = { onChange("take") },
        )
    }
}

@Composable
private fun DirectionOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.selectable(
            selected = selected,
            enabled = enabled,
            onClick = onSelect,
        ),
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Formats [epochMillis] as `"yyyy-MM-dd HH:mm KST"` in KST. Local mirror of
 * the formatter in [CommitmentEditSheet] — kept private here to avoid a
 * cross-file dependency since these are the only two call sites and the
 * function is a few lines.
 */
private fun formatKstFromMillis(epochMillis: Long): String {
    val ldt = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(KST_ZONE)
    val month = ldt.monthNumber.toString().padStart(2, '0')
    val day = ldt.dayOfMonth.toString().padStart(2, '0')
    val hour = ldt.hour.toString().padStart(2, '0')
    val minute = ldt.minute.toString().padStart(2, '0')
    return "${ldt.year}-$month-$day $hour:$minute KST"
}

private val KST_ZONE: TimeZone = TimeZone.of("Asia/Seoul")

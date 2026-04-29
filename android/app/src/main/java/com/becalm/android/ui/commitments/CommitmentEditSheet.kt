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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
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
import com.becalm.android.domain.commitment.CommitmentEditValidator.Field
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.toInstant

// ─── CommitmentEditSheet ──────────────────────────────────────────────────────

/**
 * Bottom-sheet host for the commitment edit form (EDIT-001..008).
 *
 * Opened from the `[편집]` button on [CommitmentDetailSheet] via
 * [com.becalm.android.ui.navigation.BecalmRoute.CommitmentEdit].
 *
 * Responsibilities:
 * - Render the editable form (title / due / person / direction) with field-level
 *   validation errors sourced from [CommitmentEditViewModel.uiState].
 * - Render the read-only evidentiary section (quote + dispute toggle + source).
 *   The quote string is a plain [Text] — **never** a [OutlinedTextField] — per
 *   `.spec/commitment-edit.spec.yml` invariant 1 (quote immutability).
 * - Expose the danger zone (soft-delete) via a confirmation dialog so the user
 *   cannot accidentally tombstone a row with a single tap.
 *
 * ## Quote invariance (`.spec/commitment-edit.spec.yml` invariant 1)
 *
 * The quote string is evidentiary. The UI surfaces it as a read-only
 * [Text] with dispute toggle only. There is no code path — in this file or
 * the VM — that writes the quote column. The repository method
 * [com.becalm.android.data.repository.CommitmentRepository.editCommitment]
 * also has no `quote` parameter by design.
 *
 * @param commitmentId UUID of the commitment to edit. Threaded via
 *   [SavedStateHandle] into [CommitmentEditViewModel] by Hilt; the parameter
 *   exists on the Composable signature so the nav host passes it explicitly.
 * @param onDismiss Invoked whenever the sheet should dismiss itself (swipe,
 *   successful save, successful delete, cancel button).
 * @param viewModel Hilt-provided ViewModel instance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun CommitmentEditSheet(
    @Suppress("UNUSED_PARAMETER") commitmentId: String,
    onDismiss: () -> Unit,
    viewModel: CommitmentEditViewModel? = null,
    stateOverride: EditUiState? = null,
    dismissEventsOverride: Flow<Unit>? = null,
    onTitleChange: ((String) -> Unit)? = null,
    onDueAtMillisChange: ((Long?) -> Unit)? = null,
    onDueIsApproximateChange: ((Boolean) -> Unit)? = null,
    onDueHintChange: ((String) -> Unit)? = null,
    onPersonRefChange: ((String) -> Unit)? = null,
    onDirectionChange: ((String) -> Unit)? = null,
    onToggleDispute: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onConfirmDelete: (() -> Unit)? = null,
) {
    // `commitmentId` is consumed by the Hilt-injected [CommitmentEditViewModel]
    // via its SavedStateHandle; the parameter stays on this signature so the nav
    // host can pass it explicitly at call time (see BecalmNavHost wiring).
    val editViewModel = if (
        stateOverride == null ||
            dismissEventsOverride == null ||
            onTitleChange == null ||
            onDueAtMillisChange == null ||
            onDueIsApproximateChange == null ||
            onDueHintChange == null ||
            onPersonRefChange == null ||
            onDirectionChange == null ||
            onToggleDispute == null ||
            onSave == null ||
            onCancel == null ||
            onConfirmDelete == null
    ) {
        viewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<CommitmentEditViewModel>()
    } else {
        viewModel
    }
    val state = if (stateOverride != null) {
        stateOverride
    } else {
        val collectedState by requireNotNull(editViewModel).uiState.collectAsStateWithLifecycle()
        collectedState
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // Consume one-shot dismiss events from the VM (saved / deleted / cancelled).
    // All three events map to the same outcome here — pop the sheet.
    LaunchedEffect(dismissEventsOverride, editViewModel) {
        (dismissEventsOverride ?: requireNotNull(editViewModel).dismiss).collect { onDismiss() }
    }

    // NotFound short-circuit — the row was deleted between detail-sheet open
    // and edit-sheet entry. Just dismiss.
    LaunchedEffect(state.notFound) {
        if (state.notFound) onDismiss()
    }

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
            state.notFound -> {
                // Short-circuit while the dismiss LaunchedEffect fires.
                Spacer(modifier = Modifier.height(1.dp))
            }
            else -> {
                EditSheetContent(
                    state = state,
                    onTitleChange = onTitleChange ?: requireNotNull(editViewModel)::onTitleChange,
                    onDueAtMillisChange = onDueAtMillisChange ?: requireNotNull(editViewModel)::onDueAtMillisChange,
                    onDueIsApproximateChange = onDueIsApproximateChange ?: requireNotNull(editViewModel)::onDueIsApproximateChange,
                    onDueHintChange = onDueHintChange ?: requireNotNull(editViewModel)::onDueHintChange,
                    onPersonRefChange = onPersonRefChange ?: requireNotNull(editViewModel)::onPersonRefChange,
                    onDirectionChange = onDirectionChange ?: requireNotNull(editViewModel)::onDirectionChange,
                    onToggleDispute = onToggleDispute ?: requireNotNull(editViewModel)::onToggleDispute,
                    onSave = onSave ?: requireNotNull(editViewModel)::onSave,
                    onCancel = onCancel ?: requireNotNull(editViewModel)::onCancel,
                    onConfirmDelete = onConfirmDelete ?: requireNotNull(editViewModel)::onConfirmDelete,
                )
            }
        }
    }
}

// ─── Content ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditSheetContent(
    state: EditUiState,
    onTitleChange: (String) -> Unit,
    onDueAtMillisChange: (Long?) -> Unit,
    onDueIsApproximateChange: (Boolean) -> Unit,
    onDueHintChange: (String) -> Unit,
    onPersonRefChange: (String) -> Unit,
    onDirectionChange: (String) -> Unit,
    onToggleDispute: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val scrollState = rememberScrollState()
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDateMillis by remember { mutableStateOf<Long?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(scrollState),
    ) {
        // ── Header ──
        Text(
            text = stringResource(R.string.commitment_edit_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(16.dp))

        // ── Read-only evidentiary section ──
        // IMPORTANT: the quote string is rendered as plain Text, NEVER as
        // TextField. This is a hard invariant (quote immutability, EDIT-spec
        // invariant 1). Do not change this to an editable control.
        val readOnly = state.readOnly
        if (readOnly != null) {
            SectionLabel(text = stringResource(R.string.commitment_edit_readonly_source_label))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = readOnly.sourceLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            SectionLabel(text = stringResource(R.string.commitment_edit_readonly_quote_label))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = readOnly.quote,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.commitment_edit_toggle_dispute),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = readOnly.quoteDisputed,
                    onCheckedChange = { onToggleDispute() },
                    enabled = !state.saving,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── Title ──
        OutlinedTextField(
            value = state.title,
            onValueChange = onTitleChange,
            label = { Text(text = stringResource(R.string.commitment_edit_field_title)) },
            isError = state.fieldErrors.containsKey(Field.TITLE),
            supportingText = {
                state.fieldErrors[Field.TITLE]?.let { Text(text = it) }
            },
            singleLine = true,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))

        // ── Due at ──
        SectionLabel(text = stringResource(R.string.commitment_edit_field_due))
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = state.dueAtMillis?.let { formatKstFromMillis(it) }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { showDatePicker = true },
                enabled = !state.saving,
            ) {
                Text(text = stringResource(R.string.commitment_edit_field_due_pick))
            }
            if (state.dueAtMillis != null) {
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
                text = stringResource(R.string.commitment_edit_field_due_approximate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.dueIsApproximate,
                onCheckedChange = onDueIsApproximateChange,
                enabled = !state.saving,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.dueHint,
            onValueChange = onDueHintChange,
            label = { Text(text = stringResource(R.string.commitment_edit_field_due_hint)) },
            singleLine = true,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))

        // ── Person ref ──
        OutlinedTextField(
            value = state.personRef,
            onValueChange = onPersonRefChange,
            label = { Text(text = stringResource(R.string.commitment_edit_field_person)) },
            placeholder = { Text(text = stringResource(R.string.commitment_edit_field_person_placeholder)) },
            isError = state.fieldErrors.containsKey(Field.PERSON_REF),
            supportingText = {
                state.fieldErrors[Field.PERSON_REF]?.let { Text(text = it) }
            },
            singleLine = true,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))

        // ── Direction ──
        SectionLabel(text = stringResource(R.string.commitment_edit_field_direction))
        Spacer(modifier = Modifier.height(4.dp))
        DirectionRadioRow(
            current = state.direction,
            enabled = !state.saving,
            onChange = onDirectionChange,
        )
        state.fieldErrors[Field.DIRECTION]?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        // ── Save error ──
        state.saveError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ── Action buttons ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onCancel,
                enabled = !state.saving,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.commitment_edit_cancel))
            }
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                enabled = !state.saving,
                modifier = Modifier
                    .weight(1f)
                    .testTag("commitment-edit-delete"),
            ) {
                Text(
                    text = stringResource(R.string.commitment_edit_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = onSave,
                enabled = !state.saving,
                modifier = Modifier
                    .weight(1f)
                    .testTag("commitment-edit-save"),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .heightIn(max = 16.dp),
                    )
                }
                Text(
                    text = if (state.saving) {
                        stringResource(R.string.commitment_edit_saving)
                    } else {
                        stringResource(R.string.commitment_edit_save)
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // ── Date picker dialog ──
    if (showDatePicker) {
        val initialMillis = state.dueAtMillis
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let { selectedDateMillis ->
                            pendingDateMillis = selectedDateMillis
                            showTimePicker = true
                        }
                        showDatePicker = false
                    },
                ) { Text(text = stringResource(R.string.commitment_edit_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(text = stringResource(R.string.commitment_edit_cancel))
                }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    // ── Time picker dialog ──
    if (showTimePicker) {
        val initialTimeMillis = state.dueAtMillis ?: pendingDateMillis
        val initialTime = initialTimeMillis?.let {
            Instant.fromEpochMilliseconds(it).toLocalDateTime(KST_ZONE)
        }
        val timeState = rememberTimePickerState(
            initialHour = initialTime?.hour ?: 9,
            initialMinute = initialTime?.minute ?: 0,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = {
                showTimePicker = false
                pendingDateMillis = null
            },
            title = { Text(text = stringResource(R.string.commitment_edit_field_due_pick)) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val dateMillis = pendingDateMillis ?: state.dueAtMillis
                        if (dateMillis != null) {
                            onDueAtMillisChange(
                                combinePickerDateAndKstTime(
                                    dateMillis = dateMillis,
                                    hour = timeState.hour,
                                    minute = timeState.minute,
                                ),
                            )
                        }
                        showTimePicker = false
                        pendingDateMillis = null
                    },
                ) { Text(text = stringResource(R.string.commitment_edit_save)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTimePicker = false
                        pendingDateMillis = null
                    },
                ) { Text(text = stringResource(R.string.commitment_edit_cancel)) }
            },
        )
    }

    // ── Soft-delete confirmation ──
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(text = stringResource(R.string.commitment_edit_delete_confirm_title)) },
            text = { Text(text = stringResource(R.string.commitment_edit_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onConfirmDelete()
                    },
                    modifier = Modifier.testTag("commitment-edit-delete-confirm-ok"),
                ) {
                    Text(
                        text = stringResource(R.string.commitment_edit_delete_confirm_ok),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(text = stringResource(R.string.commitment_edit_delete_confirm_cancel))
                }
            },
        )
    }
}

// ─── Direction radio row ──────────────────────────────────────────────────────

@Composable
private fun DirectionRadioRow(
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
            label = stringResource(R.string.commitment_edit_field_direction_give),
            selected = current == "give",
            enabled = enabled,
            onSelect = { onChange("give") },
        )
        DirectionOption(
            label = stringResource(R.string.commitment_edit_field_direction_take),
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
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Formats [epochMillis] as `"yyyy-MM-dd HH:mm"` in KST. Matches the short form
 * used by [CommitmentDetailSheet.formatKstShort] but kept private here to avoid
 * a cross-file dependency — the two call sites are the only consumers and the
 * function is small.
 */
private fun formatKstFromMillis(epochMillis: Long): String {
    val ldt = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(KST_ZONE)
    val month = ldt.monthNumber.toString().padStart(2, '0')
    val day = ldt.dayOfMonth.toString().padStart(2, '0')
    val hour = ldt.hour.toString().padStart(2, '0')
    val minute = ldt.minute.toString().padStart(2, '0')
    return "${ldt.year}-$month-$day $hour:$minute KST"
}

private fun combinePickerDateAndKstTime(
    dateMillis: Long,
    hour: Int,
    minute: Int,
): Long {
    val date = Instant.fromEpochMilliseconds(dateMillis)
        .toLocalDateTime(TimeZone.UTC)
        .date
    return LocalDateTime(
        year = date.year,
        monthNumber = date.monthNumber,
        dayOfMonth = date.dayOfMonth,
        hour = hour,
        minute = minute,
    ).toInstant(KST_ZONE).toEpochMilliseconds()
}

private val KST_ZONE: TimeZone = TimeZone.of("Asia/Seoul")

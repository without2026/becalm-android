package com.becalm.android.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.domain.onboarding.FirstMemoryKind
import com.becalm.android.domain.onboarding.FirstMemoryOrigin
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmTextField
import com.becalm.android.ui.components.QuietPanel
import com.becalm.android.ui.theme.BecalmTheme
import java.util.UUID

public data class FirstMemoryActivationUiState(
    val clientMemoryId: String = UUID.randomUUID().toString(),
    val origin: FirstMemoryOrigin? = null,
    val personName: String = "",
    val promiseText: String = "",
    val kind: FirstMemoryKind? = null,
    val dueHint: String = "",
    val saving: Boolean = false,
    @StringRes val errorMessageRes: Int? = null,
)

@Composable
internal fun FirstMemoryActivationContent(
    state: FirstMemoryActivationUiState,
    onOriginChange: (FirstMemoryOrigin) -> Unit,
    onPersonNameChange: (String) -> Unit,
    onPromiseTextChange: (String) -> Unit,
    onKindChange: (FirstMemoryKind) -> Unit,
    onDueHintChange: (String) -> Unit,
    onSave: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    showSkip: Boolean = true,
) {
    val canSave = state.origin != null &&
        state.personName.isNotBlank() &&
        state.promiseText.isNotBlank() &&
        state.kind != null &&
        !state.saving
    val guidanceMessageRes = state.guidanceMessageRes(canSave)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("first-memory-activation"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FirstMemoryHeader()
        FirstMemorySourceChoices(
            selected = state.origin,
            onSelect = onOriginChange,
        )
        if (state.origin != null) {
            FirstMemoryForm(
                state = state,
                onPersonNameChange = onPersonNameChange,
                onPromiseTextChange = onPromiseTextChange,
                onKindChange = onKindChange,
                onDueHintChange = onDueHintChange,
                onSave = onSave,
                onSkip = onSkip,
                showSkip = showSkip,
                canSave = canSave,
                guidanceMessageRes = guidanceMessageRes,
            )
        } else if (showSkip) {
            BecalmButton(
                text = stringResource(R.string.first_memory_skip),
                onClick = onSkip,
                variant = BecalmButtonVariant.Text,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("first-memory-skip"),
            )
        }
    }
}

@Composable
private fun FirstMemoryHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.first_memory_headline),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.first_memory_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FirstMemorySourceChoices(
    selected: FirstMemoryOrigin?,
    onSelect: (FirstMemoryOrigin) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FirstMemoryOrigin.entries.forEach { origin ->
            FirstMemoryChoiceRow(
                icon = origin.icon,
                title = stringResource(origin.titleRes),
                description = stringResource(origin.descriptionRes),
                selected = selected == origin,
                testTag = "first-memory-source-${origin.name.lowercase()}",
                onClick = { onSelect(origin) },
            )
        }
    }
}

@Composable
private fun FirstMemoryForm(
    state: FirstMemoryActivationUiState,
    onPersonNameChange: (String) -> Unit,
    onPromiseTextChange: (String) -> Unit,
    onKindChange: (FirstMemoryKind) -> Unit,
    onDueHintChange: (String) -> Unit,
    onSave: () -> Unit,
    onSkip: () -> Unit,
    showSkip: Boolean,
    canSave: Boolean,
    @StringRes guidanceMessageRes: Int?,
) {
    QuietPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val originTitle = state.origin?.let { stringResource(it.titleRes) }.orEmpty()
            Text(
                text = stringResource(R.string.first_memory_form_title_fmt, originTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            BecalmTextField(
                value = state.personName,
                onValueChange = onPersonNameChange,
                label = stringResource(R.string.first_memory_person_label),
                placeholder = stringResource(R.string.first_memory_person_placeholder),
                imeAction = ImeAction.Next,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("first-memory-person"),
            )
            BecalmTextField(
                value = state.promiseText,
                onValueChange = onPromiseTextChange,
                label = stringResource(R.string.first_memory_promise_label),
                placeholder = stringResource(R.string.first_memory_promise_placeholder),
                imeAction = ImeAction.Next,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("first-memory-promise"),
            )
            Text(
                text = stringResource(R.string.first_memory_kind_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FirstMemoryKind.entries.forEach { kind ->
                    FirstMemoryChoiceRow(
                        icon = null,
                        title = stringResource(kind.titleRes),
                        description = stringResource(kind.descriptionRes),
                        selected = state.kind == kind,
                        testTag = "first-memory-kind-${kind.name.lowercase()}",
                        onClick = { onKindChange(kind) },
                    )
                }
            }
            if (state.kind == FirstMemoryKind.SHARED_SCHEDULE) {
                BecalmTextField(
                    value = state.dueHint,
                    onValueChange = onDueHintChange,
                    label = stringResource(R.string.first_memory_due_hint_label),
                    placeholder = stringResource(R.string.first_memory_due_hint_placeholder),
                    imeAction = ImeAction.Done,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("first-memory-due-hint"),
                )
            }
            val messageRes = state.errorMessageRes ?: guidanceMessageRes
            messageRes?.let { resId ->
                Text(
                    text = stringResource(resId),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.errorMessageRes != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag(
                        if (state.errorMessageRes != null) {
                            "first-memory-error"
                        } else {
                            "first-memory-guidance"
                        },
                    ),
                )
            }
            BecalmButton(
                text = stringResource(R.string.first_memory_save),
                onClick = onSave,
                enabled = canSave,
                loading = state.saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("first-memory-save"),
            )
            if (showSkip) {
                BecalmButton(
                    text = stringResource(R.string.first_memory_skip),
                    onClick = onSkip,
                    variant = BecalmButtonVariant.Text,
                    enabled = !state.saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("first-memory-skip"),
                )
            }
        }
    }
}

@StringRes
private fun FirstMemoryActivationUiState.guidanceMessageRes(canSave: Boolean): Int? {
    if (canSave || saving) return null
    return when {
        personName.isBlank() -> R.string.first_memory_next_person
        promiseText.isBlank() -> R.string.first_memory_next_promise
        kind == null -> R.string.first_memory_next_kind
        else -> null
    }
}

private val FirstMemoryOrigin.icon: ImageVector
    get() = when (this) {
        FirstMemoryOrigin.EMAIL -> Icons.Outlined.Email
        FirstMemoryOrigin.CALL -> Icons.Outlined.Call
        FirstMemoryOrigin.MEETING -> Icons.Outlined.Groups
        FirstMemoryOrigin.MESSENGER -> Icons.AutoMirrored.Outlined.Message
    }

@get:StringRes
private val FirstMemoryOrigin.titleRes: Int
    get() = when (this) {
        FirstMemoryOrigin.EMAIL -> R.string.first_memory_source_email_title
        FirstMemoryOrigin.CALL -> R.string.first_memory_source_call_title
        FirstMemoryOrigin.MEETING -> R.string.first_memory_source_meeting_title
        FirstMemoryOrigin.MESSENGER -> R.string.first_memory_source_messenger_title
    }

@get:StringRes
private val FirstMemoryOrigin.descriptionRes: Int
    get() = when (this) {
        FirstMemoryOrigin.EMAIL -> R.string.first_memory_source_email_body
        FirstMemoryOrigin.CALL -> R.string.first_memory_source_call_body
        FirstMemoryOrigin.MEETING -> R.string.first_memory_source_meeting_body
        FirstMemoryOrigin.MESSENGER -> R.string.first_memory_source_messenger_body
    }

@get:StringRes
private val FirstMemoryKind.titleRes: Int
    get() = when (this) {
        FirstMemoryKind.MY_ACTION -> R.string.first_memory_kind_my_action_title
        FirstMemoryKind.THEIR_ACTION -> R.string.first_memory_kind_their_action_title
        FirstMemoryKind.SHARED_SCHEDULE -> R.string.first_memory_kind_shared_schedule_title
    }

@get:StringRes
private val FirstMemoryKind.descriptionRes: Int
    get() = when (this) {
        FirstMemoryKind.MY_ACTION -> R.string.first_memory_kind_my_action_body
        FirstMemoryKind.THEIR_ACTION -> R.string.first_memory_kind_their_action_body
        FirstMemoryKind.SHARED_SCHEDULE -> R.string.first_memory_kind_shared_schedule_body
    }

@Composable
private fun FirstMemoryChoiceRow(
    icon: ImageVector?,
    title: String,
    description: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
    }
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
    }
    val semanticsLabel = "$title, $description"
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .semantics {
                contentDescription = semanticsLabel
                role = Role.RadioButton
                this.selected = selected
            }
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.70f)),
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewFirstMemoryActivationContent() {
    var state by remember {
        mutableStateOf(
            FirstMemoryActivationUiState(
                origin = FirstMemoryOrigin.EMAIL,
                personName = "민지",
                promiseText = "금요일까지 제안서 초안 보내기",
                kind = FirstMemoryKind.MY_ACTION,
            ),
        )
    }
    BecalmTheme {
        FirstMemoryActivationContent(
            state = state,
            onOriginChange = { state = state.copy(origin = it) },
            onPersonNameChange = { state = state.copy(personName = it) },
            onPromiseTextChange = { state = state.copy(promiseText = it) },
            onKindChange = { state = state.copy(kind = it) },
            onDueHintChange = { state = state.copy(dueHint = it) },
            onSave = {},
            onSkip = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

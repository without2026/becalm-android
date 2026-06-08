package com.becalm.android.ui.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.EvidenceCard
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.persons.PersonActionDraftSheetStatus
import com.becalm.android.ui.persons.PersonActionDraftSheetUiState

@Composable
public fun PersonActionDraftDialog(
    state: PersonActionDraftSheetUiState,
    onSubjectChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenEvidence: (() -> Unit)? = null,
    onOpenExternalDraft: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val openExternalDraft = onOpenExternalDraft ?: { context.openDraftInExternalMailClient(state) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DraftSheetScrim, RectangleShape)
                .testTag("person-action-draft-dialog"),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .testTag("person-action-draft-sheet"),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = DraftSheetSurface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    DraftGrabHandle(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text(
                        text = draftSheetLabel(state.draftKind),
                        style = MaterialTheme.typography.labelLarge,
                        color = DraftSheetFaintText,
                    )
                    Text(
                        text = state.actionTitle?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.person_action_draft_sheet_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = DraftSheetInk,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    draftHeaderMeta(state)?.let { headerMeta ->
                        Text(
                            text = headerMeta,
                            style = MaterialTheme.typography.bodySmall,
                            color = DraftSheetMutedText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DraftRecipientRow(recipientLabel = state.recipientLabel)
                    when (state.status) {
                        PersonActionDraftSheetStatus.LOADING -> {
                            EvidenceCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = stringResource(R.string.person_action_draft_loading),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        PersonActionDraftSheetStatus.READY -> {
                            state.error?.let { error ->
                                EvidenceCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = uiMessageStringResource(error),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            DraftBodyEditor(
                                value = state.body,
                                onValueChange = onBodyChange,
                                label = stringResource(R.string.person_action_draft_body_label),
                                placeholder = stringResource(R.string.person_action_draft_body_placeholder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("person-action-draft-body"),
                            )
                            if (state.requiresUserReview) {
                                Text(
                                    text = draftReviewNotice(state),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DraftSheetFaintText,
                                )
                            }
                        }
                        PersonActionDraftSheetStatus.UNSUPPORTED,
                        PersonActionDraftSheetStatus.AUTH_REQUIRED,
                        PersonActionDraftSheetStatus.ERROR,
                        -> {
                            EvidenceCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = state.error?.let { uiMessageStringResource(it) }
                                        ?: stringResource(R.string.person_action_draft_failed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    if (state.status == PersonActionDraftSheetStatus.READY) {
                        DraftReadyUtilityActions(
                            canRetry = state.canRetry,
                            onRetry = onRetry,
                            onOpenEvidence = onOpenEvidence,
                            canCopy = state.body.isNotBlank(),
                            onCopy = {
                                clipboardManager.setText(
                                    AnnotatedString(
                                        listOf(state.subject, state.body)
                                            .filter(String::isNotBlank)
                                            .joinToString("\n\n"),
                                    ),
                                )
                            },
                        )
                        DraftReadyFooter(
                            canOpenExternalDraft = state.body.isNotBlank() || state.subject.isNotBlank(),
                            onOpenExternalDraft = openExternalDraft,
                            onDismiss = onDismiss,
                        )
                    } else {
                        DraftFallbackFooter(
                            canRetry = state.canRetry,
                            onRetry = onRetry,
                            onDismiss = onDismiss,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun draftSheetLabel(draftKind: String?): String =
    when (draftKind?.lowercase()) {
        "reply" -> stringResource(R.string.person_action_draft_sheet_label_reply)
        "follow_up",
        "reconnect_person",
        -> stringResource(R.string.person_action_draft_sheet_label_follow_up)
        else -> stringResource(R.string.person_action_draft_sheet_label)
    }

@Composable
private fun draftHeaderMeta(state: PersonActionDraftSheetUiState): String? {
    val recipient = state.recipientLabel?.takeIf { it.isNotBlank() }
    if (recipient != null) {
        return stringResource(R.string.person_action_draft_header_recipient_fmt, recipient)
    }
    return state.provenanceLabels
        .takeIf { it.isNotEmpty() }
        ?.distinct()
        ?.joinToString(" · ")
}

@Composable
private fun draftReviewNotice(state: PersonActionDraftSheetUiState): String {
    val contextLabel = state.provenanceLabels
        .mapNotNull { it.takeIf(String::isNotBlank) }
        .distinct()
        .firstOrNull()
    return if (contextLabel != null) {
        stringResource(R.string.person_action_draft_review_notice_context_fmt, contextLabel)
    } else {
        stringResource(R.string.person_action_draft_review_notice)
    }
}

@Composable
private fun DraftGrabHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(top = 2.dp, bottom = 4.dp)
            .width(38.dp)
            .height(5.dp)
            .background(DraftSheetGrab, RoundedCornerShape(3.dp)),
    )
}

@Composable
private fun DraftRecipientRow(
    recipientLabel: String?,
    modifier: Modifier = Modifier,
) {
    val label = recipientLabel?.takeIf { it.isNotBlank() } ?: return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.person_action_draft_recipient_label),
            style = MaterialTheme.typography.labelSmall,
            color = DraftSheetFaintText,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = DraftSheetLineSoft,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = DraftSheetInkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DraftBodyEditor(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.33.sp,
            ),
            color = DraftSheetFaintText,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(DraftTextareaHeight)
                .border(1.dp, DraftSheetLine, DraftTextareaShape)
                .padding(DraftTextareaPadding)
                .testTag("person-action-draft-body-input"),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = DraftSheetInkSecondary,
                fontSize = 13.5.sp,
                lineHeight = 22.275.sp,
            ),
            singleLine = false,
            cursorBrush = SolidColor(DraftSheetPrimary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Default,
            ),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxSize()) {
                    if (value.isBlank()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = DraftSheetFaintText,
                                fontSize = 13.5.sp,
                                lineHeight = 22.275.sp,
                            ),
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun DraftReadyUtilityActions(
    canRetry: Boolean,
    onRetry: () -> Unit,
    onOpenEvidence: (() -> Unit)?,
    canCopy: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!canRetry && onOpenEvidence == null && !canCopy) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canRetry) {
            DraftUtilityButton(
                text = stringResource(R.string.person_action_draft_retry),
                onClick = onRetry,
                modifier = Modifier.testTag("person-action-draft-retry"),
            )
        }
        if (onOpenEvidence != null) {
            DraftUtilityButton(
                text = stringResource(R.string.commitment_action_evidence),
                onClick = onOpenEvidence,
                modifier = Modifier.testTag("person-action-draft-evidence"),
            )
        }
        DraftUtilityButton(
            text = stringResource(R.string.person_action_draft_copy),
            onClick = onCopy,
            enabled = canCopy,
            modifier = Modifier.testTag("person-action-draft-copy"),
        )
    }
}

@Composable
private fun DraftUtilityButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = DraftUtilityButtonMinHeight),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = DraftSheetMutedText,
            disabledContentColor = DraftSheetFaintText,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DraftReadyFooter(
    canOpenExternalDraft: Boolean,
    onOpenExternalDraft: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DraftFooterButton(
            text = stringResource(R.string.commitment_action_evidence_close),
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
        )
        DraftFooterButton(
            text = stringResource(R.string.person_action_draft_open_mail),
            onClick = onOpenExternalDraft,
            enabled = canOpenExternalDraft,
            primary = true,
            modifier = Modifier
                .weight(1.6f)
                .testTag("person-action-draft-open-mail"),
        )
    }
}

@Composable
private fun DraftFooterButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val shape = RoundedCornerShape(13.dp)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(48.dp)
            .then(
                if (primary) {
                    Modifier
                } else {
                    Modifier.border(1.dp, DraftSheetLine, shape)
                },
            ),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) DraftSheetPrimary else Color.White,
            contentColor = if (primary) Color.White else DraftSheetInkSecondary,
            disabledContainerColor = if (primary) DraftSheetPrimary else Color.White,
            disabledContentColor = if (primary) Color.White else DraftSheetInkSecondary,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DraftFallbackFooter(
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canRetry) {
            BecalmButton(
                text = stringResource(R.string.person_action_draft_retry),
                onClick = onRetry,
                variant = BecalmButtonVariant.Secondary,
                modifier = Modifier.testTag("person-action-draft-retry"),
            )
        }
        BecalmButton(
            text = stringResource(R.string.commitment_action_evidence_close),
            onClick = onDismiss,
            variant = BecalmButtonVariant.Text,
        )
    }
}

private val DraftSheetScrim = Color(0x57121214)
private val DraftSheetSurface = Color(0xFFFFFFFF)
private val DraftSheetLine = Color(0xFFE5E9F0)
private val DraftSheetLineSoft = Color(0xFFF0F3F8)
private val DraftSheetGrab = Color(0xFFE2E2E4)
private val DraftSheetPrimary = Color(0xFF1E2A4A)
private val DraftSheetInk = Color(0xFF1A1F2E)
private val DraftSheetInkSecondary = Color(0xFF3A4358)
private val DraftSheetMutedText = Color(0xFF5A6478)
private val DraftSheetFaintText = Color(0xFF9AA4BC)
private val DraftUtilityButtonMinHeight = 44.dp
private val DraftTextareaHeight = 188.dp
private val DraftTextareaPadding = PaddingValues(13.dp)
private val DraftTextareaShape = RoundedCornerShape(13.dp)
private val DraftEmailRegex = Regex(
    pattern = "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
    option = RegexOption.IGNORE_CASE,
)

private fun Context.openDraftInExternalMailClient(state: PersonActionDraftSheetUiState) {
    val recipientEmail = state.recipientLabel?.let { DraftEmailRegex.find(it)?.value }
    val subject = state.subject.takeIf { it.isNotBlank() }
    val body = state.body.takeIf { it.isNotBlank() }
    val mailIntent = Intent(
        Intent.ACTION_SENDTO,
        Uri.parse("mailto:${recipientEmail?.let(Uri::encode).orEmpty()}"),
    )
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .apply {
            recipientEmail?.let { putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            body?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }

    runCatching {
        startActivity(mailIntent)
    }.onFailure {
        openDraftShareFallback(recipientEmail = recipientEmail, subject = subject, body = body)
    }
}

private fun Context.openDraftShareFallback(
    recipientEmail: String?,
    subject: String?,
    body: String?,
) {
    val shareIntent = Intent(Intent.ACTION_SEND)
        .setType("message/rfc822")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .apply {
            recipientEmail?.let { putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            body?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }
    runCatching {
        startActivity(
            Intent.createChooser(
                shareIntent,
                getString(R.string.person_action_draft_open_mail_chooser),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

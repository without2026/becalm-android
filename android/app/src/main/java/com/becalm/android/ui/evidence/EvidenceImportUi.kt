package com.becalm.android.ui.evidence

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.becalm.android.R

@Composable
public fun EvidenceImportFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.testTag("evidence-import-fab"),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 2.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = stringResource(R.string.evidence_import_fab_content_desc),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun EvidenceImportSheet(
    onDismiss: () -> Unit,
    onMessageScreenshotImport: () -> Unit,
    onMeetingAudioImport: () -> Unit,
    onMeetingTranscriptImport: () -> Unit,
    onManualTextImport: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.evidence_import_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            EvidenceImportActionRow(
                icon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                title = stringResource(R.string.evidence_import_message_screenshot),
                subtitle = stringResource(R.string.evidence_import_message_screenshot_subtitle),
                onClick = onMessageScreenshotImport,
                testTag = "evidence-import-message-screenshot",
            )
            EvidenceImportActionRow(
                icon = { Icon(Icons.Outlined.Mic, contentDescription = null) },
                title = stringResource(R.string.evidence_import_meeting_audio),
                subtitle = stringResource(R.string.evidence_import_meeting_audio_subtitle),
                onClick = onMeetingAudioImport,
                testTag = "evidence-import-meeting-audio",
            )
            EvidenceImportActionRow(
                icon = { Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = null) },
                title = stringResource(R.string.evidence_import_meeting_transcript),
                subtitle = stringResource(R.string.evidence_import_meeting_transcript_subtitle),
                onClick = onMeetingTranscriptImport,
                testTag = "evidence-import-meeting-transcript",
            )
            EvidenceImportActionRow(
                icon = { Icon(Icons.Outlined.EditNote, contentDescription = null) },
                title = stringResource(R.string.evidence_import_manual_text),
                subtitle = stringResource(R.string.evidence_import_manual_text_subtitle),
                onClick = onManualTextImport,
                testTag = "evidence-import-manual-text",
            )
        }
    }
}

@Composable
public fun ManualTextEvidenceDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.evidence_import_manual_text_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(text = stringResource(R.string.evidence_import_manual_text_placeholder)) },
                minLines = 5,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("evidence-import-manual-text-input"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(text) },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("evidence-import-manual-text-save"),
            ) {
                Text(text = stringResource(R.string.evidence_import_manual_text_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.evidence_import_manual_text_cancel))
            }
        },
    )
}

@Composable
private fun EvidenceImportActionRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(MaterialTheme.shapes.medium),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .testTag(testTag)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

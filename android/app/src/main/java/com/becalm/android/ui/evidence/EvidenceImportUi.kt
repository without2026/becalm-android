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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.becalm.android.R

@Stable
public class EvidenceImportSheetController internal constructor(
    private val isSheetVisibleProvider: () -> Boolean,
    private val setSheetVisible: (Boolean) -> Unit,
) {
    public val isSheetVisible: Boolean
        get() = isSheetVisibleProvider()

    public fun openSheet() {
        setSheetVisible(true)
    }

    public fun dismissSheet() {
        setSheetVisible(false)
    }
}

@Composable
public fun rememberEvidenceImportSheetController(): EvidenceImportSheetController {
    val showImportSheet = rememberSaveable { mutableStateOf(false) }
    return remember {
        EvidenceImportSheetController(
            isSheetVisibleProvider = { showImportSheet.value },
            setSheetVisible = { showImportSheet.value = it },
        )
    }
}

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

@Composable
public fun EvidenceImportSheetHost(
    controller: EvidenceImportSheetController,
    onMessageScreenshotImport: () -> Unit,
    onMeetingAudioImport: () -> Unit,
) {
    if (controller.isSheetVisible) {
        EvidenceImportSheet(
            onDismiss = controller::dismissSheet,
            onMessageScreenshotImport = {
                controller.dismissSheet()
                onMessageScreenshotImport()
            },
            onMeetingAudioImport = {
                controller.dismissSheet()
                onMeetingAudioImport()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun EvidenceImportSheet(
    onDismiss: () -> Unit,
    onMessageScreenshotImport: () -> Unit,
    onMeetingAudioImport: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("evidence-import-sheet-back"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.evidence_import_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
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
        }
    }
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

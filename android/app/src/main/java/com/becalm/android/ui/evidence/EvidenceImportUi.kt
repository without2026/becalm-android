package com.becalm.android.ui.evidence

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.becalm.android.R
import com.becalm.android.data.remote.dto.MeetingSpeakerPreviewDto
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.theme.becalmColors
import kotlinx.coroutines.delay

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
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp, pressedElevation = 1.dp),
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
    state: EvidenceImportUiState = EvidenceImportUiState(),
    onMeetingSelfSpeakerSelected: (String) -> Unit = {},
    onMeetingCounterpartySpeakerSelected: (String) -> Unit = {},
    onMeetingSpeakerReviewConfirmed: () -> Unit = {},
    onMeetingSpeakerReviewCancelled: () -> Unit = {},
    onMeetingSpeakerReviewAction: () -> Unit = {},
    onMeetingPreviewLoadingCancelled: () -> Unit = {},
    onRetryFailedImports: (() -> Unit)? = null,
    onReviewRequiredClick: (() -> Unit)? = null,
    onStatusDetailsClick: (() -> Unit)? = null,
    onConsentRequiredClick: (() -> Unit)? = null,
) {
    EvidenceImportStatusPopup(
        surface = state.statusSurface,
        onAction = { action ->
            when (action) {
                EvidenceImportStatusAction.DETAILS -> onStatusDetailsClick?.invoke()
                EvidenceImportStatusAction.RETRY_FAILED -> onRetryFailedImports?.invoke()
                EvidenceImportStatusAction.CONSENT_SETTINGS ->
                    (onConsentRequiredClick ?: onStatusDetailsClick)?.invoke()
                EvidenceImportStatusAction.REVIEW -> onReviewRequiredClick?.invoke()
                EvidenceImportStatusAction.MEETING_SPEAKER_REVIEW -> onMeetingSpeakerReviewAction()
            }
        },
    )
    if (state.statusSurface == null && state.statusMessage != null) {
        EvidenceImportStatusPopup(
            surface = EvidenceImportStatusSurfaceUi(
                phase = EvidenceImportStatusPhase.PROCESSING,
                title = state.statusMessage,
                body = state.statusMessage,
                primaryAction = EvidenceImportStatusAction.DETAILS,
                primaryActionLabel = UiMessage.resource(R.string.evidence_import_status_action_details),
                transitionKey = "legacy:${state.statusMessage.resId}",
            ),
            onAction = { onStatusDetailsClick?.invoke() },
        )
    }
    state.meetingReview?.let { review ->
        MeetingSpeakerReviewSheet(
            review = review,
            onSelfSelect = onMeetingSelfSpeakerSelected,
            onCounterpartySelect = onMeetingCounterpartySpeakerSelected,
            onConfirm = onMeetingSpeakerReviewConfirmed,
            onDismiss = onMeetingSpeakerReviewCancelled,
        )
    }
    if (state.loadingMessage != null) {
        EvidenceImportLoadingSheet(
            message = uiMessageStringResource(state.loadingMessage),
            onDismiss = onMeetingPreviewLoadingCancelled,
        )
    }
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

@Composable
private fun EvidenceImportStatusPopup(
    surface: EvidenceImportStatusSurfaceUi?,
    onAction: (EvidenceImportStatusAction) -> Unit,
) {
    var displayedSurface by remember { mutableStateOf<EvidenceImportStatusSurfaceUi?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(surface?.transitionKey) {
        if (surface == null) {
            visible = false
            delay(StatusSurfaceTransitionMs.toLong())
            displayedSurface = null
            return@LaunchedEffect
        }
        if (displayedSurface != null && displayedSurface?.transitionKey != surface.transitionKey) {
            visible = false
            delay(StatusSurfaceTransitionMs.toLong())
        }
        displayedSurface = surface
        visible = true
    }

    val current = displayedSurface ?: return
    Popup(alignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(StatusSurfaceTransitionMs)) +
                slideInVertically(
                    animationSpec = tween(StatusSurfaceTransitionMs),
                    initialOffsetY = { height -> height / 2 },
                ),
            exit = fadeOut(animationSpec = tween(StatusSurfaceTransitionMs)) +
                slideOutVertically(
                    animationSpec = tween(StatusSurfaceTransitionMs),
                    targetOffsetY = { height -> height / 2 },
                ),
        ) {
            EvidenceImportStatusSurface(
                surface = current,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun EvidenceImportStatusSurface(
    surface: EvidenceImportStatusSurfaceUi,
    onAction: (EvidenceImportStatusAction) -> Unit,
) {
    val title = uiMessageStringResource(surface.title)
    val body = uiMessageStringResource(surface.body)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp)
            .testTag("evidence-import-status")
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "$title. $body"
            },
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BecalmButton(
                    text = uiMessageStringResource(surface.primaryActionLabel),
                    onClick = { onAction(surface.primaryAction) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(surface.primaryAction.testTag(primary = true)),
                )
                if (surface.secondaryAction != null && surface.secondaryActionLabel != null) {
                    BecalmButton(
                        text = uiMessageStringResource(surface.secondaryActionLabel),
                        onClick = { onAction(surface.secondaryAction) },
                        variant = BecalmButtonVariant.Secondary,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(surface.secondaryAction.testTag(primary = false)),
                    )
                }
            }
        }
    }
}

private fun EvidenceImportStatusAction.testTag(primary: Boolean): String =
    when (this) {
        EvidenceImportStatusAction.CONSENT_SETTINGS -> "evidence-import-consent-action"
        EvidenceImportStatusAction.REVIEW -> "evidence-import-review-action"
        EvidenceImportStatusAction.MEETING_SPEAKER_REVIEW -> "evidence-import-speaker-review-action"
        EvidenceImportStatusAction.RETRY_FAILED -> "evidence-import-retry-failed-action"
        EvidenceImportStatusAction.DETAILS -> if (primary) {
            "evidence-import-status-primary"
        } else {
            "evidence-import-status-secondary"
        }
    }

private const val StatusSurfaceTransitionMs: Int = 140

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EvidenceImportLoadingSheet(
    message: String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(text = stringResource(R.string.action_cancel))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeetingSpeakerReviewSheet(
    review: MeetingSpeakerReviewUiState,
    onSelfSelect: (String) -> Unit,
    onCounterpartySelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isCall = review.sourceType == SourceType.CALL_RECORDING
    var localSelectedSelfSpeakerId by remember(review.rawEventId, review.speakerPreviewId) {
        mutableStateOf(review.selectedSpeakerId)
    }
    var localSelectedCounterpartySpeakerId by remember(review.rawEventId, review.speakerPreviewId) {
        mutableStateOf(review.selectedCounterpartySpeakerId)
    }
    val selectedSelfSpeakerId = review.selectedSpeakerId ?: localSelectedSelfSpeakerId
    val selectedCounterpartySpeakerId = review.selectedCounterpartySpeakerId ?: localSelectedCounterpartySpeakerId
    val canConfirm = if (isCall) {
        selectedSelfSpeakerId != null &&
            selectedCounterpartySpeakerId != null &&
            selectedSelfSpeakerId != selectedCounterpartySpeakerId
    } else {
        selectedSelfSpeakerId != null
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text(
                text = stringResource(
                    if (isCall) {
                        R.string.evidence_import_call_review_title
                    } else {
                        R.string.evidence_import_meeting_review_title
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(
                    if (isCall) {
                        R.string.evidence_import_call_review_body
                    } else {
                        R.string.evidence_import_meeting_review_body
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (isCall) {
                Text(
                    text = stringResource(R.string.evidence_import_call_review_self_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                review.speakers.forEach { speaker ->
                    SpeakerOptionRow(
                        speaker = speaker,
                        selected = selectedSelfSpeakerId == speaker.speakerId,
                        testTag = "meeting-speaker-self-${speaker.speakerId}",
                        onClick = {
                            localSelectedSelfSpeakerId = speaker.speakerId
                            val inferredCounterparty = review.speakers
                                .filterNot { it.speakerId == speaker.speakerId }
                                .singleOrNull()
                                ?.speakerId
                            localSelectedCounterpartySpeakerId = when {
                                inferredCounterparty != null -> inferredCounterparty
                                localSelectedCounterpartySpeakerId == speaker.speakerId -> null
                                else -> localSelectedCounterpartySpeakerId
                            }
                            onSelfSelect(speaker.speakerId)
                        },
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.evidence_import_call_review_counterparty_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                review.speakers.forEach { speaker ->
                    SpeakerOptionRow(
                        speaker = speaker,
                        selected = selectedCounterpartySpeakerId == speaker.speakerId,
                        enabled = selectedSelfSpeakerId != speaker.speakerId,
                        testTag = "meeting-speaker-counterparty-${speaker.speakerId}",
                        onClick = {
                            localSelectedCounterpartySpeakerId = speaker.speakerId
                            onCounterpartySelect(speaker.speakerId)
                        },
                    )
                }
                if (selectedSelfSpeakerId != null && selectedCounterpartySpeakerId == null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.evidence_import_call_review_counterparty_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                review.speakers.forEach { speaker ->
                    SpeakerOptionRow(
                        speaker = speaker,
                        selected = selectedSelfSpeakerId == speaker.speakerId,
                        testTag = "meeting-speaker-${speaker.speakerId}",
                        onClick = {
                            localSelectedSelfSpeakerId = speaker.speakerId
                            onSelfSelect(speaker.speakerId)
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                BecalmButton(
                    text = stringResource(R.string.evidence_import_meeting_review_cancel),
                    onClick = onDismiss,
                    variant = BecalmButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(10.dp))
                BecalmButton(
                    text = stringResource(
                        if (isCall) {
                            R.string.evidence_import_call_review_confirm
                        } else {
                            R.string.evidence_import_meeting_review_confirm
                        },
                    ),
                    onClick = onConfirm,
                    enabled = canConfirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SpeakerOptionRow(
    speaker: MeetingSpeakerPreviewDto,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(MaterialTheme.shapes.medium),
        color = MaterialTheme.becalmColors.glassPanelFill,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.becalmColors.glassBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .testTag(testTag)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                enabled = enabled,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.evidence_import_meeting_review_speaker_label,
                        speaker.speakerId,
                        speaker.totalSeconds.toInt(),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                speaker.sampleTexts.take(2).forEach { sample ->
                    Text(
                        text = sample,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
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
        color = MaterialTheme.becalmColors.glassPanelFill,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.becalmColors.glassBorder),
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

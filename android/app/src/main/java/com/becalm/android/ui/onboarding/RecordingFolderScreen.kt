package com.becalm.android.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.navigation.BecalmNavigationDefaults
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.navigation.navigateAfterSourceReconnectOr
import com.becalm.android.ui.navigation.sourceReconnectTargetSourceType
import com.becalm.android.ui.theme.BecalmTheme

/**
 * Onboarding step: recording path / READ_MEDIA_AUDIO.
 *
 * Shows a PIPA-compliant rationale card before launching the permission request.
 * "Use this folder" first triggers the OS audio permission dialog, then persists BeCalm's
 * app-owned MediaStore path preset. No OS folder picker is launched.
 *
 * spec: ONB-002, ONB-003
 *
 * Primary VM: [OnboardingViewModel]
 * Navigation entry: [BecalmRoute.OnboardingRecordingFolder]
 * Navigation exit: source reconnect return route when present, otherwise authenticated home.
 */
@Composable
public fun RecordingFolderScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel? = null,
    detectionOverride: RecordingFolderDetection? = null,
    audioPermissionOverride: String? = null,
    onGrantFlow: (() -> Unit)? = null,
    onSkipFlow: (() -> Unit)? = null,
) {
    val onboardingViewModel = if (onGrantFlow == null || onSkipFlow == null) {
        viewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<OnboardingViewModel>()
    } else {
        viewModel
    }
    val detection by rememberRecordingFolderDetection(detectionOverride)
    val reconnectTargetSourceType = navController.sourceReconnectTargetSourceType()
    val navigateAfterGrant = {
        navController.navigateAfterSourceReconnectOr(BecalmNavigationDefaults.authenticatedHomeRoute)
    }
    val navigateAfterSkip = {
        navController.navigateAfterSourceReconnectOr(BecalmNavigationDefaults.authenticatedHomeRoute)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val uiState = if (onboardingViewModel != null) {
        val collectedState by onboardingViewModel.uiState.collectAsStateWithLifecycle()
        collectedState
    } else {
        null
    }
    val errorMessage = uiState?.error?.let { uiMessageStringResource(it) }

    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(errorMessage)
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            requireNotNull(onboardingViewModel).onRecordingFolderPermissionResult(
                granted = false,
                targetSourceType = reconnectTargetSourceType,
            )
            navigateAfterSkip()
            return@rememberLauncherForActivityResult
        }

        requireNotNull(onboardingViewModel).onRecordingPathSelected(
            targetSourceType = reconnectTargetSourceType,
        )
        navigateAfterGrant()
    }

    // READ_MEDIA_AUDIO was introduced in Android 13 (API 33). On API 28–32 the
    // equivalent capability is covered by READ_EXTERNAL_STORAGE.
    val audioPermission = audioPermissionOverride ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    BecalmScaffold(
        title = stringResource(R.string.onb_recording_folder_title),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        RecordingFolderContent(
            displayPath = detection.displayPath,
            targetPath = recordingFolderTargetPath(reconnectTargetSourceType),
            sourceSpecific = reconnectTargetSourceType in RECORDING_SOURCE_TYPES,
            voiceFolderDetected = detection.voiceFolderDetected,
            callFolderDetected = detection.callFolderDetected,
            meetingFolderDetected = detection.meetingFolderDetected,
            onGrant = onGrantFlow ?: { launcher.launch(audioPermission) },
            onSkip = onSkipFlow ?: {
                requireNotNull(onboardingViewModel).onSkipRecordingFolder(
                    targetSourceType = reconnectTargetSourceType,
                )
                navigateAfterSkip()
            },
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun RecordingFolderContent(
    displayPath: String,
    targetPath: String,
    sourceSpecific: Boolean,
    voiceFolderDetected: Boolean,
    callFolderDetected: Boolean,
    meetingFolderDetected: Boolean,
    onGrant: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SourceStoryLayout(
        icon = Icons.Outlined.Mic,
        headline = stringResource(R.string.onb_recording_folder_headline),
        body = stringResource(R.string.onb_recording_folder_body),
        modifier = modifier,
    ) {
        SourceStoryInfoPanel(
            title = stringResource(R.string.onb_intro_recordings_panel_title),
            body = if (sourceSpecific) {
                stringResource(R.string.onb_recording_folder_picker_instruction_source)
            } else {
                stringResource(R.string.onb_recording_folder_picker_instruction)
            },
            detailLines = listOf(
                if (sourceSpecific) {
                    stringResource(R.string.onb_recording_folder_picker_target_source_fmt, targetPath)
                } else {
                    stringResource(R.string.onb_recording_folder_picker_target)
                },
                stringResource(R.string.onb_recording_folder_detected_path_fmt, displayPath),
                stringResource(
                    R.string.onb_recording_folder_voice_status_fmt,
                    stringResource(R.string.onb_recording_folder_voice_path),
                    if (voiceFolderDetected) {
                        stringResource(R.string.onb_recording_folder_status_detected)
                    } else {
                        stringResource(R.string.onb_recording_folder_status_missing)
                    },
                ),
                stringResource(
                    R.string.onb_recording_folder_call_status_fmt,
                    stringResource(R.string.onb_recording_folder_call_path),
                    if (callFolderDetected) {
                        stringResource(R.string.onb_recording_folder_status_detected)
                    } else {
                        stringResource(R.string.onb_recording_folder_status_missing)
                    },
                ),
                stringResource(
                    R.string.onb_recording_folder_meeting_status_fmt,
                    stringResource(R.string.onb_recording_folder_meeting_path),
                    if (meetingFolderDetected) {
                        stringResource(R.string.onb_recording_folder_status_detected)
                    } else {
                        stringResource(R.string.onb_recording_folder_status_created_later)
                    },
                ),
            ),
        )
        Spacer(modifier = Modifier.height(8.dp))
        BecalmButton(
            text = stringResource(R.string.onb_recording_folder_use_selected),
            onClick = onGrant,
            variant = BecalmButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        BecalmButton(
            text = stringResource(R.string.action_skip),
            onClick = onSkip,
            variant = BecalmButtonVariant.Tertiary,
        )
    }
}

private val RECORDING_SOURCE_TYPES = setOf(SourceType.VOICE, SourceType.CALL_RECORDING, SourceType.MEETING)

@Composable
private fun recordingFolderTargetPath(sourceType: String?): String =
    when (sourceType) {
        SourceType.VOICE -> stringResource(R.string.onb_recording_folder_target_voice)
        SourceType.CALL_RECORDING -> stringResource(R.string.onb_recording_folder_target_call)
        SourceType.MEETING -> stringResource(R.string.onb_recording_folder_target_meeting)
        else -> stringResource(R.string.onb_recording_folder_target_common)
    }

@PreviewLightDark
@Composable
private fun PreviewRecordingFolderScreen() {
    BecalmTheme {
        RecordingFolderContent(
            displayPath = "Recordings",
            targetPath = stringResource(R.string.onb_recording_folder_target_common),
            sourceSpecific = false,
            voiceFolderDetected = true,
            callFolderDetected = false,
            meetingFolderDetected = false,
            onGrant = {},
            onSkip = {},
        )
    }
}

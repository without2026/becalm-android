package com.becalm.android.ui.evidence

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.becalm.android.domain.meeting.MeetingImportFilePolicy
import com.becalm.android.ui.sources.MeetingOpenDocumentContract
import com.becalm.android.ui.sources.MeetingOpenDocumentRequest

public data class EvidenceImportActions(
    val openMessageScreenshotPicker: () -> Unit,
    val openMeetingAudioPicker: () -> Unit,
    val openMeetingTranscriptPicker: () -> Unit,
    val submitManualText: (String) -> Unit,
)

@Composable
public fun rememberEvidenceImportActions(
    viewModel: EvidenceImportViewModel,
): EvidenceImportActions {
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.onMessageScreenshotSelected(uri)
    }
    val audioPicker = rememberLauncherForActivityResult(MeetingOpenDocumentContract()) { uri ->
        viewModel.onMeetingAudioSelected(uri)
    }
    val transcriptPicker = rememberLauncherForActivityResult(MeetingOpenDocumentContract()) { uri ->
        viewModel.onMeetingTranscriptSelected(uri)
    }
    return remember(viewModel, imagePicker, audioPicker, transcriptPicker) {
        EvidenceImportActions(
            openMessageScreenshotPicker = {
                imagePicker.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
            },
            openMeetingAudioPicker = {
                audioPicker.launch(
                    MeetingOpenDocumentRequest(
                        mimeTypes = MeetingImportFilePolicy.AUDIO_MIME_TYPES,
                        initialUri = null,
                    ),
                )
            },
            openMeetingTranscriptPicker = {
                transcriptPicker.launch(
                    MeetingOpenDocumentRequest(
                        mimeTypes = MeetingImportFilePolicy.TRANSCRIPT_MIME_TYPES,
                        initialUri = null,
                    ),
                )
            },
            submitManualText = viewModel::onManualTextSubmitted,
        )
    }
}

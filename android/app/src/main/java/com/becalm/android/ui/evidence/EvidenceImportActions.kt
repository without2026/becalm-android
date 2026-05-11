package com.becalm.android.ui.evidence

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.becalm.android.domain.meeting.MeetingImportFilePolicy
import com.becalm.android.ui.sources.MeetingOpenDocumentContract
import com.becalm.android.ui.sources.MeetingOpenDocumentRequest

public data class EvidenceImportActions(
    val openMessageScreenshotPicker: () -> Unit,
    val openMeetingAudioPicker: () -> Unit,
)

public object EvidenceImportPickerRequests {
    public fun messageScreenshot(): PickVisualMediaRequest =
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    public fun meetingAudio(): MeetingOpenDocumentRequest =
        MeetingOpenDocumentRequest(
            mimeTypes = MeetingImportFilePolicy.AUDIO_MIME_TYPES,
            initialUri = null,
        )

}

@Composable
public fun rememberEvidenceImportActions(
    viewModel: EvidenceImportViewModel,
): EvidenceImportActions {
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.onMessageScreenshotSelected(uri)
    }
    val audioPicker = rememberLauncherForActivityResult(MeetingOpenDocumentContract()) { uri ->
        viewModel.onMeetingAudioSelected(uri)
    }
    return remember(viewModel, imagePicker, audioPicker) {
        EvidenceImportActions(
            openMessageScreenshotPicker = {
                imagePicker.launch(EvidenceImportPickerRequests.messageScreenshot())
            },
            openMeetingAudioPicker = {
                audioPicker.launch(EvidenceImportPickerRequests.meetingAudio())
            },
        )
    }
}

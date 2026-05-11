package com.becalm.android.ui.evidence

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.remote.dto.MeetingSpeakerPreviewDto
import com.becalm.android.data.repository.MeetingSpeakerReviewContext
import com.becalm.android.data.repository.SourceImportRepository
import com.becalm.android.ui.components.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public data class EvidenceImportUiState(
    val message: UiMessage? = null,
    val loadingMessage: UiMessage? = null,
    val meetingReview: MeetingSpeakerReviewUiState? = null,
)

public data class MeetingSpeakerReviewUiState(
    val audioUri: Uri,
    val speakerPreviewId: String,
    val speakers: List<MeetingSpeakerPreviewDto>,
    val selectedSelfSpeakerId: String? = speakers.firstOrNull()?.speakerId,
)

@HiltViewModel
public class EvidenceImportViewModel @Inject constructor(
    private val sourceImportRepository: SourceImportRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(EvidenceImportUiState())
    public val state: StateFlow<EvidenceImportUiState> = _state.asStateFlow()

    public fun onMessageScreenshotSelected(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _state.value = when (sourceImportRepository.importMessageScreenshot(uri)) {
                is BecalmResult.Success -> EvidenceImportUiState(UiMessage.resource(R.string.evidence_import_success))
                is BecalmResult.Failure -> EvidenceImportUiState(UiMessage.resource(R.string.evidence_import_error_message_screenshot))
            }
        }
    }

    public fun onMeetingAudioSelected(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _state.value = EvidenceImportUiState(loadingMessage = UiMessage.resource(R.string.evidence_import_meeting_preview_loading))
            _state.value = when (val preview = sourceImportRepository.previewMeetingAudioSpeakers(uri)) {
                is BecalmResult.Success -> EvidenceImportUiState(
                    meetingReview = MeetingSpeakerReviewUiState(
                        audioUri = uri,
                        speakerPreviewId = preview.value.speakerPreviewId,
                        speakers = preview.value.speakers,
                    ),
                )
                is BecalmResult.Failure -> EvidenceImportUiState(UiMessage.resource(R.string.evidence_import_meeting_preview_failed))
            }
        }
    }

    public fun onMeetingSelfSpeakerSelected(speakerId: String) {
        val review = _state.value.meetingReview ?: return
        _state.value = _state.value.copy(
            meetingReview = review.copy(selectedSelfSpeakerId = speakerId),
        )
    }

    public fun onMeetingSpeakerReviewCancelled() {
        _state.value = EvidenceImportUiState()
    }

    public fun onMeetingSpeakerReviewConfirmed() {
        val review = _state.value.meetingReview ?: return
        val selfSpeakerId = review.selectedSelfSpeakerId ?: return
        viewModelScope.launch {
            _state.value = EvidenceImportUiState(loadingMessage = UiMessage.resource(R.string.evidence_import_meeting_preview_loading))
            val context = MeetingSpeakerReviewContext(
                selfSpeakerId = selfSpeakerId,
                speakerMappingsJson = speakerMappingsJson(review.speakers, selfSpeakerId),
                speakerPreviewId = review.speakerPreviewId,
            )
            _state.value = when (sourceImportRepository.importMeetingAudio(review.audioUri, context)) {
                is BecalmResult.Success -> EvidenceImportUiState(UiMessage.resource(R.string.evidence_import_success))
                is BecalmResult.Failure -> EvidenceImportUiState(UiMessage.resource(R.string.source_detail_error_meeting_audio_import_failed))
            }
        }
    }

    public fun onMessageShown() {
        if (_state.value.meetingReview == null) {
            _state.value = EvidenceImportUiState()
        } else {
            _state.value = _state.value.copy(message = null, loadingMessage = null)
        }
    }

    private fun speakerMappingsJson(speakers: List<MeetingSpeakerPreviewDto>, selfSpeakerId: String): String =
        speakers.joinToString(prefix = "[", postfix = "]") { speaker ->
            val relation = if (speaker.speakerId == selfSpeakerId) "self" else "participant"
            val confirmed = if (speaker.speakerId == selfSpeakerId) "true" else "false"
            """{"speaker_id":"${speaker.speakerId}","display_name":"${speaker.speakerId}","relation_to_user":"$relation","confidence":0.0,"confirmed_by_user":$confirmed}"""
        }
}

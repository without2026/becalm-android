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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

public data class EvidenceImportUiState(
    val message: UiMessage? = null,
    val loadingMessage: UiMessage? = null,
    val statusMessage: UiMessage? = null,
    val meetingReview: MeetingSpeakerReviewUiState? = null,
)

public data class MeetingSpeakerReviewUiState(
    val audioUri: Uri,
    val speakerPreviewId: String,
    val speakers: List<MeetingSpeakerPreviewDto>,
    val selectedSelfSpeakerId: String? = speakers.firstOrNull()?.speakerId,
)

private data class EvidenceImportTransientState(
    val message: UiMessage? = null,
    val loadingMessage: UiMessage? = null,
    val meetingReview: MeetingSpeakerReviewUiState? = null,
)

@HiltViewModel
public class EvidenceImportViewModel @Inject constructor(
    private val sourceImportRepository: SourceImportRepository,
    statusProjectionPort: EvidenceImportStatusProjectionPort,
) : ViewModel() {
    private val transientState = MutableStateFlow(EvidenceImportTransientState())
    public val state: StateFlow<EvidenceImportUiState> =
        combine(transientState, statusProjectionPort.observeStatus()) { transient, persistentStatus ->
            EvidenceImportUiState(
                message = transient.message,
                loadingMessage = transient.loadingMessage,
                statusMessage = persistentStatus.toUiMessage(),
                meetingReview = transient.meetingReview,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = EvidenceImportUiState(),
        )

    public fun onMessageScreenshotSelected(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            transientState.value = when (sourceImportRepository.importMessageScreenshot(uri)) {
                is BecalmResult.Success -> EvidenceImportUiState(
                    message = UiMessage.resource(R.string.evidence_import_success),
                ).toTransient()
                is BecalmResult.Failure -> EvidenceImportTransientState(
                    message = UiMessage.resource(R.string.evidence_import_error_message_screenshot),
                )
            }
        }
    }

    public fun onMeetingAudioSelected(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            transientState.value = EvidenceImportTransientState(
                loadingMessage = UiMessage.resource(R.string.evidence_import_meeting_preview_loading),
            )
            transientState.value = when (val preview = sourceImportRepository.previewMeetingAudioSpeakers(uri)) {
                is BecalmResult.Success -> EvidenceImportTransientState(
                    meetingReview = MeetingSpeakerReviewUiState(
                        audioUri = uri,
                        speakerPreviewId = preview.value.speakerPreviewId,
                        speakers = preview.value.speakers,
                    ),
                )
                is BecalmResult.Failure -> EvidenceImportTransientState(
                    message = UiMessage.resource(R.string.evidence_import_meeting_preview_failed),
                )
            }
        }
    }

    public fun onMeetingSelfSpeakerSelected(speakerId: String) {
        val review = transientState.value.meetingReview ?: return
        transientState.value = transientState.value.copy(
            meetingReview = review.copy(selectedSelfSpeakerId = speakerId),
        )
    }

    public fun onMeetingSpeakerReviewCancelled() {
        transientState.value = EvidenceImportTransientState()
    }

    public fun onMeetingSpeakerReviewConfirmed() {
        val review = transientState.value.meetingReview ?: return
        val selfSpeakerId = review.selectedSelfSpeakerId ?: return
        viewModelScope.launch {
            transientState.value = EvidenceImportTransientState(
                loadingMessage = UiMessage.resource(R.string.evidence_import_meeting_preview_loading),
            )
            val context = MeetingSpeakerReviewContext(
                selfSpeakerId = selfSpeakerId,
                speakerMappingsJson = speakerMappingsJson(review.speakers, selfSpeakerId),
                speakerPreviewId = review.speakerPreviewId,
            )
            transientState.value = when (sourceImportRepository.importMeetingAudio(review.audioUri, context)) {
                is BecalmResult.Success -> EvidenceImportTransientState(
                    message = UiMessage.resource(R.string.evidence_import_success),
                )
                is BecalmResult.Failure -> EvidenceImportTransientState(
                    message = UiMessage.resource(R.string.source_detail_error_meeting_audio_import_failed),
                )
            }
        }
    }

    public fun onMessageShown() {
        transientState.value = transientState.value.copy(message = null, loadingMessage = null)
    }

    private fun EvidenceImportPersistentStatus.toUiMessage(): UiMessage? =
        when (this) {
            EvidenceImportPersistentStatus.NONE -> null
            EvidenceImportPersistentStatus.PROCESSING ->
                UiMessage.resource(R.string.evidence_import_status_processing)
            EvidenceImportPersistentStatus.REVIEW_REQUIRED ->
                UiMessage.resource(R.string.evidence_import_status_review_required)
        }

    private fun EvidenceImportUiState.toTransient(): EvidenceImportTransientState =
        EvidenceImportTransientState(
            message = message,
            loadingMessage = loadingMessage,
            meetingReview = meetingReview,
        )

    private fun speakerMappingsJson(speakers: List<MeetingSpeakerPreviewDto>, selfSpeakerId: String): String =
        speakers.joinToString(prefix = "[", postfix = "]") { speaker ->
            val relation = if (speaker.speakerId == selfSpeakerId) "self" else "participant"
            val confirmed = if (speaker.speakerId == selfSpeakerId) "true" else "false"
            """{"speaker_id":"${speaker.speakerId}","display_name":"${speaker.speakerId}","relation_to_user":"$relation","confidence":0.0,"confirmed_by_user":$confirmed}"""
        }
}

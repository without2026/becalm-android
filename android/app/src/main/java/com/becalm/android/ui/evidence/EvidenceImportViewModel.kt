package com.becalm.android.ui.evidence

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.analytics.NoopProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.remote.dto.MeetingSpeakerPreviewDto
import com.becalm.android.data.repository.MeetingSpeakerReviewContext
import com.becalm.android.data.repository.SourceImportRepository
import com.becalm.android.ui.components.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.datetime.Clock
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
    private val productAnalytics: ProductAnalyticsClient = NoopProductAnalyticsClient(),
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
                is BecalmResult.Success -> {
                    trackImportCompleted("message_screenshot")
                    EvidenceImportUiState(
                        message = UiMessage.resource(R.string.evidence_import_success),
                    ).toTransient()
                }
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
                speakerMappingsJson = MeetingSpeakerMappingsJson.encode(review.speakers, selfSpeakerId),
                speakerPreviewId = review.speakerPreviewId,
            )
            transientState.value = when (sourceImportRepository.importMeetingAudio(review.audioUri, context)) {
                is BecalmResult.Success -> {
                    trackImportCompleted("meeting")
                    EvidenceImportTransientState(
                        message = UiMessage.resource(R.string.evidence_import_success),
                    )
                }
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

    private fun trackImportCompleted(sourceType: String) {
        productAnalytics.track(
            ProductAnalyticsEvent(
                eventId = UUID.randomUUID().toString(),
                eventName = ProductAnalyticsEvents.EVIDENCE_IMPORT_COMPLETED,
                occurredAt = Clock.System.now(),
                properties = mapOf("source_type" to sourceType),
            ),
        )
    }
}

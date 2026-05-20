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
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.MeetingSpeakerReviewContext
import com.becalm.android.data.repository.SourceImportRepository
import com.becalm.android.ui.components.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

public data class EvidenceImportUiState(
    val message: UiMessage? = null,
    val loadingMessage: UiMessage? = null,
    val statusMessage: UiMessage? = null,
    val meetingReview: MeetingSpeakerReviewUiState? = null,
)

public data class MeetingSpeakerReviewUiState(
    val rawEventId: String,
    val sourceRef: String?,
    val sourceType: String = SourceType.MEETING,
    val speakerPreviewId: String,
    val speakers: List<MeetingSpeakerPreviewDto>,
    val selectedSpeakerId: String? = null,
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
    private var activeMeetingImportJob: Job? = null
    public val state: StateFlow<EvidenceImportUiState> =
        combine(
            transientState,
            statusProjectionPort.observeStatus(),
            sourceImportRepository.observeLatestMeetingSpeakerReview(),
        ) { transient, persistentStatus, latestMeetingReview ->
            EvidenceImportUiState(
                message = transient.message,
                loadingMessage = transient.loadingMessage,
                statusMessage = persistentStatus.toUiMessage(),
                meetingReview = transient.meetingReview ?: latestMeetingReview?.let { review ->
                    MeetingSpeakerReviewUiState(
                        rawEventId = review.rawEventId,
                        sourceRef = review.sourceRef,
                        sourceType = review.sourceType,
                        speakerPreviewId = review.speakerPreviewId,
                        speakers = review.speakers,
                    )
                },
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
        activeMeetingImportJob?.cancel()
        activeMeetingImportJob = viewModelScope.launch {
            transientState.value = when (sourceImportRepository.stageMeetingAudioForSpeakerReview(uri)) {
                is BecalmResult.Success -> {
                    trackImportCompleted(SourceType.MEETING)
                    EvidenceImportTransientState(
                        message = UiMessage.resource(R.string.evidence_import_meeting_preview_started),
                    )
                }
                is BecalmResult.Failure -> EvidenceImportTransientState(
                    message = UiMessage.resource(R.string.evidence_import_meeting_preview_failed),
                )
            }
        }
    }

    public fun onMeetingSelfSpeakerSelected(speakerId: String) {
        val review = state.value.meetingReview ?: return
        transientState.value = transientState.value.copy(
            meetingReview = review.copy(selectedSpeakerId = speakerId),
        )
        onMeetingSpeakerReviewConfirmed(speakerId)
    }

    public fun onMeetingSpeakerReviewCancelled() {
        activeMeetingImportJob?.cancel()
        activeMeetingImportJob = null
        transientState.value = EvidenceImportTransientState()
    }

    public fun onMeetingPreviewLoadingCancelled() {
        activeMeetingImportJob?.cancel()
        activeMeetingImportJob = null
        transientState.value = EvidenceImportTransientState()
    }

    public fun onMeetingSpeakerReviewConfirmed() {
        onMeetingSpeakerReviewConfirmed(transientState.value.meetingReview?.selectedSpeakerId)
    }

    private fun onMeetingSpeakerReviewConfirmed(selectedSpeakerId: String?) {
        val review = state.value.meetingReview ?: return
        val selectedSpeaker = selectedSpeakerId ?: return
        val selfSpeakerId = if (review.sourceType == SourceType.CALL_RECORDING) {
            inferCallSelfSpeakerId(review.speakers, selectedSpeaker)
        } else {
            selectedSpeaker
        }
        activeMeetingImportJob?.cancel()
        activeMeetingImportJob = viewModelScope.launch {
            val context = MeetingSpeakerReviewContext(
                selfSpeakerId = selfSpeakerId,
                speakerMappingsJson = if (review.sourceType == SourceType.CALL_RECORDING) {
                    MeetingSpeakerMappingsJson.encodeCallCounterparty(review.speakers, selectedSpeaker)
                } else {
                    MeetingSpeakerMappingsJson.encodeMeetingSelf(review.speakers, selfSpeakerId)
                },
                speakerPreviewId = review.speakerPreviewId,
            )
            transientState.value = when (val result = sourceImportRepository.confirmMeetingSpeakerReview(review.rawEventId, context)) {
                is BecalmResult.Success -> {
                    trackImportCompleted(review.sourceType)
                    EvidenceImportTransientState(
                        message = UiMessage.resource(
                            if (result.value.queuedExtraction) {
                                R.string.evidence_import_meeting_extract_started
                            } else {
                                R.string.evidence_import_meeting_preview_started
                            },
                        ),
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

    private fun inferCallSelfSpeakerId(
        speakers: List<MeetingSpeakerPreviewDto>,
        counterpartySpeakerId: String,
    ): String =
        speakers.firstOrNull { it.speakerId != counterpartySpeakerId }?.speakerId
            ?: counterpartySpeakerId
}

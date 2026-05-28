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
import com.becalm.android.domain.meeting.MeetingSpeakerMappingsJson
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
    val statusSurface: EvidenceImportStatusSurfaceUi? = null,
    val meetingReview: MeetingSpeakerReviewUiState? = null,
    val foregroundReviewRequestKey: String? = null,
)

public data class EvidenceImportStatusSurfaceUi(
    val phase: EvidenceImportStatusPhase,
    val title: UiMessage,
    val body: UiMessage,
    val primaryAction: EvidenceImportStatusAction,
    val primaryActionLabel: UiMessage,
    val secondaryAction: EvidenceImportStatusAction? = null,
    val secondaryActionLabel: UiMessage? = null,
    val transitionKey: String,
)

public enum class EvidenceImportStatusAction {
    DETAILS,
    RETRY_FAILED,
    CONSENT_SETTINGS,
    REVIEW,
    MEETING_SPEAKER_REVIEW,
}

public data class MeetingSpeakerReviewUiState(
    val rawEventId: String,
    val sourceRef: String?,
    val sourceType: String = SourceType.MEETING,
    val speakerPreviewId: String,
    val speakers: List<MeetingSpeakerPreviewDto>,
    val selectedSpeakerId: String? = null,
    val selectedCounterpartySpeakerId: String? = null,
)

private data class EvidenceImportTransientState(
    val message: UiMessage? = null,
    val loadingMessage: UiMessage? = null,
    val meetingReview: MeetingSpeakerReviewUiState? = null,
    val meetingReviewRequested: Boolean = false,
    val dismissedMeetingReviewRawEventId: String? = null,
    val foregroundImportKey: String? = null,
    val foregroundReviewConsumedKey: String? = null,
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
            val statusSurface = persistentStatus.toStatusSurface()
            val latestReview = latestMeetingReview
                ?.let { review ->
                    MeetingSpeakerReviewUiState(
                        rawEventId = review.rawEventId,
                        sourceRef = review.sourceRef,
                        sourceType = review.sourceType,
                        speakerPreviewId = review.speakerPreviewId,
                        speakers = review.speakers,
                    )
                }
            val visibleLatestReview = latestReview
                ?.takeIf { transient.meetingReviewRequested }
                ?.takeUnless { it.rawEventId == transient.dismissedMeetingReviewRawEventId }
            val foregroundReviewRequestKey = transient.foregroundImportKey
                ?.takeIf { persistentStatus.phase == EvidenceImportStatusPhase.REVIEW_REQUIRED }
                ?.takeIf { persistentStatus.personReviewRequiredCount > 0 }
                ?.takeIf { it != transient.foregroundReviewConsumedKey }
            EvidenceImportUiState(
                message = transient.message,
                loadingMessage = transient.loadingMessage,
                statusMessage = statusSurface?.title,
                statusSurface = statusSurface,
                meetingReview = transient.meetingReview ?: visibleLatestReview,
                foregroundReviewRequestKey = foregroundReviewRequestKey,
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
                    ).toTransient(foregroundImportKey = newForegroundImportKey())
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
        val inferredCounterparty = if (review.sourceType == SourceType.CALL_RECORDING) {
            review.selectedCounterpartySpeakerId ?: inferSingleOtherSpeakerId(review.speakers, speakerId)
        } else {
            review.selectedCounterpartySpeakerId
        }
        transientState.value = transientState.value.copy(
            meetingReview = review.copy(
                selectedSpeakerId = speakerId,
                selectedCounterpartySpeakerId = inferredCounterparty?.takeUnless { it == speakerId },
            ),
            meetingReviewRequested = true,
        )
    }

    public fun onMeetingCounterpartySpeakerSelected(speakerId: String) {
        val review = state.value.meetingReview ?: return
        transientState.value = transientState.value.copy(
            meetingReview = review.copy(selectedCounterpartySpeakerId = speakerId),
            meetingReviewRequested = true,
        )
    }

    public fun onMeetingSpeakerReviewCancelled() {
        activeMeetingImportJob?.cancel()
        activeMeetingImportJob = null
        transientState.value = EvidenceImportTransientState(
            meetingReviewRequested = false,
            dismissedMeetingReviewRawEventId = state.value.meetingReview?.rawEventId,
        )
    }

    public fun onMeetingSpeakerReviewAction() {
        transientState.value = transientState.value.copy(
            meetingReviewRequested = true,
            dismissedMeetingReviewRawEventId = null,
        )
    }

    public fun onMeetingPreviewLoadingCancelled() {
        activeMeetingImportJob?.cancel()
        activeMeetingImportJob = null
        transientState.value = EvidenceImportTransientState()
    }

    public fun onRetryFailedImports() {
        viewModelScope.launch {
            transientState.value = transientState.value.copy(
                message = null,
                loadingMessage = UiMessage.resource(R.string.evidence_import_retry_loading),
            )
            transientState.value = when (val result = sourceImportRepository.retryFailedEvidenceImports()) {
                is BecalmResult.Success -> EvidenceImportTransientState(
                    message = UiMessage.resource(R.string.evidence_import_retry_started, result.value.toString()),
                )
                is BecalmResult.Failure -> EvidenceImportTransientState(
                    message = UiMessage.resource(R.string.evidence_import_retry_unavailable),
                )
            }
        }
    }

    public fun onMeetingSpeakerReviewConfirmed() {
        onMeetingSpeakerReviewConfirmed(
            transientState.value.meetingReview?.selectedSpeakerId
                ?: state.value.meetingReview?.selectedSpeakerId,
        )
    }

    private fun onMeetingSpeakerReviewConfirmed(selectedSpeakerId: String?) {
        val review = transientState.value.meetingReview ?: state.value.meetingReview ?: return
        val selfSpeakerId = selectedSpeakerId ?: return
        val counterpartySpeakerId = if (review.sourceType == SourceType.CALL_RECORDING) {
            review.selectedCounterpartySpeakerId
                ?: inferSingleOtherSpeakerId(review.speakers, selfSpeakerId)
                ?: return
        } else {
            null
        }
        if (counterpartySpeakerId == selfSpeakerId) return
        val speakerMappingsJson = if (review.sourceType == SourceType.CALL_RECORDING) {
            val confirmedCounterpartySpeakerId = counterpartySpeakerId ?: return
            MeetingSpeakerMappingsJson.encodeCallSelfAndCounterparty(
                speakers = review.speakers,
                selfSpeakerId = selfSpeakerId,
                counterpartySpeakerId = confirmedCounterpartySpeakerId,
            )
        } else {
            MeetingSpeakerMappingsJson.encodeMeetingSelf(review.speakers, selfSpeakerId)
        }
        activeMeetingImportJob?.cancel()
        activeMeetingImportJob = viewModelScope.launch {
            val context = MeetingSpeakerReviewContext(
                selfSpeakerId = selfSpeakerId,
                speakerMappingsJson = speakerMappingsJson,
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
                        foregroundImportKey = newForegroundImportKey(),
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

    public fun onForegroundReviewOpened(requestKey: String) {
        transientState.value = transientState.value.copy(foregroundReviewConsumedKey = requestKey)
    }

    private fun EvidenceImportPersistentStatus.toStatusSurface(): EvidenceImportStatusSurfaceUi? {
        val processingCountLabel = processingCount.coerceAtLeast(1).toString()
        val consentRequiredCountLabel = consentRequiredCount.coerceAtLeast(1).toString()
        val reviewRequiredCountLabel = reviewRequiredCount.coerceAtLeast(1).toString()
        val failedCountLabel = failedCount.coerceAtLeast(1).toString()
        return when (phase) {
            EvidenceImportStatusPhase.NONE -> null
            EvidenceImportStatusPhase.PROCESSING -> EvidenceImportStatusSurfaceUi(
                phase = phase,
                title = UiMessage.resource(R.string.evidence_import_status_processing_title, processingCountLabel),
                body = UiMessage.resource(R.string.evidence_import_status_processing_body),
                primaryAction = EvidenceImportStatusAction.DETAILS,
                primaryActionLabel = UiMessage.resource(R.string.evidence_import_status_action_details),
                transitionKey = "processing:$processingCount",
            )
            EvidenceImportStatusPhase.LONG_RUNNING -> EvidenceImportStatusSurfaceUi(
                phase = phase,
                title = UiMessage.resource(R.string.evidence_import_status_long_running_title),
                body = UiMessage.resource(R.string.evidence_import_status_long_running_body),
                primaryAction = EvidenceImportStatusAction.DETAILS,
                primaryActionLabel = UiMessage.resource(R.string.evidence_import_status_action_details),
                transitionKey = "long_running:$processingCount",
            )
            EvidenceImportStatusPhase.CONSENT_REQUIRED -> EvidenceImportStatusSurfaceUi(
                phase = phase,
                title = UiMessage.resource(
                    R.string.evidence_import_status_consent_required_title,
                    consentRequiredCountLabel,
                ),
                body = UiMessage.resource(R.string.evidence_import_status_consent_required_body),
                primaryAction = EvidenceImportStatusAction.CONSENT_SETTINGS,
                primaryActionLabel = UiMessage.resource(R.string.evidence_import_status_action_consent),
                transitionKey = "consent:$consentRequiredCount",
            )
            EvidenceImportStatusPhase.REVIEW_REQUIRED -> EvidenceImportStatusSurfaceUi(
                phase = phase,
                title = UiMessage.resource(R.string.evidence_import_status_review_required_title, reviewRequiredCountLabel),
                body = UiMessage.resource(R.string.evidence_import_status_review_required_body),
                primaryAction = if (meetingReviewRequiredCount > 0) {
                    EvidenceImportStatusAction.MEETING_SPEAKER_REVIEW
                } else {
                    EvidenceImportStatusAction.REVIEW
                },
                primaryActionLabel = UiMessage.resource(
                    if (meetingReviewRequiredCount > 0) {
                        R.string.evidence_import_speaker_review_action
                    } else {
                        R.string.evidence_import_review_action
                    },
                ),
                secondaryAction = if (meetingReviewRequiredCount > 0 && personReviewRequiredCount > 0) {
                    EvidenceImportStatusAction.REVIEW
                } else {
                    null
                },
                secondaryActionLabel = if (meetingReviewRequiredCount > 0 && personReviewRequiredCount > 0) {
                    UiMessage.resource(R.string.evidence_import_review_action)
                } else {
                    null
                },
                transitionKey = "review:$reviewRequiredCount",
            )
            EvidenceImportStatusPhase.FAILED -> EvidenceImportStatusSurfaceUi(
                phase = phase,
                title = UiMessage.resource(R.string.evidence_import_status_failed_title, failedCountLabel),
                body = UiMessage.resource(R.string.evidence_import_status_failed_body),
                primaryAction = EvidenceImportStatusAction.RETRY_FAILED,
                primaryActionLabel = UiMessage.resource(R.string.evidence_import_status_action_retry_failed),
                transitionKey = "failed:$failedCount",
            )
        }
    }

    private fun EvidenceImportUiState.toTransient(
        foregroundImportKey: String? = null,
    ): EvidenceImportTransientState =
        EvidenceImportTransientState(
            message = message,
            loadingMessage = loadingMessage,
            meetingReview = meetingReview,
            meetingReviewRequested = meetingReview != null,
            foregroundImportKey = foregroundImportKey,
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

    private fun newForegroundImportKey(): String = UUID.randomUUID().toString()

    private fun inferSingleOtherSpeakerId(
        speakers: List<MeetingSpeakerPreviewDto>,
        speakerId: String,
    ): String? =
        speakers
            .filterNot { it.speakerId == speakerId }
            .singleOrNull()
            ?.speakerId
}

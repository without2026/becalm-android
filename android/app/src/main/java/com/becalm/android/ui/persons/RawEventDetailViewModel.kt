package com.becalm.android.ui.persons

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.analytics.NoopProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.NoopSourceEventAnchorDao
import com.becalm.android.data.local.db.dao.SourceEventAnchorDao
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceEventAnchorEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceOriginalContext
import com.becalm.android.data.repository.SourceOriginalResolver
import com.becalm.android.data.repository.NoopUserCorrectionRepository
import com.becalm.android.data.repository.UserCorrectionRepository
import com.becalm.android.ui.components.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

// ─── UI model ─────────────────────────────────────────────────────────────────

/**
 * Immutable snapshot of the RawEventDetailScreen UI.
 *
 * Exposes only display-safe fields extracted from the underlying raw entity,
 * keeping PII (raw personRef, raw headers) out of the UI layer. Email-specific
 * fields are populated only when the loaded event is an email source; for other
 * sources they remain null / zero.
 *
 * @property eventId Primary-key of the loaded event.
 * @property sourceType Source-type label (e.g. "gmail", "voice").
 * @property eventTitle Human-readable title of the event, when available.
 * @property timestamp Event occurrence time, when available.
 * @property snippet First 200 characters of the event body for preview display (SRC-004).
 * @property emailBody Joined `email_body` projection. Null when the event is not
 *   an email source, or when no body row has been captured yet. See [EmailBodyUi].
 * @property attachmentCount Count of entries parsed from
 *   [EmailBodyEntity.attachmentsMeta]. Zero when the JSON is null / empty / malformed
 *   (graceful degrade per EMAIL-007).
 * @property commitmentsExtractedCount Mirror of
 *   [RawIngestionEventEntity.commitmentsExtractedCount] — rendered as the
 *   "약속 추출 N건" badge when > 0 (SRC-008).
 * @property loading True while the lookup is in progress.
 * @property error Non-null when the event could not be found or an error occurred.
 */
public data class RawEventDetailUiState(
    val eventId: String = "",
    val sourceType: String? = null,
    val eventTitle: String? = null,
    val timestamp: kotlinx.datetime.Instant? = null,
    val snippet: String? = null,
    val durationSeconds: Int? = null,
    val location: String? = null,
    val attendeesRaw: String? = null,
    val commitmentQuotes: List<String> = emptyList(),
    val extractedCommitments: List<RawEventCommitmentSummary> = emptyList(),
    val emailBody: EmailBodyUi? = null,
    val archivedOriginal: ArchivedOriginalUi? = null,
    val threadMessages: List<RawEventThreadMessageUi> = emptyList(),
    val attachmentCount: Int = 0,
    val commitmentsExtractedCount: Int = 0,
    val syncStatus: String? = null,
    val participantCorrections: List<RawEventParticipantCorrectionRow> = emptyList(),
    val participantChoices: List<RawEventParticipantChoiceRow> = emptyList(),
    val correctingParticipantIds: Set<String> = emptySet(),
    val loading: Boolean = true,
    val error: UiMessage? = null,
    val message: UiMessage? = null,
)

public data class RawEventCommitmentSummary(
    val id: String,
    val title: String,
    val itemType: String,
    val direction: String?,
    val status: String?,
    val quote: String,
)

public data class RawEventThreadMessageUi(
    val rawEventId: String,
    val title: String?,
    val snippet: String?,
    val timestamp: kotlinx.datetime.Instant,
    val isCurrent: Boolean,
)

public data class RawEventParticipantCorrectionRow(
    val participantId: String,
    val sourceEventId: String,
    val currentPersonId: String?,
    val displayName: String,
    val detail: String?,
    val role: String,
    val resolutionStatus: String,
    val confidence: Double,
)

public data class RawEventParticipantChoiceRow(
    val personId: String,
    val displayName: String,
    val detail: String?,
    val identityType: String?,
    val normalizedValue: String?,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "RawEventDetailViewModel"
internal const val ARG_EVENT_ID = "event_id"

/**
 * ViewModel for RawEventDetailScreen (SRC-008).
 *
 * Loads a single raw ingestion event by its primary-key [eventId] using
 * [RawIngestionRepository.findById], then resolves any local original/email body
 * through [SourceOriginalResolver].
 *
 * The current user ID is resolved from [UserPrefsStore.observeCurrentUserId] and
 * passed to the user-scoped [RawIngestionRepository.findById] call — prevents
 * cross-user leaks when a stale navigation arg references another user's event
 * UUID.
 *
 * @param savedStateHandle navigation argument; expects key [ARG_EVENT_ID].
 */
@HiltViewModel
public class RawEventDetailViewModel @Inject constructor(
    private val rawIngestionRepository: RawIngestionRepository,
    private val sourceOriginalResolver: SourceOriginalResolver,
    private val sourceEventAnchorDao: SourceEventAnchorDao = NoopSourceEventAnchorDao,
    private val projectionPort: RawEventDetailProjectionPort,
    private val userCorrectionRepository: UserCorrectionRepository = NoopUserCorrectionRepository,
    private val userPrefsStore: UserPrefsStore,
    savedStateHandle: SavedStateHandle,
    private val logger: Logger,
    private val productAnalytics: ProductAnalyticsClient = NoopProductAnalyticsClient(),
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val eventId: String = savedStateHandle[ARG_EVENT_ID] ?: ""

    private val _uiState: MutableStateFlow<RawEventDetailUiState> =
        MutableStateFlow(RawEventDetailUiState())
    public val uiState: StateFlow<RawEventDetailUiState> = _uiState.asStateFlow()

    init {
        if (eventId.isEmpty()) {
            _uiState.value = RawEventDetailUiState(
                loading = false,
                error = UiMessage.resource(R.string.raw_event_detail_error_missing_id),
            )
        } else {
            loadEvent()
        }
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    public fun onMessageShown() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    public fun onParticipantReassign(participantId: String, choicePersonId: String) {
        val currentState = _uiState.value
        val participant = currentState.participantCorrections.firstOrNull { it.participantId == participantId } ?: return
        val choice = currentState.participantChoices.firstOrNull { it.personId == choicePersonId } ?: return
        if (participantId in currentState.correctingParticipantIds) return
        viewModelScope.launch(ioDispatcher) {
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) {
                markCorrectionFinished(
                    participantId = participantId,
                    message = UiMessage.resource(R.string.raw_event_person_correction_failed),
                )
                return@launch
            }
            markCorrectionStarted(participantId)
            val result = userCorrectionRepository.submitParticipantReassign(
                userId = userId,
                participantId = participant.participantId,
                sourceEventId = participant.sourceEventId,
                fromPersonId = participant.currentPersonId,
                toPersonId = choice.personId,
                identityType = choice.identityType,
                normalizedValue = choice.normalizedValue,
                displayNameRaw = choice.displayName,
            )
            when (result) {
                is com.becalm.android.core.result.BecalmResult.Success -> {
                    markCorrectionFinished(
                        participantId = participantId,
                        message = UiMessage.resource(R.string.raw_event_person_correction_saved),
                    )
                    loadEvent()
                }
                is com.becalm.android.core.result.BecalmResult.Failure -> {
                    logger.w(TAG, "participant reassign failed: ${result.error}")
                    markCorrectionFinished(
                        participantId = participantId,
                        message = UiMessage.resource(R.string.raw_event_person_correction_failed),
                    )
                }
            }
        }
    }

    public fun onParticipantIgnore(participantId: String) {
        val currentState = _uiState.value
        val participant = currentState.participantCorrections.firstOrNull { it.participantId == participantId } ?: return
        if (participantId in currentState.correctingParticipantIds) return
        viewModelScope.launch(ioDispatcher) {
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) {
                markCorrectionFinished(
                    participantId = participantId,
                    message = UiMessage.resource(R.string.raw_event_person_correction_failed),
                )
                return@launch
            }
            markCorrectionStarted(participantId)
            val result = userCorrectionRepository.submitParticipantIgnore(
                userId = userId,
                participantId = participant.participantId,
                sourceEventId = participant.sourceEventId,
                fromPersonId = participant.currentPersonId,
                displayNameRaw = participant.displayName,
            )
            when (result) {
                is com.becalm.android.core.result.BecalmResult.Success -> {
                    markCorrectionFinished(
                        participantId = participantId,
                        message = UiMessage.resource(R.string.raw_event_person_correction_saved),
                    )
                    loadEvent()
                }
                is com.becalm.android.core.result.BecalmResult.Failure -> {
                    logger.w(TAG, "participant ignore failed: ${result.error}")
                    markCorrectionFinished(
                        participantId = participantId,
                        message = UiMessage.resource(R.string.raw_event_person_correction_failed),
                    )
                }
            }
        }
    }

    private fun loadEvent() {
        viewModelScope.launch {
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) {
                logger.w(TAG, "loadEvent id=%08x userId absent — treating as not found".format(eventId.hashCode()))
                _uiState.value = RawEventDetailProjector.notFoundState()
                return@launch
            }

            val rawEntity = rawIngestionRepository.findById(id = eventId, userId = userId)
            val sourceEventAnchor = if (rawEntity == null) {
                sourceEventAnchorDao.findBestForEventRef(userId = userId, eventRef = eventId)
            } else {
                null
            }
            val entity = rawEntity ?: sourceEventAnchor?.toSyntheticRawEvent()
            logger.d(TAG, "loadEvent id=%08x found=${entity != null}".format(eventId.hashCode()))
            if (entity == null) {
                _uiState.value = RawEventDetailProjector.notFoundState()
                return@launch
            }

            val loadedState = withContext(ioDispatcher) {
                val commitmentQuotes = projectionPort.loadCommitmentQuotes(userId, entity)
                val extractedCommitments = projectionPort.loadCommitmentSummaries(userId, entity)
                val attendeesRaw = projectionPort.loadCalendarAttendeesRaw(userId, entity)
                val threadMessages = projectionPort.loadThreadMessages(userId, entity)
                val participantCorrections = projectionPort.loadParticipantCorrections(userId, entity)
                val participantChoices = projectionPort.loadParticipantCorrectionChoices(userId)
                val sourceOriginal = resolveSourceOriginalWithEmailBodyRefresh(
                    userId = userId,
                    event = entity,
                    fallbackRawEventIds = sourceEventAnchor?.localRawEventId?.let(::listOf).orEmpty(),
                )
                RawEventDetailProjector.buildLoadedState(
                    entity = entity,
                    emailBody = sourceOriginal.emailBody,
                    archivedOriginal = sourceOriginal.archivedOriginal,
                    commitmentQuotes = commitmentQuotes,
                    extractedCommitments = extractedCommitments,
                    attendeesRaw = attendeesRaw,
                ).copy(
                    threadMessages = threadMessages,
                    participantCorrections = participantCorrections,
                    participantChoices = participantChoices,
                    correctingParticipantIds = _uiState.value.correctingParticipantIds
                        .intersect(participantCorrections.mapTo(mutableSetOf()) { it.participantId }),
                    message = _uiState.value.message,
                )
            }
            _uiState.value = loadedState
            trackHistoricalItemViewed(entity)
        }
    }

    private suspend fun resolveSourceOriginalWithEmailBodyRefresh(
        userId: String,
        event: RawIngestionEventEntity,
        fallbackRawEventIds: List<String>,
    ): SourceOriginalContext {
        val initial = sourceOriginalResolver.resolve(
            userId = userId,
            event = event,
            fallbackRawEventIds = fallbackRawEventIds,
        )
        if (!event.shouldRefreshBackendEmailBody(initial)) return initial

        when (
            val refresh = rawIngestionRepository.refreshSince(
                userId = userId,
                sourceType = event.sourceType,
                since = event.timestamp,
            )
        ) {
            is BecalmResult.Success -> logger.d(
                TAG,
                "email body refresh source=${event.sourceType} fetched=${refresh.value.fetched} upserted=${refresh.value.upserted}",
            )
            is BecalmResult.Failure -> {
                logger.w(TAG, "email body refresh failed source=${event.sourceType}: ${refresh.error}")
                return initial
            }
        }
        return sourceOriginalResolver.resolve(
            userId = userId,
            event = event,
            fallbackRawEventIds = fallbackRawEventIds,
        )
    }

    private fun RawIngestionEventEntity.shouldRefreshBackendEmailBody(sourceOriginal: SourceOriginalContext): Boolean {
        if (sourceType !in BACKEND_MANAGED_EMAIL_SOURCE_TYPES) return false
        if (sourceOriginal.archivedOriginal != null) return false
        return sourceOriginal.emailBody?.bodyPlain.isNullOrBlank() && sourceOriginal.emailBody?.bodyHtml.isNullOrBlank()
    }

    private fun markCorrectionStarted(participantId: String) {
        _uiState.value = _uiState.value.copy(
            correctingParticipantIds = _uiState.value.correctingParticipantIds + participantId,
            message = null,
        )
    }

    private fun markCorrectionFinished(participantId: String, message: UiMessage?) {
        _uiState.value = _uiState.value.copy(
            correctingParticipantIds = _uiState.value.correctingParticipantIds - participantId,
            message = message,
        )
    }

    private fun SourceEventAnchorEntity.toSyntheticRawEvent(): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = sourceEventId ?: localRawEventId ?: id,
            userId = userId,
            clientEventId = localRawEventId ?: sourceEventId ?: id,
            sourceType = sourceType,
            sourceRef = sourceRef ?: providerEventId,
            counterpartyRef = null,
            eventTitle = title,
            eventSnippet = snippet,
            durationSeconds = null,
            location = null,
            conversationRef = conversationRef,
            folder = null,
            commitmentsExtractedCount = 0,
            timestamp = occurredAt ?: Clock.System.now(),
            syncStatus = "synced",
        )

    private fun trackHistoricalItemViewed(entity: RawIngestionEventEntity) {
        productAnalytics.track(
            ProductAnalyticsEvent(
                eventId = UUID.randomUUID().toString(),
                eventName = ProductAnalyticsEvents.HISTORICAL_ITEM_VIEWED,
                occurredAt = Clock.System.now(),
                properties = mapOf(
                    "surface" to "raw_event_detail",
                    "source_type" to entity.sourceType,
                    "has_commitments" to (entity.commitmentsExtractedCount > 0),
                    "core_active" to true,
                ),
            ),
        )
    }

}

private val BACKEND_MANAGED_EMAIL_SOURCE_TYPES = setOf(
    SourceType.GMAIL,
    SourceType.OUTLOOK_MAIL,
)

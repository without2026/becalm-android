package com.becalm.android.ui.persons

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.coroutines.rethrowIfCancellation
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.ManualMemoryOutboxDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.ManualMemoryOutboxEntity
import com.becalm.android.data.local.db.entity.ManualMemoryOutboxSyncStatus
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.ScheduleEventLinkEntity
import com.becalm.android.data.repository.ScheduleEventLinkRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.PersonActionRepository
import com.becalm.android.data.repository.PersonActionRefreshStats
import com.becalm.android.data.repository.PersonDetailRemoteRepository
import com.becalm.android.domain.reminder.ReminderScheduler
import com.becalm.android.ui.actions.PersonActionEvidenceDetailUi
import com.becalm.android.ui.actions.PersonActionItemUi
import com.becalm.android.ui.actions.defaultReminderSnoozeUntil
import com.becalm.android.ui.actions.draftEvidenceRefs
import com.becalm.android.ui.actions.supportedDraftKind
import com.becalm.android.ui.actions.toPersonActionEvidenceDetailUi
import com.becalm.android.ui.actions.toPersonActionItemUi
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.worker.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

// ─── UI models ────────────────────────────────────────────────────────────────

/** Compact commitment summary rendered inside one source-event card. */
public data class PersonDetailCommitmentSummary(
    val title: String,
    val itemType: String,
    val direction: String? = null,
    val status: String? = null,
)

/** Projection-only connector from one source-event card to the next interaction. */
public data class PersonDetailNextAction(
    @StringRes val labelRes: Int,
    val nextSourceEventKey: String,
)

/**
 * A PersonDetail timeline card. One card represents one original source event.
 * Extracted give/take/schedule items are kept for derived badges and drill-down
 * state, but they do not render inline in the person timeline.
 */
public data class SourceEventCardProjection(
    val sourceEventKey: String,
    val sourceType: String,
    val rawEventId: String?,
    val occurredAt: Instant,
    val title: String?,
    val snippet: String?,
    val commitmentsExtractedCount: Int = 0,
    val myActions: List<PersonDetailCommitmentSummary> = emptyList(),
    val theirActions: List<PersonDetailCommitmentSummary> = emptyList(),
    val schedules: List<PersonDetailCommitmentSummary> = emptyList(),
    val nextAction: PersonDetailNextAction? = null,
    val firstMemoryOrigin: String? = null,
    val linkedCalendarEventId: String? = null,
    val relatedSourceTypes: List<String> = emptyList(),
)

public enum class ManualMemorySyncStatusKind {
    PENDING,
    FAILED,
}

public data class ManualMemorySyncStatusUi(
    val kind: ManualMemorySyncStatusKind,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
)

public enum class PersonActionDraftSheetStatus {
    LOADING,
    READY,
    UNSUPPORTED,
    AUTH_REQUIRED,
    ERROR,
}

public data class PersonActionDraftSheetUiState(
    val actionItemId: String,
    val actionTitle: String?,
    val status: PersonActionDraftSheetStatus,
    val draftKind: String? = null,
    val channel: String = "email",
    val draftId: String? = null,
    val subject: String = "",
    val body: String = "",
    val recipientLabel: String? = null,
    val provenanceLabels: List<String> = emptyList(),
    val requiresUserReview: Boolean = true,
    val generatedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val error: UiMessage? = null,
    val canRetry: Boolean = false,
)

/**
 * Immutable snapshot of the PersonDetailScreen UI.
 *
 * @property personId Canonical relation person id for this screen.
 * @property displayName Display-safe contact name from on-device enrichment, or null.
 * @property companyName Display-safe company name from on-device enrichment, or null.
 * @property jobTitle Display-safe job title from on-device enrichment, or null.
 * @property sourceEventCards Source-event cards sorted newest-first. This is the single
 *   rendering path for person detail history.
 * @property loading True while the initial flow collection is in progress.
 * @property error Non-null when an error should be surfaced to the user.
 */
public data class PersonDetailUiState(
    val personId: String = "",
    val displayName: String? = null,
    val nickname: String? = null,
    val companyName: String? = null,
    val jobTitle: String? = null,
    val eventCount: Int = 0,
    val emailInteractionCount: Int = 0,
    val callInteractionCount: Int = 0,
    val meetingCount: Int = 0,
    val pendingCommitmentCount: Int = 0,
    val channelSources: Set<String> = emptySet(),
    val topActions: List<PersonActionItemUi> = emptyList(),
    val loadingReminderActionId: String? = null,
    val loadingCompleteActionId: String? = null,
    val loadingDismissActionId: String? = null,
    val evidenceDetail: PersonActionEvidenceDetailUi? = null,
    val loadingEvidenceActionId: String? = null,
    val draftSheet: PersonActionDraftSheetUiState? = null,
    val loadingDraftActionId: String? = null,
    val sourceEventCards: List<SourceEventCardProjection> = emptyList(),
    val manualMemorySyncStatus: ManualMemorySyncStatusUi? = null,
    val retryingManualMemorySync: Boolean = false,
    val canLoadMoreTimeline: Boolean = false,
    val loading: Boolean = true,
    val error: UiMessage? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "PersonDetailViewModel"
internal const val ARG_PERSON_ID = "person_id"
private const val PERSON_INTERACTIONS_PAGE_SIZE = 150
private const val PERSON_ACTION_DISMISS_REASON = "not_actionable"

/**
 * ViewModel for PersonDetailScreen (SRC-003, SRC-004, SRC-005).
 *
 * Primary detail data comes from the person index projection:
 * [PersonIndexDao.observeIdentitiesForPerson] and
 * [PersonIndexDao.observeInteractionsForPerson].
 *
 * The current user ID is sourced reactively from [UserPrefsStore.observeCurrentUserId].
 * When no user is signed in, an empty non-loading state is emitted.
 *
 * @param savedStateHandle navigation argument; expects key [ARG_PERSON_ID].
 */
@HiltViewModel
public class PersonDetailViewModel @Inject constructor(
    private val personEnrichmentRepository: PersonEnrichmentRepository,
    private val personIndexDao: PersonIndexDao,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val scheduleEventLinkRepository: ScheduleEventLinkRepository? = null,
    private val personActionRepository: PersonActionRepository = NoopPersonDetailActionRepository,
    private val personDetailRemoteRepository: PersonDetailRemoteRepository = NoopPersonDetailRemoteRepository,
    private val manualMemoryOutboxDao: ManualMemoryOutboxDao,
    private val workScheduler: WorkScheduler,
    private val reminderScheduler: ReminderScheduler,
    private val userPrefsStore: UserPrefsStore,
    savedStateHandle: SavedStateHandle,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {

    private val personId: String = savedStateHandle[ARG_PERSON_ID] ?: ""

    private val _uiState: MutableStateFlow<PersonDetailUiState> =
        MutableStateFlow(PersonDetailUiState(personId = personId))
    public val uiState: StateFlow<PersonDetailUiState> = _uiState.asStateFlow()
    private val interactionLimit: MutableStateFlow<Int> = MutableStateFlow(PERSON_INTERACTIONS_PAGE_SIZE)
    private var observeJob: Job? = null

    init {
        if (personId.isEmpty()) {
            _uiState.update { it.copy(loading = false, error = UiMessage.resource(R.string.person_detail_error_missing_id)) }
        } else {
            observeDetail()
        }
    }

    // ─── Actions ──────────────────────────────────────────────────────────────

    /**
     * Clears the current error from [PersonDetailUiState.error].
     */
    public fun onErrorDismissed() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Restarts the detail observation path after a blocking load error.
     */
    public fun onRetryLoad() {
        if (personId.isEmpty()) {
            _uiState.update { it.copy(loading = false, error = UiMessage.resource(R.string.person_detail_error_missing_id)) }
            return
        }
        _uiState.update { it.copy(loading = true, error = null) }
        observeDetail()
    }

    /**
     * Expands the local Room projection window for long person timelines.
     */
    public fun onLoadMoreTimeline() {
        interactionLimit.update { currentLimit -> currentLimit + PERSON_INTERACTIONS_PAGE_SIZE }
    }

    public fun onOpenPersonActionEvidence(
        actionItemId: String,
        evidenceKind: String?,
        evidenceId: String?,
    ) {
        if (actionItemId.isBlank() || evidenceKind.isNullOrBlank() || evidenceId.isNullOrBlank()) {
            _uiState.update { it.copy(error = UiMessage.resource(R.string.commitments_error_evidence_failed)) }
            return
        }
        _uiState.update { it.copy(loadingEvidenceActionId = actionItemId, error = null) }
        viewModelScope.launch(ioDispatcher) {
            val userId = userPrefsStore.observeCurrentUserId().firstOrNull()
            if (userId.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        loadingEvidenceActionId = null,
                        error = UiMessage.resource(R.string.commitments_error_evidence_failed),
                    )
                }
                return@launch
            }
            when (
                val result = personActionRepository.fetchEvidenceOriginal(
                    userId = userId,
                    actionItemId = actionItemId,
                    evidenceKind = evidenceKind,
                    evidenceId = evidenceId,
                )
            ) {
                is BecalmResult.Success -> {
                    _uiState.update {
                        it.copy(
                            loadingEvidenceActionId = null,
                            evidenceDetail = result.value.toPersonActionEvidenceDetailUi(),
                            error = null,
                        )
                    }
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "person action evidence failed id=${hashId(actionItemId)}: ${result.error}")
                    _uiState.update {
                        it.copy(
                            loadingEvidenceActionId = null,
                            error = UiMessage.resource(R.string.commitments_error_evidence_failed),
                        )
                    }
                }
            }
        }
    }

    public fun onDismissPersonActionEvidence() {
        _uiState.update { it.copy(evidenceDetail = null) }
    }

    public fun onOpenPersonActionDraft(actionItemId: String) {
        if (actionItemId.isBlank() || _uiState.value.loadingDraftActionId == actionItemId) return
        val currentState = _uiState.value
        val action = currentState.topActions.firstOrNull { it.id == actionItemId }
        if (action == null) {
            _uiState.update { it.copy(error = UiMessage.resource(R.string.person_action_draft_failed)) }
            return
        }
        val recipientLabel = action.draftRecipientLabel(currentState)
        val draftKind = action.supportedDraftKind()
        if (draftKind == null) {
            _uiState.update {
                it.copy(
                    draftSheet = PersonActionDraftSheetUiState(
                        actionItemId = action.id,
                        actionTitle = action.title,
                        status = PersonActionDraftSheetStatus.UNSUPPORTED,
                        recipientLabel = recipientLabel,
                        error = UiMessage.resource(R.string.person_action_draft_unsupported),
                    ),
                )
            }
            return
        }
        val existingEditableDraft = _uiState.value.draftSheet
            ?.takeIf { it.actionItemId == action.id && it.status == PersonActionDraftSheetStatus.READY }
        _uiState.update {
            it.copy(
                loadingDraftActionId = actionItemId,
                error = null,
                draftSheet = existingEditableDraft?.copy(error = null, canRetry = false)
                    ?: PersonActionDraftSheetUiState(
                        actionItemId = action.id,
                        actionTitle = action.title,
                        status = PersonActionDraftSheetStatus.LOADING,
                        draftKind = draftKind,
                        recipientLabel = recipientLabel,
                    ),
            )
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                val userId = userPrefsStore.observeCurrentUserId().firstOrNull()
                if (userId.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(
                            loadingDraftActionId = null,
                            draftSheet = PersonActionDraftSheetUiState(
                                actionItemId = action.id,
                                actionTitle = action.title,
                                status = PersonActionDraftSheetStatus.AUTH_REQUIRED,
                                draftKind = draftKind,
                                recipientLabel = recipientLabel,
                                error = UiMessage.resource(R.string.person_action_draft_auth_failed),
                                canRetry = false,
                            ),
                        )
                    }
                    return@launch
                }
                when (
                    val result = personActionRepository.generateDraft(
                        userId = userId,
                        actionItemId = action.id,
                        draftKind = draftKind,
                        channel = "email",
                        evidenceRefs = action.draftEvidenceRefs(),
                    )
                ) {
                    is BecalmResult.Success -> {
                        _uiState.update {
                            it.copy(
                                loadingDraftActionId = null,
                                draftSheet = result.value.toDraftSheetUiState(
                                    actionTitle = action.title,
                                    recipientLabel = recipientLabel,
                                ),
                                error = null,
                            )
                        }
                    }
                    is BecalmResult.Failure -> {
                        logger.w(TAG, "person action draft failed id=${hashId(action.id)}: ${result.error}")
                        _uiState.update {
                            it.copy(
                                loadingDraftActionId = null,
                                draftSheet = action.toDraftFailureState(
                                    draftKind = draftKind,
                                    error = result.error,
                                    recipientLabel = recipientLabel,
                                    existingEditableDraft = existingEditableDraft,
                                ),
                            )
                        }
                    }
                }
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                logger.w(TAG, "person action draft crashed id=${hashId(action.id)}: ${e.message}")
                _uiState.update {
                    it.copy(
                        loadingDraftActionId = null,
                        draftSheet = action.toDraftFailureState(
                            draftKind = draftKind,
                            error = BecalmError.Unknown(e),
                            recipientLabel = recipientLabel,
                            existingEditableDraft = existingEditableDraft,
                        ),
                    )
                }
            }
        }
    }

    public fun onRetryPersonActionDraft() {
        val actionItemId = _uiState.value.draftSheet?.actionItemId ?: return
        onOpenPersonActionDraft(actionItemId)
    }

    public fun onDraftSubjectChange(value: String) {
        _uiState.update { state ->
            state.copy(draftSheet = state.draftSheet?.copy(subject = value))
        }
    }

    public fun onDraftBodyChange(value: String) {
        _uiState.update { state ->
            state.copy(draftSheet = state.draftSheet?.copy(body = value))
        }
    }

    public fun onDismissPersonActionDraft() {
        _uiState.update { it.copy(draftSheet = null, loadingDraftActionId = null) }
    }

    public fun onCompletePersonAction(actionItemId: String) {
        if (
            actionItemId.isBlank() ||
            _uiState.value.loadingCompleteActionId == actionItemId ||
            _uiState.value.loadingDismissActionId == actionItemId
        ) return
        _uiState.update { it.copy(loadingCompleteActionId = actionItemId, error = null) }
        viewModelScope.launch(ioDispatcher) {
            try {
                val userId = userPrefsStore.observeCurrentUserId().firstOrNull()
                if (userId.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(
                            loadingCompleteActionId = null,
                            error = UiMessage.resource(R.string.person_action_complete_failed),
                        )
                    }
                    return@launch
                }
                when (val result = personActionRepository.completeAction(userId = userId, actionItemId = actionItemId)) {
                    is BecalmResult.Success -> {
                        refreshPersonActions(userId)
                        _uiState.update {
                            it.copy(
                                loadingCompleteActionId = null,
                                error = UiMessage.resource(R.string.person_action_complete_success),
                            )
                        }
                    }
                    is BecalmResult.Failure -> {
                        logger.w(TAG, "person action complete failed id=${hashId(actionItemId)}: ${result.error}")
                        _uiState.update {
                            it.copy(
                                loadingCompleteActionId = null,
                                error = UiMessage.resource(R.string.person_action_complete_failed),
                            )
                        }
                    }
                }
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                logger.w(TAG, "person action complete crashed id=${hashId(actionItemId)}: ${e.message}")
                _uiState.update {
                    it.copy(
                        loadingCompleteActionId = null,
                        error = UiMessage.resource(R.string.person_action_complete_failed),
                    )
                }
            }
        }
    }

    public fun onDismissPersonAction(actionItemId: String) {
        if (
            actionItemId.isBlank() ||
            _uiState.value.loadingCompleteActionId == actionItemId ||
            _uiState.value.loadingDismissActionId == actionItemId
        ) return
        _uiState.update { it.copy(loadingDismissActionId = actionItemId, error = null) }
        viewModelScope.launch(ioDispatcher) {
            try {
                val userId = userPrefsStore.observeCurrentUserId().firstOrNull()
                if (userId.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(
                            loadingDismissActionId = null,
                            error = UiMessage.resource(R.string.person_action_dismiss_failed),
                        )
                    }
                    return@launch
                }
                when (
                    val result = personActionRepository.dismissAction(
                        userId = userId,
                        actionItemId = actionItemId,
                        reason = PERSON_ACTION_DISMISS_REASON,
                    )
                ) {
                    is BecalmResult.Success -> {
                        refreshPersonActions(userId)
                        _uiState.update {
                            it.copy(
                                loadingDismissActionId = null,
                                error = UiMessage.resource(R.string.person_action_dismiss_success),
                            )
                        }
                    }
                    is BecalmResult.Failure -> {
                        logger.w(TAG, "person action dismiss failed id=${hashId(actionItemId)}: ${result.error}")
                        _uiState.update {
                            it.copy(
                                loadingDismissActionId = null,
                                error = UiMessage.resource(R.string.person_action_dismiss_failed),
                            )
                        }
                    }
                }
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                logger.w(TAG, "person action dismiss crashed id=${hashId(actionItemId)}: ${e.message}")
                _uiState.update {
                    it.copy(
                        loadingDismissActionId = null,
                        error = UiMessage.resource(R.string.person_action_dismiss_failed),
                    )
                }
            }
        }
    }

    public fun onRetryManualMemorySync() {
        if (personId.isBlank() || _uiState.value.retryingManualMemorySync) return
        _uiState.update { it.copy(retryingManualMemorySync = true, error = null) }
        viewModelScope.launch(ioDispatcher) {
            val userId = userPrefsStore.observeCurrentUserId().firstOrNull()
            if (userId.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        retryingManualMemorySync = false,
                        error = UiMessage.resource(R.string.person_detail_manual_memory_sync_retry_failed),
                    )
                }
                return@launch
            }
            try {
                manualMemoryOutboxDao.markFailedForPersonPending(
                    userId = userId,
                    personId = personId,
                    updatedAt = Clock.System.now(),
                )
                workScheduler.enqueueManualMemoryOutboxRetry(initialDelaySeconds = 0L)
                _uiState.update { it.copy(retryingManualMemorySync = false) }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                logger.w(TAG, "manual memory retry failed person=${hashId(personId)}: ${e.message}")
                _uiState.update {
                    it.copy(
                        retryingManualMemorySync = false,
                        error = UiMessage.resource(R.string.person_detail_manual_memory_sync_retry_failed),
                    )
                }
            }
        }
    }

    public fun onRemindPersonAction(actionItemId: String) {
        val action = _uiState.value.topActions.firstOrNull { it.id == actionItemId }
        val snoozedUntil = action?.defaultReminderSnoozeUntil(Clock.System.now())
        val commitmentId = action?.commitmentId?.takeIf { it.isNotBlank() }
        if (actionItemId.isBlank() || action == null || commitmentId == null || snoozedUntil == null) {
            _uiState.update { it.copy(error = UiMessage.resource(R.string.person_action_reminder_failed)) }
            return
        }
        _uiState.update { it.copy(loadingReminderActionId = actionItemId, error = null) }
        viewModelScope.launch(ioDispatcher) {
            try {
                val userId = userPrefsStore.observeCurrentUserId().firstOrNull()
                if (userId.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(
                            loadingReminderActionId = null,
                            error = UiMessage.resource(R.string.person_action_reminder_failed),
                        )
                    }
                    return@launch
                }
                when (
                    val result = personActionRepository.snoozeAction(
                        userId = userId,
                        actionItemId = actionItemId,
                        snoozedUntil = snoozedUntil,
                        reason = "user_reminder",
                    )
                ) {
                    is BecalmResult.Success -> {
                        userPrefsStore.setCommitmentReminderDisabled(commitmentId, disabled = false)
                        reminderScheduler.schedule(commitmentId, action.dueAt)
                        refreshPersonActions(userId)
                        _uiState.update {
                            it.copy(
                                loadingReminderActionId = null,
                                error = UiMessage.resource(R.string.person_action_reminder_scheduled),
                            )
                        }
                    }
                    is BecalmResult.Failure -> {
                        logger.w(TAG, "person action reminder failed id=${hashId(actionItemId)}: ${result.error}")
                        _uiState.update {
                            it.copy(
                                loadingReminderActionId = null,
                                error = UiMessage.resource(R.string.person_action_reminder_failed),
                            )
                        }
                    }
                }
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                logger.w(TAG, "person action reminder crashed id=${hashId(actionItemId)}: ${e.message}")
                _uiState.update {
                    it.copy(
                        loadingReminderActionId = null,
                        error = UiMessage.resource(R.string.person_action_reminder_failed),
                    )
                }
            }
        }
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeDetail() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            userPrefsStore.observeCurrentUserId()
                .combine(interactionLimit) { userId, limit -> userId to limit }
                .flatMapLatest { (userId, limit) ->
                    if (userId == null) {
                        flowOf(PersonDetailUiState(personId = personId, loading = false))
                    } else {
                        refreshPersonActions(userId)
                        refreshPersonEvents(userId)
                        combine(
                            personIndexDao.observeIdentitiesForPerson(userId, personId),
                            personEnrichmentRepository.observeAll(),
                            personIndexDao.observeInteractionsForPerson(userId, personId, limit),
                            personActionRepository.observeActiveForPerson(userId, personId, limit = PERSON_ACTION_DETAIL_LIMIT),
                            manualMemoryOutboxDao.observeForPerson(userId, personId),
                        ) { identities, enrichmentRows, interactions, actionRows, manualMemoryOutboxRows ->
                            DetailInputs(
                                identities = identities,
                                enrichmentRows = enrichmentRows,
                                interactions = interactions,
                                actionRows = actionRows,
                                manualMemoryOutboxRows = manualMemoryOutboxRows,
                            )
                        }.flatMapLatest { inputs ->
                            val interactions = inputs.interactions
                            val linksFlow = scheduleEventLinkRepository?.observeForProjectionRefs(
                                userId = userId,
                                commitmentIds = interactions.mapNotNull { it.commitmentId },
                                rawEventIds = interactions.mapNotNull { it.sourceEventId },
                                calendarEventIds = emptyList(),
                            ) ?: flowOf(emptyList<ScheduleEventLinkEntity>())
                            linksFlow.combine(flowOf(inputs)) { links, currentInputs ->
                                DetailInputsWithLinks(currentInputs, links)
                            }
                        }.flatMapLatest { detail ->
                            val inputs = detail.inputs
                            flowOf(
                                withContext(ioDispatcher) {
                                    val rawEvents = loadRawEventsForInteractions(userId, inputs.interactions)
                                    PersonDetailProjector.buildIndexedState(
                                        personId = personId,
                                        identities = inputs.identities,
                                        enrichmentRows = inputs.enrichmentRows,
                                        interactions = inputs.interactions,
                                        rawEvents = rawEvents,
                                        scheduleLinks = detail.scheduleLinks,
                                    ).copy(
                                        topActions = inputs.actionRows
                                            .asSequence()
                                            .sortedWith(
                                                compareByDescending<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity> { it.urgencyScore }
                                                    .thenByDescending { it.updatedAt },
                                            )
                                            .take(PERSON_ACTION_DETAIL_VISIBLE_LIMIT)
                                            .map { it.toPersonActionItemUi() }
                                            .toList(),
                                        manualMemorySyncStatus = inputs.manualMemoryOutboxRows.toManualMemorySyncStatusUi(),
                                        canLoadMoreTimeline = inputs.interactions.size >= limit,
                                    )
                                },
                            )
                        }.catch { e ->
                            logger.e(TAG, "observeDetail failed", e)
                            emit(
                                PersonDetailUiState(
                                    personId = personId,
                                    loading = false,
                                    error = UiMessage.resource(R.string.person_detail_error_load_failed),
                                ),
                            )
                        }
                    }
                }
                .collect { state ->
                    _uiState.update { current ->
                        state.copy(
                            loadingReminderActionId = current.loadingReminderActionId,
                            loadingCompleteActionId = current.loadingCompleteActionId,
                            loadingDismissActionId = current.loadingDismissActionId,
                            evidenceDetail = current.evidenceDetail,
                            loadingEvidenceActionId = current.loadingEvidenceActionId,
                            draftSheet = current.draftSheet,
                            loadingDraftActionId = current.loadingDraftActionId,
                            retryingManualMemorySync = current.retryingManualMemorySync,
                        )
                    }
                }
        }
    }

    private suspend fun loadRawEventsForInteractions(
        userId: String,
        interactions: List<com.becalm.android.data.local.db.entity.PersonInteractionEntity>,
    ): List<RawIngestionEventEntity> {
        val rawIds = interactions.mapNotNull { interaction ->
            interaction.sourceEventId
                ?: interaction.sourceRef.takeIf { it.startsWith("raw:") }?.removePrefix("raw:")
        }.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val sourceRefs = interactions.map { it.sourceRef.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("commitment:") }
            .distinct()
        val byId = if (rawIds.isEmpty()) {
            emptyList()
        } else {
            rawIngestionEventDao.findByIdsForUser(userId = userId, ids = rawIds)
        }
        val bySourceRef = if (sourceRefs.isEmpty()) {
            emptyList()
        } else {
            rawIngestionEventDao.findBySourceRefsForUser(userId = userId, sourceRefs = sourceRefs)
        }
        return (byId + bySourceRef).distinctBy { it.id }
    }

    private fun refreshPersonActions(userId: String) {
        viewModelScope.launch(ioDispatcher) {
            when (val result = personActionRepository.refresh(userId = userId, surface = "person")) {
                is BecalmResult.Success -> Unit
                is BecalmResult.Failure -> logger.w(TAG, "person action refresh failed: ${result.error}")
            }
        }
    }

    private fun refreshPersonEvents(userId: String) {
        viewModelScope.launch(ioDispatcher) {
            when (val result = personDetailRemoteRepository.refreshPersonEvents(userId = userId, personId = personId)) {
                is BecalmResult.Success -> Unit
                is BecalmResult.Failure -> logger.w(TAG, "person event recall refresh failed: ${result.error}")
            }
        }
    }

    private fun hashId(id: String): String = "%08x".format(id.hashCode())

    private data class DetailInputs(
        val identities: List<com.becalm.android.data.local.db.entity.PersonIdentityEntity>,
        val enrichmentRows: List<com.becalm.android.data.local.db.entity.PersonEnrichmentEntity>,
        val interactions: List<com.becalm.android.data.local.db.entity.PersonInteractionEntity>,
        val actionRows: List<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity>,
        val manualMemoryOutboxRows: List<ManualMemoryOutboxEntity>,
    )

    private data class DetailInputsWithLinks(
        val inputs: DetailInputs,
        val scheduleLinks: List<ScheduleEventLinkEntity>,
    )
}

private fun List<ManualMemoryOutboxEntity>.toManualMemorySyncStatusUi(): ManualMemorySyncStatusUi? {
    val failedCount = count { it.syncStatus == ManualMemoryOutboxSyncStatus.FAILED }
    val pendingCount = count { it.syncStatus == ManualMemoryOutboxSyncStatus.PENDING }
    return when {
        failedCount > 0 -> ManualMemorySyncStatusUi(
            kind = ManualMemorySyncStatusKind.FAILED,
            pendingCount = pendingCount,
            failedCount = failedCount,
        )
        pendingCount > 0 -> ManualMemorySyncStatusUi(
            kind = ManualMemorySyncStatusKind.PENDING,
            pendingCount = pendingCount,
        )
        else -> null
    }
}

private fun com.becalm.android.data.remote.dto.PersonActionDraftDto.toDraftSheetUiState(
    actionTitle: String?,
    recipientLabel: String?,
): PersonActionDraftSheetUiState =
    PersonActionDraftSheetUiState(
        actionItemId = actionItemId,
        actionTitle = actionTitle,
        status = PersonActionDraftSheetStatus.READY,
        draftKind = draftKind,
        channel = channel,
        draftId = draftId,
        subject = subject.orEmpty(),
        body = body,
        recipientLabel = recipientLabel,
        provenanceLabels = provenance.mapNotNull { it.label?.takeIf(String::isNotBlank) },
        requiresUserReview = safety?.requiresUserReview ?: true,
        generatedAt = generatedAt,
        expiresAt = expiresAt,
    )

private fun PersonActionItemUi.toDraftFailureState(
    draftKind: String,
    error: BecalmError,
    recipientLabel: String? = null,
    existingEditableDraft: PersonActionDraftSheetUiState? = null,
): PersonActionDraftSheetUiState {
    val status = when (error) {
        BecalmError.Unauthorized -> PersonActionDraftSheetStatus.AUTH_REQUIRED
        is BecalmError.Validation, is BecalmError.NotFound -> PersonActionDraftSheetStatus.UNSUPPORTED
        else -> PersonActionDraftSheetStatus.ERROR
    }
    val messageRes = when (status) {
        PersonActionDraftSheetStatus.AUTH_REQUIRED -> R.string.person_action_draft_auth_failed
        PersonActionDraftSheetStatus.UNSUPPORTED -> R.string.person_action_draft_unsupported
        PersonActionDraftSheetStatus.ERROR -> R.string.person_action_draft_failed
        PersonActionDraftSheetStatus.LOADING, PersonActionDraftSheetStatus.READY -> R.string.person_action_draft_failed
    }
    if (status == PersonActionDraftSheetStatus.ERROR && existingEditableDraft != null) {
        return existingEditableDraft.copy(
            status = PersonActionDraftSheetStatus.READY,
            draftKind = draftKind,
            error = UiMessage.resource(messageRes),
            canRetry = true,
        )
    }
    return PersonActionDraftSheetUiState(
        actionItemId = id,
        actionTitle = title,
        status = status,
        draftKind = draftKind,
        recipientLabel = recipientLabel,
        error = UiMessage.resource(messageRes),
        canRetry = status == PersonActionDraftSheetStatus.ERROR,
    )
}

private fun PersonActionItemUi.draftRecipientLabel(detailState: PersonDetailUiState): String? {
    val name = personDisplayName?.takeIf { it.isNotBlank() }
        ?: detailState.displayName?.takeIf { it.isNotBlank() }
        ?: return null
    val role = detailState.jobTitle?.takeIf { it.isNotBlank() }
    return if (role == null || name.contains(role)) {
        name
    } else {
        "$name $role"
    }
}

private const val PERSON_ACTION_DETAIL_LIMIT = 100
private const val PERSON_ACTION_DETAIL_VISIBLE_LIMIT = 3

private object NoopPersonDetailRemoteRepository : PersonDetailRemoteRepository {
    override suspend fun refreshPersonEvents(
        userId: String,
        personId: String,
        limit: Int,
    ): BecalmResult<PersonDetailRemoteRepository.RefreshStats> =
        BecalmResult.Success(
            PersonDetailRemoteRepository.RefreshStats(
                fetched = 0,
                upserted = 0,
                hasMore = false,
                nextCursor = null,
            ),
        )
}

private object NoopPersonDetailActionRepository : PersonActionRepository {
    override fun observeActiveForSurface(
        userId: String,
        surface: String,
        limit: Int,
    ): kotlinx.coroutines.flow.Flow<List<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity>> =
        flowOf(emptyList())

    override fun observeActiveForPerson(
        userId: String,
        personId: String,
        limit: Int,
    ): kotlinx.coroutines.flow.Flow<List<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity>> =
        flowOf(emptyList())

    override fun observeActiveForCommitment(
        userId: String,
        commitmentId: String,
        limit: Int,
    ): kotlinx.coroutines.flow.Flow<List<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity>> =
        flowOf(emptyList())

    override fun observeActiveForCalendarEvent(
        userId: String,
        calendarEventId: String,
        limit: Int,
    ): kotlinx.coroutines.flow.Flow<List<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity>> =
        flowOf(emptyList())

    override suspend fun refresh(
        userId: String,
        surface: String?,
    ): BecalmResult<PersonActionRefreshStats> =
        BecalmResult.Success(
            PersonActionRefreshStats(
                fetched = 0,
                deleted = 0,
                serverWatermark = null,
                recomputeState = null,
            ),
        )

    override suspend fun completeAction(
        userId: String,
        actionItemId: String,
        expectedUpdatedAt: kotlinx.datetime.Instant?,
    ): BecalmResult<com.becalm.android.data.repository.PersonActionMutationSyncStats> =
        BecalmResult.Success(noopMutationStats())

    override suspend fun dismissAction(
        userId: String,
        actionItemId: String,
        reason: String?,
        expectedUpdatedAt: kotlinx.datetime.Instant?,
    ): BecalmResult<com.becalm.android.data.repository.PersonActionMutationSyncStats> =
        BecalmResult.Success(noopMutationStats())

    override suspend fun snoozeAction(
        userId: String,
        actionItemId: String,
        snoozedUntil: kotlinx.datetime.Instant,
        reason: String?,
        expectedUpdatedAt: kotlinx.datetime.Instant?,
    ): BecalmResult<com.becalm.android.data.repository.PersonActionMutationSyncStats> =
        BecalmResult.Success(noopMutationStats())

    override suspend fun submitActionFeedback(
        userId: String,
        actionItemId: String,
        feedbackType: String,
        reason: String?,
        correctedPersonId: String?,
        correctedDueAt: kotlinx.datetime.Instant?,
    ): BecalmResult<com.becalm.android.data.repository.PersonActionMutationSyncStats> =
        BecalmResult.Success(noopMutationStats())

    override suspend fun syncPendingMutations(
        userId: String,
        limit: Int,
    ): BecalmResult<com.becalm.android.data.repository.PersonActionMutationSyncStats> =
        BecalmResult.Success(noopMutationStats())

    private fun noopMutationStats(): com.becalm.android.data.repository.PersonActionMutationSyncStats =
        com.becalm.android.data.repository.PersonActionMutationSyncStats(queued = 0, synced = 0, retryable = 0, failed = 0)
}

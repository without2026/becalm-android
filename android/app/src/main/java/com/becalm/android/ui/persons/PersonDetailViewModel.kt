package com.becalm.android.ui.persons

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.ScheduleEventLinkEntity
import com.becalm.android.data.repository.ScheduleEventLinkRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.PersonActionRepository
import com.becalm.android.data.repository.PersonActionRefreshStats
import com.becalm.android.ui.actions.PersonActionItemUi
import com.becalm.android.ui.actions.toPersonActionItemUi
import com.becalm.android.ui.components.UiMessage
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val sourceEventCards: List<SourceEventCardProjection> = emptyList(),
    val canLoadMoreTimeline: Boolean = false,
    val loading: Boolean = true,
    val error: UiMessage? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "PersonDetailViewModel"
internal const val ARG_PERSON_ID = "person_id"
private const val PERSON_INTERACTIONS_PAGE_SIZE = 150

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
                        combine(
                            personIndexDao.observeIdentitiesForPerson(userId, personId),
                            personEnrichmentRepository.observeAll(),
                            personIndexDao.observeInteractionsForPerson(userId, personId, limit),
                            personActionRepository.observeActiveForSurface(userId, surface = "person", limit = PERSON_ACTION_DETAIL_LIMIT),
                        ) { identities, enrichmentRows, interactions, actionRows ->
                            DetailInputs(identities, enrichmentRows, interactions, actionRows)
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
                                            .filter { it.personId == personId }
                                            .sortedWith(
                                                compareByDescending<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity> { it.urgencyScore }
                                                    .thenByDescending { it.updatedAt },
                                            )
                                            .take(PERSON_ACTION_DETAIL_VISIBLE_LIMIT)
                                            .map { it.toPersonActionItemUi() }
                                            .toList(),
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
                    _uiState.update { state }
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

    private data class DetailInputs(
        val identities: List<com.becalm.android.data.local.db.entity.PersonIdentityEntity>,
        val enrichmentRows: List<com.becalm.android.data.local.db.entity.PersonEnrichmentEntity>,
        val interactions: List<com.becalm.android.data.local.db.entity.PersonInteractionEntity>,
        val actionRows: List<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity>,
    )

    private data class DetailInputsWithLinks(
        val inputs: DetailInputs,
        val scheduleLinks: List<ScheduleEventLinkEntity>,
    )
}

private const val PERSON_ACTION_DETAIL_LIMIT = 100
private const val PERSON_ACTION_DETAIL_VISIBLE_LIMIT = 3

private object NoopPersonDetailActionRepository : PersonActionRepository {
    override fun observeActiveForSurface(
        userId: String,
        surface: String,
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
}

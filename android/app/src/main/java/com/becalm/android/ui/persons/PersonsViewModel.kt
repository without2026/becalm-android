package com.becalm.android.ui.persons

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.analytics.NoopProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.repository.FirstMemoryRepository
import com.becalm.android.data.repository.PersonManualMatchRepository
import com.becalm.android.data.repository.PersonActionRepository
import com.becalm.android.domain.onboarding.FirstMemoryDraft
import com.becalm.android.domain.onboarding.FirstMemoryInput
import com.becalm.android.domain.onboarding.FirstMemoryKind
import com.becalm.android.domain.onboarding.FirstMemoryOrigin
import com.becalm.android.domain.onboarding.FirstMemoryValidator
import com.becalm.android.ui.onboarding.FirstMemoryActivationUiState
import com.becalm.android.ui.components.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

// ─── UI models ────────────────────────────────────────────────────────────────

/**
 * A single row in the persons list, derived from the Room-backed persons projection owner.
 *
 * @property personId Canonical relation person id.
 * @property displayName Human-readable name from the on-device contact, or null when absent.
 * @property lastInteractionAt Timestamp of the most recent raw ingestion event for this person,
 *   or null when no events exist locally yet.
 * @property interactionCount Count of raw ingestion events recorded for this person.
 */
public data class PersonRow(
    val personId: String,
    val displayName: String?,
    val nickname: String? = null,
    val companyName: String? = null,
    val jobTitle: String? = null,
    val lastInteractionAt: Instant?,
    val interactionCount: Int,
    val pendingCommitmentCount: Int = 0,
    val channelSources: Set<String> = emptySet(),
    val lastInteractionSnippet: String? = null,
    val topAction: PersonActionSummary? = null,
) {
    /**
     * User-facing label for row rendering.
     *
     * The actual fallback text is resolved at UI layer to keep localization
     * in one place.
     */
    val displayLabel: String
        get() = displayName
            ?: nickname
            ?: ""
}

public data class PersonActionSummary(
    val id: String,
    val title: String,
    val primaryVerb: String,
    val shortReason: String,
    val actionKind: String,
    val dueAt: Instant?,
    val urgencyScore: Double,
)

public data class PersonMatchChoiceRow(
    val anchor: String,
    val displayName: String,
    val detail: String?,
    val hasInteractions: Boolean,
    val kind: PersonMatchChoiceKind = PersonMatchChoiceKind.CONTACT,
)

public enum class PersonMatchChoiceKind {
    CANDIDATE,
    EXISTING_PERSON,
    CONTACT,
}

public enum class PersonSectionKind {
    PENDING_COMMITMENTS,
    RECENT_CONTACTS,
}

public data class PersonSection(
    val kind: PersonSectionKind,
    val people: List<PersonRow>,
)

public fun buildPersonSections(people: List<PersonRow>): List<PersonSection> {
    val pending = ArrayList<PersonRow>()
    val recent = ArrayList<PersonRow>()
    people.forEach { person ->
        if (person.topAction != null || person.pendingCommitmentCount > 0) {
            pending += person
        } else {
            recent += person
        }
    }
    return listOf(
        PersonSection(
            kind = PersonSectionKind.PENDING_COMMITMENTS,
            people = pending,
        ),
        PersonSection(
            kind = PersonSectionKind.RECENT_CONTACTS,
            people = recent,
        ),
    )
}

/**
 * Immutable snapshot of the PersonsScreen UI.
 *
 * @property query Current search query string.
 * @property people Filtered and derived list of persons.
 * @property loading True while the initial flow collection is in progress.
 * @property error Non-null when an error has occurred and should be shown to the user.
 */
public data class PersonsUiState(
    val query: String = "",
    val people: List<PersonRow> = emptyList(),
    val personSections: List<PersonSection> = buildPersonSections(people),
    val matchChoices: List<PersonMatchChoiceRow> = emptyList(),
    val unassignedEvents: List<UnassignedEventSummary> = emptyList(),
    val showOfflineBadge: Boolean = false,
    val offlineLastSyncAt: Instant? = null,
    val sortOrder: PersonsSortOrder = PersonsSortOrder.MOST_RECENT_EVENT_DESC,
    val pageSize: Int = 120,
    val hasMorePages: Boolean = false,
    val nextCursor: String? = null,
    val refreshing: Boolean = false,
    val lastRefreshSnapshot: PersonsRefreshSnapshot? = null,
    val savingMatchEventIds: Set<String> = emptySet(),
    val resolvedMatchEventIds: Set<String> = emptySet(),
    val notSelfMatchEventIds: Set<String> = emptySet(),
    val firstMemory: FirstMemoryActivationUiState = FirstMemoryActivationUiState(),
    val loading: Boolean = true,
    val error: UiMessage? = null,
)

public sealed interface PersonsEffect {
    public data class NavigateToPersonDetail(val personId: String) : PersonsEffect
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val QUERY_DEBOUNCE_MS = 300L

private fun FirstMemoryActivationUiState.toDraft(): FirstMemoryDraft =
    FirstMemoryDraft(
        clientMemoryId = clientMemoryId,
        origin = origin,
        personName = personName,
        promiseText = promiseText,
        kind = kind,
        dueHint = dueHint,
    )

/**
 * ViewModel for PersonsScreen (SRC-001, SRC-002).
 *
 * Observes the persons-screen projection seams and maps them into a single
 * [PersonsUiState] snapshot.
 *
 * Search text is reflected immediately so the keyboard can compose normally.
 * Filtering is debounced at [QUERY_DEBOUNCE_MS] ms to avoid re-querying on every keystroke.
 */
@HiltViewModel
public class PersonsViewModel @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    projectionPort: PersonsScreenProjectionPort,
    private val refreshCoordinator: PersonsRefreshCoordinator,
    private val manualMatchRepository: PersonManualMatchRepository,
    private val firstMemoryRepository: FirstMemoryRepository,
    private val personActionRepository: PersonActionRepository = NoopPersonActionRepository,
    private val productAnalytics: ProductAnalyticsClient = NoopProductAnalyticsClient(),
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val stateSource = PersonsScreenStateSource(
        userPrefsStore = userPrefsStore,
        projectionPort = projectionPort,
    )

    private val _uiState: MutableStateFlow<PersonsUiState> = MutableStateFlow(PersonsUiState())
    public val uiState: StateFlow<PersonsUiState> = _uiState.asStateFlow()
    private val _effects: MutableSharedFlow<PersonsEffect> = MutableSharedFlow(extraBufferCapacity = 1)
    public val effects: SharedFlow<PersonsEffect> = _effects.asSharedFlow()

    /** Backing flow for the debounced search query. */
    private val _query: MutableStateFlow<String> = MutableStateFlow("")
    private var lastTrackedSearchLength: Int = 0

    init {
        observePeople()
        refreshActionCache()
    }

    // ─── Actions ──────────────────────────────────────────────────────────────

    /**
     * Updates the search query. Filtering is applied with a [QUERY_DEBOUNCE_MS] ms debounce
     * so rapid typing does not trigger a list recomposition on every character.
     *
     * Covers SRC-002.
     */
    public fun onQueryChange(q: String) {
        val normalized = q.trim()
        if (normalized.isNotEmpty() && normalized.length != lastTrackedSearchLength) {
            lastTrackedSearchLength = normalized.length
            productAnalytics.track(
                ProductAnalyticsEvent(
                    eventId = UUID.randomUUID().toString(),
                    eventName = ProductAnalyticsEvents.SEARCH_PERFORMED,
                    occurredAt = Clock.System.now(),
                    properties = mapOf(
                        "surface" to "persons",
                        "query_length" to normalized.length,
                        "core_active" to true,
                    ),
                ),
            )
        } else if (normalized.isEmpty()) {
            lastTrackedSearchLength = 0
        }
        _uiState.update { it.copy(query = q) }
        _query.value = q
    }

    public fun onPersonSelected(personId: String) {
        val normalized = _query.value.trim()
        if (normalized.isNotEmpty()) {
            productAnalytics.track(
                ProductAnalyticsEvent(
                    eventId = UUID.randomUUID().toString(),
                    eventName = ProductAnalyticsEvents.SEARCH_TO_DETAIL,
                    occurredAt = Clock.System.now(),
                    properties = mapOf(
                        "surface" to "persons",
                        "query_length" to normalized.length,
                        "result_type" to "person",
                        "has_target" to personId.isNotBlank(),
                        "core_active" to true,
                    ),
                ),
            )
        }
    }

    public fun onFirstMemoryOriginChange(origin: FirstMemoryOrigin) {
        _uiState.update { state ->
            state.copy(firstMemory = state.firstMemory.copy(origin = origin, errorMessageRes = null))
        }
    }

    public fun onFirstMemoryPersonNameChange(value: String) {
        _uiState.update { state ->
            state.copy(firstMemory = state.firstMemory.copy(personName = value, errorMessageRes = null))
        }
    }

    public fun onFirstMemoryPromiseTextChange(value: String) {
        _uiState.update { state ->
            state.copy(firstMemory = state.firstMemory.copy(promiseText = value, errorMessageRes = null))
        }
    }

    public fun onFirstMemoryKindChange(kind: FirstMemoryKind) {
        _uiState.update { state ->
            val dueHint = if (kind == FirstMemoryKind.SHARED_SCHEDULE) state.firstMemory.dueHint else ""
            state.copy(firstMemory = state.firstMemory.copy(kind = kind, dueHint = dueHint, errorMessageRes = null))
        }
    }

    public fun onFirstMemoryDueHintChange(value: String) {
        _uiState.update { state ->
            state.copy(firstMemory = state.firstMemory.copy(dueHint = value, errorMessageRes = null))
        }
    }

    public fun onSaveFirstMemory() {
        when (val validation = FirstMemoryValidator.validate(_uiState.value.firstMemory.toDraft())) {
            is FirstMemoryValidator.ValidationResult.Err -> {
                _uiState.update {
                    it.copy(firstMemory = it.firstMemory.copy(errorMessageRes = R.string.first_memory_error_required))
                }
            }
            is FirstMemoryValidator.ValidationResult.Ok -> saveFirstMemory(validation.input)
        }
    }

    private fun saveFirstMemory(input: FirstMemoryInput) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(firstMemory = it.firstMemory.copy(saving = true, errorMessageRes = null))
            }
            when (val result = firstMemoryRepository.save(input)) {
                is BecalmResult.Success -> {
                    _uiState.update {
                        it.copy(firstMemory = FirstMemoryActivationUiState())
                    }
                    _effects.emit(PersonsEffect.NavigateToPersonDetail(result.value.personId))
                }
                is BecalmResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            firstMemory = it.firstMemory.copy(
                                saving = false,
                                errorMessageRes = R.string.first_memory_error_save_failed,
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Clears the current error from [PersonsUiState.error].
     */
    public fun onErrorDismissed() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Re-runs the Room-backed grouping query and triggers catch-up + enrichment refresh.
     *
     * The projection port owns the actual scheduler calls so tests can observe that this
     * entry point exists without binding to WorkManager or Android lifecycle classes.
     */
    public fun onPullRefresh() {
        _uiState.update { it.copy(refreshing = true) }
        viewModelScope.launch(ioDispatcher) {
            try {
                val snapshot = refreshCoordinator.refresh()
                refreshActionCacheForCurrentUser()
                _uiState.update {
                    it.copy(
                        refreshing = false,
                        lastRefreshSnapshot = snapshot,
                        error = null,
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _uiState.update {
                    it.copy(
                        refreshing = false,
                        error = UiMessage.resource(R.string.persons_error_refresh_failed),
                    )
                }
            }
        }
    }

    private fun refreshActionCache() {
        viewModelScope.launch(ioDispatcher) {
            refreshActionCacheForCurrentUser()
        }
    }

    private suspend fun refreshActionCacheForCurrentUser() {
        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId.isNullOrBlank()) return
        personActionRepository.refresh(userId = userId, surface = "person")
    }

    public fun onManualMatch(
        event: UnassignedEventSummary,
        personAnchor: String,
        nickname: String,
    ) {
        val eventId = event.id
        if (!markMatchSaving(eventId)) return
        viewModelScope.launch(ioDispatcher) {
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) {
                finishMatchSaving(
                    eventId = eventId,
                    resolved = false,
                    error = UiMessage.resource(R.string.persons_error_sign_in_required),
                )
                return@launch
            }
            val result = manualMatchRepository.matchInteraction(
                userId = userId,
                sourceType = event.sourceType,
                sourceRef = event.sourceRef,
                interactionKind = event.interactionKind,
                personAnchor = personAnchor,
                nickname = nickname,
            )
            when (result) {
                is BecalmResult.Success -> {
                    finishMatchSaving(eventId = eventId, resolved = true)
                    productAnalytics.track(
                        ProductAnalyticsEvent(
                            eventId = UUID.randomUUID().toString(),
                            eventName = ProductAnalyticsEvents.PERSON_MATCH_COMPLETED,
                            occurredAt = Clock.System.now(),
                            properties = mapOf(
                                "source_type" to event.sourceType,
                                "interaction_kind" to event.interactionKind.toString(),
                            ),
                        ),
                    )
                }
                is BecalmResult.Failure -> {
                    finishMatchSaving(
                        eventId = eventId,
                        resolved = false,
                        error = UiMessage.resource(R.string.persons_error_manual_match_failed),
                    )
                }
            }
        }
    }

    public fun onSelfMatch(event: UnassignedEventSummary) {
        val eventId = event.id
        if (!markMatchSaving(eventId)) return
        viewModelScope.launch(ioDispatcher) {
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) {
                finishMatchSaving(
                    eventId = eventId,
                    resolved = false,
                    error = UiMessage.resource(R.string.persons_error_sign_in_required),
                )
                return@launch
            }
            when (
                val result = manualMatchRepository.matchInteractionAsSelf(
                    userId = userId,
                    sourceType = event.sourceType,
                    sourceRef = event.sourceRef,
                    interactionKind = event.interactionKind,
                )
            ) {
                is BecalmResult.Success -> {
                    finishMatchSaving(eventId = eventId, resolved = true)
                    productAnalytics.track(
                        ProductAnalyticsEvent(
                            eventId = UUID.randomUUID().toString(),
                            eventName = ProductAnalyticsEvents.PERSON_MATCH_COMPLETED,
                            occurredAt = Clock.System.now(),
                            properties = mapOf(
                                "source_type" to event.sourceType,
                                "interaction_kind" to event.interactionKind.toString(),
                                "match_target" to "self",
                            ),
                        ),
                    )
                }
                is BecalmResult.Failure -> {
                    finishMatchSaving(
                        eventId = eventId,
                        resolved = false,
                        error = UiMessage.resource(R.string.persons_error_manual_match_failed),
                    )
                }
            }
        }
    }

    public fun onNotSelfMatch(event: UnassignedEventSummary) {
        val eventId = event.id
        if (!markMatchSaving(eventId)) return
        viewModelScope.launch(ioDispatcher) {
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) {
                finishMatchSaving(
                    eventId = eventId,
                    resolved = false,
                    error = UiMessage.resource(R.string.persons_error_sign_in_required),
                )
                return@launch
            }
            when (
                val result = manualMatchRepository.rejectInteractionAsSelf(
                    userId = userId,
                    sourceType = event.sourceType,
                    sourceRef = event.sourceRef,
                    interactionKind = event.interactionKind,
                )
            ) {
                is BecalmResult.Success -> {
                    finishMatchSaving(eventId = eventId, resolved = false, notSelfRejected = true)
                    productAnalytics.track(
                        ProductAnalyticsEvent(
                            eventId = UUID.randomUUID().toString(),
                            eventName = ProductAnalyticsEvents.PERSON_MATCH_COMPLETED,
                            occurredAt = Clock.System.now(),
                            properties = mapOf(
                                "source_type" to event.sourceType,
                                "interaction_kind" to event.interactionKind.toString(),
                                "match_target" to "not_self",
                            ),
                        ),
                    )
                }
                is BecalmResult.Failure -> {
                    finishMatchSaving(
                        eventId = eventId,
                        resolved = false,
                        error = UiMessage.resource(R.string.persons_error_manual_match_failed),
                    )
                }
            }
        }
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun observePeople() {
        viewModelScope.launch {
            stateSource.observe(
                queryFlow = _query,
                pageSize = PERSONS_PAGE_SIZE,
                queryDebounceMs = QUERY_DEBOUNCE_MS,
            ).catch {
                _uiState.update { it.copy(loading = false, error = UiMessage.resource(R.string.persons_error_load_failed)) }
            }.collect { state ->
                val current = _uiState.value
                val currentIds = state.unassignedEvents.mapTo(mutableSetOf(), UnassignedEventSummary::id)
                _uiState.value = state.copy(
                    query = _query.value,
                    savingMatchEventIds = current.savingMatchEventIds.intersect(currentIds),
                    resolvedMatchEventIds = current.resolvedMatchEventIds.intersect(currentIds),
                    notSelfMatchEventIds = current.notSelfMatchEventIds.intersect(currentIds),
                    error = current.error,
                    refreshing = current.refreshing,
                    lastRefreshSnapshot = current.lastRefreshSnapshot,
                )
            }
        }
    }

    private fun markMatchSaving(eventId: String): Boolean {
        var accepted = false
        _uiState.update { state ->
            if (eventId in state.savingMatchEventIds) {
                state
            } else {
                accepted = true
                state.copy(
                    savingMatchEventIds = state.savingMatchEventIds + eventId,
                    error = null,
                )
            }
        }
        return accepted
    }

    private fun finishMatchSaving(
        eventId: String,
        resolved: Boolean,
        notSelfRejected: Boolean = false,
        error: UiMessage? = null,
    ) {
        _uiState.update { state ->
            state.copy(
                savingMatchEventIds = state.savingMatchEventIds - eventId,
                resolvedMatchEventIds = if (resolved) {
                    state.resolvedMatchEventIds + eventId
                } else {
                    state.resolvedMatchEventIds - eventId
                },
                notSelfMatchEventIds = if (notSelfRejected) {
                    state.notSelfMatchEventIds + eventId
                } else if (error != null) {
                    state.notSelfMatchEventIds - eventId
                } else {
                    state.notSelfMatchEventIds
                },
                error = error,
            )
        }
    }

    private companion object {
        const val PERSONS_PAGE_SIZE: Int = 120
    }
}

private object NoopPersonActionRepository : PersonActionRepository {
    override fun observeActiveForSurface(
        userId: String,
        surface: String,
        limit: Int,
    ): kotlinx.coroutines.flow.Flow<List<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity>> =
        flowOf(emptyList())

    override suspend fun refresh(
        userId: String,
        surface: String?,
    ): BecalmResult<com.becalm.android.data.repository.PersonActionRefreshStats> =
        BecalmResult.Success(
            com.becalm.android.data.repository.PersonActionRefreshStats(
                fetched = 0,
                deleted = 0,
                serverWatermark = null,
                recomputeState = null,
            ),
        )
}

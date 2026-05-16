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
import com.becalm.android.data.repository.PersonManualMatchRepository
import com.becalm.android.ui.components.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
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
) {
    /**
     * User-facing label. Falls back to the canonical [personId] when no
     * display name or nickname is available (SRC-001, ENR-006).
     */
    val displayLabel: String
        get() = displayName
            ?: nickname
            ?: personId
}

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
        if (person.pendingCommitmentCount > 0) {
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
    val pageSize: Int = 20,
    val hasMorePages: Boolean = false,
    val nextCursor: String? = null,
    val refreshing: Boolean = false,
    val lastRefreshSnapshot: PersonsRefreshSnapshot? = null,
    val loading: Boolean = true,
    val error: UiMessage? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val QUERY_DEBOUNCE_MS = 300L

/**
 * ViewModel for PersonsScreen (SRC-001, SRC-002).
 *
 * Observes the persons-screen projection seams and maps them into a single
 * [PersonsUiState] snapshot.
 *
 * Search filtering is debounced at [QUERY_DEBOUNCE_MS] ms to avoid re-rendering
 * on every keystroke.
 */
@HiltViewModel
public class PersonsViewModel @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    projectionPort: PersonsScreenProjectionPort,
    private val refreshCoordinator: PersonsRefreshCoordinator,
    private val manualMatchRepository: PersonManualMatchRepository,
    private val productAnalytics: ProductAnalyticsClient = NoopProductAnalyticsClient(),
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val stateSource = PersonsScreenStateSource(
        userPrefsStore = userPrefsStore,
        projectionPort = projectionPort,
    )

    private val _uiState: MutableStateFlow<PersonsUiState> = MutableStateFlow(PersonsUiState())
    public val uiState: StateFlow<PersonsUiState> = _uiState.asStateFlow()

    /** Backing flow for the debounced search query. */
    private val _query: MutableStateFlow<String> = MutableStateFlow("")
    private var lastTrackedSearchLength: Int = 0

    init {
        observePeople()
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
            val snapshot = refreshCoordinator.refresh()
            _uiState.update { it.copy(refreshing = false, lastRefreshSnapshot = snapshot) }
        }
    }

    public fun onManualMatch(
        event: UnassignedEventSummary,
        personAnchor: String,
        nickname: String,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) {
                _uiState.update { it.copy(error = UiMessage.resource(R.string.persons_error_sign_in_required)) }
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
                    _uiState.update { it.copy(error = UiMessage.resource(R.string.persons_error_manual_match_failed)) }
                }
            }
        }
    }

    public fun onSelfMatch(event: UnassignedEventSummary) {
        viewModelScope.launch(ioDispatcher) {
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) {
                _uiState.update { it.copy(error = UiMessage.resource(R.string.persons_error_sign_in_required)) }
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
                    _uiState.update { it.copy(error = UiMessage.resource(R.string.persons_error_manual_match_failed)) }
                }
            }
        }
    }

    public fun onNotSelfMatch(event: UnassignedEventSummary) {
        viewModelScope.launch(ioDispatcher) {
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) {
                _uiState.update { it.copy(error = UiMessage.resource(R.string.persons_error_sign_in_required)) }
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
                    _uiState.update { it.copy(error = UiMessage.resource(R.string.persons_error_manual_match_failed)) }
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
                _uiState.value = state
            }
        }
    }

    private companion object {
        const val PERSONS_PAGE_SIZE: Int = 20
    }
}

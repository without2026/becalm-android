package com.becalm.android.ui.persons

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.data.local.datastore.UserPrefsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

// ─── UI models ────────────────────────────────────────────────────────────────

/**
 * A single row in the persons list, derived from the Room-backed persons projection owner.
 *
 * @property personRef Canonicalized counterparty identifier.
 * @property displayName Human-readable name from the on-device contact, or null when absent.
 * @property lastInteractionAt Timestamp of the most recent raw ingestion event for this person,
 *   or null when no events exist locally yet.
 * @property interactionCount Count of raw ingestion events recorded for this person.
 */
public data class PersonRow(
    val personRef: String,
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
     * User-facing label. Falls back to the canonical raw [personRef] when no
     * display name or nickname is available (SRC-001, ENR-006).
     */
    val displayLabel: String
        get() = displayName
            ?: nickname
            ?: personRef
}

public enum class PersonSectionKind {
    PENDING_COMMITMENTS,
    RECENT_CONTACTS,
}

public data class PersonSection(
    val kind: PersonSectionKind,
    val people: List<PersonRow>,
)

public fun buildPersonSections(people: List<PersonRow>): List<PersonSection> = listOf(
    PersonSection(
        kind = PersonSectionKind.PENDING_COMMITMENTS,
        people = people.filter { it.pendingCommitmentCount > 0 },
    ),
    PersonSection(
        kind = PersonSectionKind.RECENT_CONTACTS,
        people = people.filter { it.pendingCommitmentCount <= 0 },
    ),
)

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
    val error: String? = null,
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
    userPrefsStore: UserPrefsStore,
    projectionPort: PersonsScreenProjectionPort,
    private val refreshCoordinator: PersonsRefreshCoordinator,
) : ViewModel() {
    private val stateSource = PersonsScreenStateSource(
        userPrefsStore = userPrefsStore,
        projectionPort = projectionPort,
    )

    private val _uiState: MutableStateFlow<PersonsUiState> = MutableStateFlow(PersonsUiState())
    public val uiState: StateFlow<PersonsUiState> = _uiState.asStateFlow()

    /** Backing flow for the debounced search query. */
    private val _query: MutableStateFlow<String> = MutableStateFlow("")

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
        _query.value = q
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
        val snapshot = refreshCoordinator.refresh()
        _uiState.update { it.copy(refreshing = false, lastRefreshSnapshot = snapshot) }
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun observePeople() {
        viewModelScope.launch {
            stateSource.observe(
                queryFlow = _query,
                pageSize = PERSONS_PAGE_SIZE,
                queryDebounceMs = QUERY_DEBOUNCE_MS,
            ).catch { e ->
                _uiState.update { it.copy(loading = false, error = e.message ?: "load failed") }
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    private companion object {
        const val PERSONS_PAGE_SIZE: Int = 20
    }
}

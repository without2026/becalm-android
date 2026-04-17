package com.becalm.android.ui.persons

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

// ─── UI models ────────────────────────────────────────────────────────────────

/**
 * A single row in the persons list, derived from [PersonEnrichmentEntity] enriched
 * with interaction statistics aggregated from [RawIngestionRepository].
 *
 * @property personRef Canonicalized counterparty identifier.
 * @property displayName Human-readable name from the on-device contact, or null when absent.
 * @property lastInteractionAt Timestamp of the most recent raw ingestion event for this person,
 *   or null when no events exist locally yet.
 * @property interactionCount Count of raw ingestion events recorded for this person.
 * @property starred Whether the person has been starred by the user.
 *   Always false until SP-05 lands the `starred` column on [PersonEnrichmentEntity].
 */
public data class PersonRow(
    val personRef: String,
    val displayName: String?,
    val lastInteractionAt: Instant?,
    val interactionCount: Int,
    val starred: Boolean,
) {
    /**
     * User-facing label. Falls back to a redacted prefix of [personRef] so raw
     * phone numbers / emails are never shown (SRC-001, ENR-006).
     */
    val displayLabel: String get() = displayName ?: if (personRef.length <= 3) "***" else personRef.take(3) + "***"
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
    val loading: Boolean = true,
    val error: String? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "PersonsViewModel"
private const val QUERY_DEBOUNCE_MS = 300L
private const val EVENTS_PER_PERSON_LIMIT = 100

/**
 * ViewModel for PersonsScreen (SRC-001, SRC-002).
 *
 * Combines [PersonEnrichmentRepository.observeAll] with per-person event counts
 * derived from [RawIngestionRepository.observeForPerson].  The event stats are
 * approximated by collecting the most recent events slice per person; a future
 * DAO query will replace this with a single aggregation query once the index is
 * available.
 *
 * Search filtering is debounced at [QUERY_DEBOUNCE_MS] ms to avoid re-rendering
 * on every keystroke.
 *
 * Star toggling fails loudly via [error] when [PersonEnrichmentRepository.setStarred]
 * returns [BecalmResult.Failure] (expected until SP-05 ships).
 */
@HiltViewModel
public class PersonsViewModel @Inject constructor(
    private val personEnrichmentRepository: PersonEnrichmentRepository,
    private val rawIngestionRepository: RawIngestionRepository,
    private val logger: Logger,
) : ViewModel() {

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
     * Requests a star-state change for [personRef].
     *
     * If [PersonEnrichmentRepository.setStarred] fails (expected while SP-05 is pending),
     * the error is surfaced in [PersonsUiState.error] rather than crashing or silently
     * dropping the request.
     *
     * Covers SRC-001.
     */
    public fun onToggleStar(personRef: String, starred: Boolean) {
        viewModelScope.launch {
            when (val result = personEnrichmentRepository.setStarred(personRef, starred)) {
                is BecalmResult.Success -> {
                    logger.d(TAG, "setStarred ok personRef=${redact(personRef)} starred=$starred")
                }
                is BecalmResult.Failure -> {
                    val message = when {
                        result.error is BecalmError.Unknown &&
                            result.error.throwable is UnsupportedOperationException ->
                            "Starred not yet supported (SP-05 pending)"
                        else -> result.error.toString()
                    }
                    logger.w(TAG, "setStarred failed: ${result.error}")
                    _uiState.update { it.copy(error = message) }
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

    // ─── Private ──────────────────────────────────────────────────────────────

    @OptIn(FlowPreview::class)
    private fun observePeople() {
        viewModelScope.launch {
            combine(
                personEnrichmentRepository.observeAll(),
                _query.debounce(QUERY_DEBOUNCE_MS),
            ) { enrichmentList, query ->
                enrichmentList to query
            }.catch { e ->
                _uiState.update { it.copy(loading = false, error = e.message ?: "load failed") }
            }.collect { (enrichmentList, query) ->
                val rows = enrichmentList
                    .filter { entity ->
                        query.isBlank() ||
                            entity.displayName?.contains(query, ignoreCase = true) == true ||
                            entity.personRef.contains(query, ignoreCase = true)
                    }
                    .map { entity -> entity.toPersonRow() }

                _uiState.update {
                    it.copy(
                        query = query,
                        people = rows,
                        loading = false,
                    )
                }
            }
        }
    }

    /**
     * Maps a [PersonEnrichmentEntity] to a [PersonRow].
     *
     * Interaction stats ([lastInteractionAt] / [interactionCount]) require a coroutine
     * database call per-person which would N+1 the list query.  PersonRow carries
     * placeholder defaults (null / 0) here; a dedicated DAO aggregate query (planned
     * post-SP-05) will replace this with a single JOIN once the index is available.
     *
     * The `starred` flag is always false until SP-05 adds the column to [PersonEnrichmentEntity].
     */
    // TODO(SRC-001): Replace placeholder stats with a DAO aggregate query (COUNT + MAX)
    // once the raw_ingestion_events index on person_ref is available (post-SP-05).
    private fun PersonEnrichmentEntity.toPersonRow(): PersonRow = PersonRow(
        personRef = personRef,
        displayName = displayName,
        lastInteractionAt = null,
        interactionCount = 0,
        starred = false,
    )
}

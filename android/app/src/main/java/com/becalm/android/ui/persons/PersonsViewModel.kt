package com.becalm.android.ui.persons

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.data.local.dao.CommitmentDao
import com.becalm.android.data.local.dao.PersonEnrichmentDao
import com.becalm.android.data.local.dao.RawIngestionEventDao
import com.becalm.android.data.local.entities.PersonEnrichment
import com.becalm.android.data.local.entities.RawIngestionEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// spec: SRC-001 — persons list from Room person_refs
// spec: SRC-003 — substring search filter
// spec: SRC-005 — unassigned bucket (person_ref IS NULL)
// spec: SRC-006 — pull-to-refresh
// spec: SRC-007 — offline mode with Room cache
// Invariant: persons_enrichment join is on-device only — never sends to Railway

data class PersonSummary(
    val personRef: String,
    val enrichment: PersonEnrichment?,
    val pendingCommitmentsCount: Int,
    val recentEventSnippet: String?,
    val recentEventTimestamp: Long?,
    val channelTypes: Set<String>
) {
    val displayName: String get() = enrichment?.displayName
        ?: enrichment?.nickname
        ?: personRef
}

data class PersonsUiState(
    val persons: List<PersonSummary> = emptyList(),
    val unassignedEvents: List<RawIngestionEvent> = emptyList(),
    val filteredPersons: List<PersonSummary> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val isOffline: Boolean = false,
    val lastSyncAt: Long? = null
)

@HiltViewModel
class PersonsViewModel @Inject constructor(
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val commitmentDao: CommitmentDao,
    private val personEnrichmentDao: PersonEnrichmentDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(PersonsUiState(isLoading = true))
    val uiState: StateFlow<PersonsUiState> = _uiState

    init {
        loadPersons()
    }

    // spec: SRC-001 — build person summaries from Room person_refs
    private fun loadPersons() {
        viewModelScope.launch {
            val personRefs = rawIngestionEventDao.getDistinctPersonRefs()
            val enrichments = personEnrichmentDao.getByPersonRefs(personRefs).associateBy { it.personRef }
            val pendingCounts = commitmentDao.getPendingCountPerPerson().associateBy { it.personRef }
            val unassigned = rawIngestionEventDao.getUnassignedEvents()

            val summaries = personRefs.mapNotNull { ref ->
                val events = rawIngestionEventDao.getEventsByPersonRef(ref, limit = 1)
                val recentEvent = events.firstOrNull()
                PersonSummary(
                    personRef = ref,
                    enrichment = enrichments[ref],
                    pendingCommitmentsCount = pendingCounts[ref]?.pendingCount ?: 0,
                    recentEventSnippet = recentEvent?.eventSnippet ?: recentEvent?.eventTitle,
                    recentEventTimestamp = recentEvent?.timestamp,
                    channelTypes = rawIngestionEventDao.getEventsByPersonRef(ref)
                        .map { it.sourceType }.toSet()
                )
            }.sortedByDescending { it.recentEventTimestamp }

            _uiState.value = PersonsUiState(
                persons = summaries,
                filteredPersons = summaries,
                unassignedEvents = unassigned,
                isLoading = false,
                lastSyncAt = System.currentTimeMillis()
            )
        }
    }

    // spec: SRC-003 — substring search on display_name, email, phone
    fun onSearchQueryChange(query: String) {
        val current = _uiState.value
        val filtered = if (query.isBlank()) {
            current.persons
        } else {
            current.persons.filter { person ->
                person.personRef.contains(query, ignoreCase = true) ||
                person.displayName.contains(query, ignoreCase = true) ||
                (person.enrichment?.company?.contains(query, ignoreCase = true) == true)
            }
        }
        _uiState.value = current.copy(searchQuery = query, filteredPersons = filtered)
    }

    // spec: SRC-006 — pull-to-refresh reloads from Room (Railway refresh is triggered by ING-011)
    fun onPullToRefresh() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        loadPersons()
    }
}

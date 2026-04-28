package com.becalm.android.ui.persons

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * A single row in the person's interaction timeline, discriminated by source type.
 */
public sealed class InteractionRow {

    /**
     * A raw ingestion event (voice, email, etc.) linked to this person.
     *
     * @property id Primary-key of the raw ingestion event. Required so the row can
     *   navigate to [com.becalm.android.ui.navigation.BecalmRoute.RawEventDetail]
     *   on tap (SRC-004).
     * @property timestamp When the event was recorded.
     * @property source Source type string (e.g. "voice", "gmail").
     * @property summary Event title when available; null otherwise.
     * @property snippet Truncated body preview from
     *   [RawIngestionEventEntity.eventSnippet] — rendered as secondary text on
     *   [InteractionHistoryRow] per `.spec/contracts/ui-map.yml:206-210`.
     * @property commitmentsExtractedCount Mirror of
     *   [RawIngestionEventEntity.commitmentsExtractedCount] — drives the
     *   "약속 추출 N건" badge on [InteractionHistoryRow] per SRC-008.
     */
    public data class Event(
        val id: String,
        val timestamp: Instant,
        val source: String,
        val summary: String?,
        val snippet: String?,
        val commitmentsExtractedCount: Int = 0,
    ) : InteractionRow()

    /**
     * A persisted trackable item linked to this person.
     *
     * @property timestamp When the source event occurred.
     * @property title Normalized item text/title.
     * @property itemType `action | schedule | decision`.
     * @property direction Action-only direction.
     * @property actionState Action-only lifecycle state.
     * @property scheduleStatus Schedule-only subtype.
     * @property decisionStatus Decision-only subtype.
     */
    public data class Commitment(
        val timestamp: Instant,
        val title: String,
        val itemType: String = CommitmentItemType.ACTION,
        val direction: String? = null,
        val actionState: String? = null,
        val scheduleStatus: String? = null,
        val decisionStatus: String? = null,
    ) : InteractionRow()

    /**
     * A calendar meeting where this person appeared as an attendee.
     *
     * @property timestamp Meeting start time.
     * @property title Meeting title.
     */
    public data class CalendarMeeting(
        val timestamp: Instant,
        val title: String,
    ) : InteractionRow()
}

/**
 * Immutable snapshot of the PersonDetailScreen UI.
 *
 * @property personRef Canonicalized counterparty identifier for this screen.
 * @property displayName Display-safe contact name from on-device enrichment, or null.
 * @property companyName Display-safe company name from on-device enrichment, or null.
 * @property jobTitle Display-safe job title from on-device enrichment, or null.
 * @property pendingCommitments Commitments that are not yet completed.
 * @property completedCommitments Commitments that have been completed/done.
 * @property interactionHistory Non-commitment interactions (events, meetings) sorted newest-first.
 * @property loading True while the initial flow collection is in progress.
 * @property error Non-null when an error should be surfaced to the user.
 */
public data class PersonDetailUiState(
    val personRef: String = "",
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
    val completedExpanded: Boolean = false,
    val pendingCommitments: List<InteractionRow.Commitment> = emptyList(),
    val completedCommitments: List<InteractionRow.Commitment> = emptyList(),
    val interactionHistory: List<InteractionRow> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "PersonDetailViewModel"
internal const val ARG_PERSON_REF = "person_id"
private const val RAW_EVENTS_LIMIT = 100
private const val CALENDAR_EVENTS_LIMIT = 50

/**
 * ViewModel for PersonDetailScreen (SRC-003, SRC-004, SRC-005).
 *
 * Combines four reactive sources:
 * 1. [PersonEnrichmentRepository.observeByPersonRef] — on-device contact metadata.
 * 2. [RawIngestionRepository.observeForPerson] — raw ingestion events linked to this person.
 * 3. [CommitmentRepository.observeAllForPerson] — commitments with this person as counterparty.
 * 4. [CalendarEventRepository.observeForPerson] — calendar meetings where this person attended.
 *
 * The current user ID is sourced reactively from [UserPrefsStore.observeCurrentUserId].
 * When no user is signed in, an empty non-loading state is emitted.
 *
 * @param savedStateHandle navigation argument; expects key [ARG_PERSON_REF].
 */
@HiltViewModel
public class PersonDetailViewModel @Inject constructor(
    private val personEnrichmentRepository: PersonEnrichmentRepository,
    private val rawIngestionRepository: RawIngestionRepository,
    private val commitmentRepository: CommitmentRepository,
    private val calendarEventRepository: CalendarEventRepository,
    private val userPrefsStore: UserPrefsStore,
    savedStateHandle: SavedStateHandle,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {

    private val personRef: String = savedStateHandle[ARG_PERSON_REF] ?: ""

    private val _uiState: MutableStateFlow<PersonDetailUiState> =
        MutableStateFlow(PersonDetailUiState(personRef = personRef))
    public val uiState: StateFlow<PersonDetailUiState> = _uiState.asStateFlow()
    private val _completedExpanded: MutableStateFlow<Boolean> = MutableStateFlow(false)

    init {
        if (personRef.isEmpty()) {
            _uiState.update { it.copy(loading = false, error = "Person ID missing") }
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

    /** Toggles the completed-commitments section expansion state (SRC-008). */
    public fun onToggleCompletedExpanded() {
        _completedExpanded.update { !it }
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeDetail() {
        viewModelScope.launch {
            userPrefsStore.observeCurrentUserId()
                .flatMapLatest { userId ->
                    if (userId == null) {
                        flowOf(PersonDetailUiState(personRef = personRef, loading = false))
                    } else {
                        combine(
                            personEnrichmentRepository.observeByPersonRef(personRef),
                            rawIngestionRepository.observeForPerson(userId, personRef, RAW_EVENTS_LIMIT),
                            commitmentRepository.observeAllForPerson(userId, personRef),
                            calendarEventRepository.observeForPerson(userId, personRef, CALENDAR_EVENTS_LIMIT),
                            _completedExpanded,
                        ) { enrichment, rawEvents, commitments, calendarEvents, completedExpanded ->
                            withContext(ioDispatcher) {
                                PersonDetailProjector.buildState(
                                    personRef = personRef,
                                    enrichment = enrichment,
                                    rawEvents = rawEvents,
                                    commitments = commitments,
                                    calendarEvents = calendarEvents,
                                    completedExpanded = completedExpanded,
                                )
                            }
                        }.catch { e ->
                            logger.e(TAG, "observeDetail failed", e)
                            emit(
                                PersonDetailUiState(
                                    personRef = personRef,
                                    loading = false,
                                    error = e.message ?: "observe failed",
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

}

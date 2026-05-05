package com.becalm.android.ui.persons

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.KST
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.remote.dto.SourceType
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
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

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
        val rawEventId: String? = id,
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

/** Compact commitment summary rendered inside one source-event card. */
public data class PersonDetailCommitmentSummary(
    val title: String,
    val itemType: String,
    val direction: String? = null,
    val status: String? = null,
)

/** Projection-only connector from one source-event card to the next interaction. */
public data class PersonDetailNextAction(
    val label: String,
    val nextSourceEventKey: String,
)

/**
 * A PersonDetail timeline card. One card represents one source event, and the
 * extracted give/take/schedule items from that source render inside it.
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
)

/**
 * Immutable snapshot of the PersonDetailScreen UI.
 *
 * @property personId Canonical relation person id for this screen.
 * @property displayName Display-safe contact name from on-device enrichment, or null.
 * @property companyName Display-safe company name from on-device enrichment, or null.
 * @property jobTitle Display-safe job title from on-device enrichment, or null.
 * @property timeline Commitments, raw events, and meetings sorted newest-first.
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
    val timeline: List<InteractionRow> = emptyList(),
    val sourceEventCards: List<SourceEventCardProjection> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "PersonDetailViewModel"
internal const val ARG_PERSON_ID = "person_id"
private const val PERSON_INTERACTIONS_LIMIT = 150

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
    private val userPrefsStore: UserPrefsStore,
    savedStateHandle: SavedStateHandle,
    private val logger: Logger,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {

    private val personId: String = savedStateHandle[ARG_PERSON_ID] ?: ""

    private val _uiState: MutableStateFlow<PersonDetailUiState> =
        MutableStateFlow(PersonDetailUiState(personId = personId))
    public val uiState: StateFlow<PersonDetailUiState> = _uiState.asStateFlow()

    init {
        if (personId.isEmpty()) {
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

    // ─── Private ──────────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeDetail() {
        viewModelScope.launch {
            userPrefsStore.observeCurrentUserId()
                .flatMapLatest { userId ->
                    if (userId == null) {
                        flowOf(PersonDetailUiState(personId = personId, loading = false))
                    } else {
                        val calendarCutoff = calendarHistoryCutoff()
                        combine(
                            personIndexDao.observeIdentitiesForPerson(userId, personId),
                            personEnrichmentRepository.observeAll(),
                            personIndexDao.observeInteractionsForPerson(userId, personId, PERSON_INTERACTIONS_LIMIT),
                        ) { identities, enrichmentRows, interactions ->
                            withContext(ioDispatcher) {
                                PersonDetailProjector.buildIndexedState(
                                    personId = personId,
                                    identities = identities,
                                    enrichmentRows = enrichmentRows,
                                    interactions = interactions.filterRecentCalendarHistory(calendarCutoff),
                                    rawEvents = emptyList(),
                                )
                            }
                        }.catch { e ->
                            logger.e(TAG, "observeDetail failed", e)
                            emit(
                                PersonDetailUiState(
                                    personId = personId,
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

    private fun calendarHistoryCutoff(): Instant =
        clock.today(KST).plus(DatePeriod(days = -1)).atStartOfDayIn(KST)

    private fun List<PersonInteractionEntity>.filterRecentCalendarHistory(
        cutoff: Instant,
    ): List<PersonInteractionEntity> =
        filterNot { interaction ->
            interaction.isCalendarLike() && interaction.occurredAt < cutoff
        }

    private fun PersonInteractionEntity.isCalendarLike(): Boolean =
        interactionKind == "calendar" || sourceType in CALENDAR_SOURCE_TYPES

    private companion object {
        val CALENDAR_SOURCE_TYPES: Set<String> = setOf(
            SourceType.GOOGLE_CALENDAR,
            SourceType.OUTLOOK_CALENDAR,
        )
    }
}

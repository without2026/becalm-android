package com.becalm.android.ui.persons

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
import kotlinx.datetime.Instant

// ─── UI models ────────────────────────────────────────────────────────────────

/**
 * A single row in the person's interaction timeline, discriminated by source type.
 */
public sealed class InteractionRow {

    /**
     * A raw ingestion event (voice, email, etc.) linked to this person.
     *
     * @property timestamp When the event was recorded.
     * @property source Source type string (e.g. "voice", "gmail").
     * @property summary Event title when available; null otherwise. The raw body snippet
     *   is intentionally excluded.
     * @property commitmentsExtractedCount Mirror of
     *   [RawIngestionEventEntity.commitmentsExtractedCount] — drives the
     *   "약속 추출 N건" badge on [InteractionHistoryRow] per SRC-008.
     */
    public data class Event(
        val timestamp: Instant,
        val source: String,
        val summary: String?,
        val commitmentsExtractedCount: Int = 0,
    ) : InteractionRow()

    /**
     * A commitment (give/take) linked to this person.
     *
     * @property timestamp When the source event occurred.
     * @property title Commitment title.
     * @property direction "give" or "take".
     * @property actionState v5 follow-through state. Valid values per
     *   [CommitmentEntity.actionState]: `"pending"`, `"reminded"`,
     *   `"followed_up"`, `"completed"`. Partition into the "completed"
     *   section happens on this column only — the legacy `commitment_state`
     *   column is intentionally ignored.
     */
    public data class Commitment(
        val timestamp: Instant,
        val title: String,
        val direction: String,
        val actionState: String,
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
    val companyName: String? = null,
    val jobTitle: String? = null,
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

/** v5 `action_state` terminal value that places a commitment in the "완료" section. */
internal const val ACTION_STATE_COMPLETED: String = "completed"

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
) : ViewModel() {

    private val personRef: String = savedStateHandle[ARG_PERSON_REF] ?: ""

    private val _uiState: MutableStateFlow<PersonDetailUiState> =
        MutableStateFlow(PersonDetailUiState(personRef = personRef))
    public val uiState: StateFlow<PersonDetailUiState> = _uiState.asStateFlow()

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
                        ) { enrichment, rawEvents, commitments, calendarEvents ->
                            val sections = buildInteractions(rawEvents, commitments, calendarEvents)
                            PersonDetailUiState(
                                personRef = personRef,
                                displayName = enrichment?.displayName,
                                companyName = enrichment?.company,
                                jobTitle = enrichment?.title,
                                pendingCommitments = sections.pendingCommitments,
                                completedCommitments = sections.completedCommitments,
                                interactionHistory = sections.interactionHistory,
                                loading = false,
                                error = null,
                            )
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

    /**
     * Container for the three UI sections produced by [buildInteractions].
     */
    private data class InteractionSections(
        val pendingCommitments: List<InteractionRow.Commitment>,
        val completedCommitments: List<InteractionRow.Commitment>,
        val interactionHistory: List<InteractionRow>,
    )

    /**
     * Splits raw events, commitments, and calendar meetings into three sections:
     * pending commitments, completed commitments, and non-commitment interaction history
     * (sorted newest-first).
     */
    private fun buildInteractions(
        rawEvents: List<RawIngestionEventEntity>,
        commitments: List<CommitmentEntity>,
        calendarEvents: List<CalendarEventEntity>,
    ): InteractionSections {
        val eventRows: List<InteractionRow> = rawEvents.map { e ->
            InteractionRow.Event(
                timestamp = e.timestamp,
                source = e.sourceType,
                summary = e.eventTitle,
                commitmentsExtractedCount = e.commitmentsExtractedCount,
            )
        }
        val commitmentRows: List<InteractionRow.Commitment> = commitments.map { c ->
            InteractionRow.Commitment(
                timestamp = c.sourceEventOccurredAt,
                title = c.title,
                direction = c.direction,
                actionState = c.actionState,
            )
        }
        val calendarRows: List<InteractionRow> = calendarEvents.map { m ->
            InteractionRow.CalendarMeeting(
                timestamp = m.startAt,
                title = m.title,
            )
        }
        // Partition on the v5 `action_state` column only — the legacy
        // `commitment_state` column is retained for schema parity but drifts on
        // the dispute/edit path (see CommitmentRepositoryImpl).
        val (completed, pending) = commitmentRows.partition { c ->
            c.actionState.equals(ACTION_STATE_COMPLETED, ignoreCase = true)
        }
        // history contains only Event + CalendarMeeting — commitmentRows are partitioned out
        // above. The Commitment branch is unreachable here; the else guards the invariant.
        val history = (eventRows + calendarRows)
            .sortedByDescending { row ->
                when (row) {
                    is InteractionRow.Event -> row.timestamp
                    is InteractionRow.CalendarMeeting -> row.timestamp
                    else -> error("history must not contain ${row::class.simpleName}")
                }
            }
        return InteractionSections(
            pendingCommitments = pending.sortedByDescending { it.timestamp },
            completedCommitments = completed.sortedByDescending { it.timestamp },
            interactionHistory = history,
        )
    }
}

package com.becalm.android.ui.persons

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.KST
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.components.isCalendarSource
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
    val sourceEventCards: List<SourceEventCardProjection> = emptyList(),
    val loading: Boolean = true,
    val error: UiMessage? = null,
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

    private fun calendarHistoryCutoff(): Instant =
        clock.today(KST).plus(DatePeriod(days = -1)).atStartOfDayIn(KST)

    private fun List<PersonInteractionEntity>.filterRecentCalendarHistory(
        cutoff: Instant,
    ): List<PersonInteractionEntity> =
        filterNot { interaction ->
            interaction.isCalendarLike() && interaction.occurredAt < cutoff
        }

    private fun PersonInteractionEntity.isCalendarLike(): Boolean =
        interactionKind == "calendar" || sourceType.isCalendarSource()
}

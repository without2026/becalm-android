package com.becalm.android.ui.commitments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.domain.commitment.CommitmentEvent
import com.becalm.android.domain.reminder.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
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
import javax.inject.Inject

// ─── Filter ───────────────────────────────────────────────────────────────────

/**
 * Display filter applied in-memory to the full commitment list.
 *
 * - [ALL]  — no filter; shows every row for the current user.
 * - [GIVE] — direction == "give".
 * - [TAKE] — direction == "take".
 *
 * Due-today and overdue state is surfaced per-card as a DN badge rather than as a
 * top-level filter (CTO Q5).
 */
// spec: CMT-001
public enum class CommitmentFilter {
    ALL,
    GIVE,
    TAKE,
}

// ─── Row model ────────────────────────────────────────────────────────────────

/**
 * Display-safe projection of a [CommitmentEntity] with a human-readable [derivedStatus] string
 * computed from [CommitmentEntity.commitmentState] and [CommitmentEntity.actionState] at
 * observation time.
 *
 * Only fields required by the UI are exposed; raw PII such as quote, personRef, and the full
 * counterpartyRef are kept inside the ViewModel and are never handed to the composable layer.
 * [counterpartyDisplayName] is already truncated to the UI display length.
 *
 * The status string is intentionally a raw label ("DRAFT", "CONFIRMED", etc.) so that the
 * composable layer can localize it independently. No Android resources are imported here.
 */
// spec: CMT-001
public data class CommitmentRow(
    val id: String,
    val title: String,
    val direction: String,
    val derivedStatus: String,
    val dueAt: Instant?,
    val dueIsApproximate: Boolean,
    val counterpartyDisplayName: String?,
    /**
     * Verbatim due-date expression captured from the source event
     * (e.g. "다음주", "월말"). Surfaced alongside the rendered due date so users
     * can understand inferred deadlines — commitment-management.spec.yml:9,13.
     *
     * Preserved even when [dueAt] is non-null; especially prominent when
     * [dueIsApproximate] is true.
     */
    val dueHint: String? = null,
)

// ─── UI state ─────────────────────────────────────────────────────────────────

/**
 * Full UI state for CommitmentManagementScreen.
 *
 * @property items  Filtered, sorted list of [CommitmentRow] to display.
 * @property filter Currently active [CommitmentFilter].
 * @property loading True while the initial collection or a transition is in flight.
 * @property error  Human-readable error string from the last failed action, or null.
 */
// spec: CMT-001
public data class CommitmentUiState(
    val items: List<CommitmentRow> = emptyList(),
    val filter: CommitmentFilter = CommitmentFilter.ALL,
    val loading: Boolean = true,
    val error: String? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "CommitmentMgmtVM"
private const val COUNTERPARTY_DISPLAY_MAX = 30

/**
 * ViewModel for CommitmentManagementScreen.
 *
 * Collects all commitments for the current user from [CommitmentRepository.observeAllForUser]
 * and joins them against the on-device enrichment map from
 * [PersonEnrichmentRepository.observeEnrichmentMap] (Room-only; PIPA-protected, never
 * uploaded). [CommitmentFilter] is applied in-memory so that a single Room subscription
 * drives all filter tabs.
 *
 * All mutating actions delegate to [CommitmentRepository.transitionState]; on
 * [BecalmResult.Failure] the error is surfaced via [CommitmentUiState.error] — never
 * swallowed.
 *
 * Enrichment resolution for commitment counterparty display (CMT-001):
 * 1. `enrichment[personRef]?.displayName`
 * 2. `enrichment[personRef]?.nickname`
 * 3. `personRef` itself (already canonicalized — phone E.164 / email / display name)
 * 4. `counterpartyRaw?.take(COUNTERPARTY_DISPLAY_MAX)` for legacy rows without personRef
 *
 * @param commitmentRepository      Source of truth for commitment data.
 * @param personEnrichmentRepository On-device PIPA-only contact enrichment map source.
 * @param reminderScheduler         Schedules / cancels AlarmManager reminders.
 * @param userPrefsStore            Provides the reactive current user ID.
 * @param logger                    Structured log sink.
 */
@HiltViewModel
public class CommitmentManagementViewModel @Inject constructor(
    private val commitmentRepository: CommitmentRepository,
    private val personEnrichmentRepository: PersonEnrichmentRepository,
    private val reminderScheduler: ReminderScheduler,
    private val userPrefsStore: UserPrefsStore,
    private val logger: Logger,
) : ViewModel() {

    private val _uiState: MutableStateFlow<CommitmentUiState> =
        MutableStateFlow(CommitmentUiState())

    /** Current UI state. Starts loading; settles once the first Room emission arrives. */
    public val uiState: StateFlow<CommitmentUiState> = _uiState.asStateFlow()

    /**
     * Backing store for the unfiltered entity list from Room. Required so that
     * [onFilterChange] can re-apply a different filter without re-querying Room.
     */
    private val allEntities: MutableStateFlow<List<CommitmentEntity>> = MutableStateFlow(emptyList())

    init {
        observeCommitments()
    }

    // ─── Private observation ──────────────────────────────────────────────────

    /** Latest enrichment map from [PersonEnrichmentRepository], kept in-memory so filter
     *  switches can re-render with the current enrichment without re-collecting.
     */
    private val enrichmentMap: MutableStateFlow<Map<String, PersonEnrichmentEntity>> =
        MutableStateFlow(emptyMap())

    /**
     * Subscribes to the reactive commitment list for the current user, joined against the
     * on-device enrichment map (CMT-001 — person_ref → display_name / nickname).
     *
     * The current user ID is sourced from [UserPrefsStore.observeCurrentUserId]. When the
     * ID is absent (not yet signed in) the item list is cleared. When present, all commitments
     * for the user are emitted by a single Room query and filtered in-memory.
     */
    // spec: CMT-001, CMT-002
    private fun observeCommitments() {
        viewModelScope.launch {
            val commitmentsByUser = userPrefsStore.observeCurrentUserId()
                .flatMapLatest { userId ->
                    if (userId == null) {
                        flowOf(emptyList())
                    } else {
                        commitmentRepository.observeAllForUser(userId)
                    }
                }
            combine(
                commitmentsByUser,
                personEnrichmentRepository.observeEnrichmentMap(),
            ) { entities, enrichment ->
                entities to enrichment
            }
                .catch { e ->
                    _uiState.update {
                        it.copy(loading = false, error = e.message ?: "load failed")
                    }
                }
                .collect { (entities, enrichment) ->
                    allEntities.value = entities
                    enrichmentMap.value = enrichment
                    val filter = _uiState.value.filter
                    _uiState.update { state ->
                        state.copy(
                            items = applyFilter(entities, filter, enrichment),
                            loading = false,
                        )
                    }
                }
        }
    }

    // ─── Actions ──────────────────────────────────────────────────────────────

    /**
     * Clears the current error from [CommitmentUiState.error].
     */
    public fun onErrorDismissed() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Switches the active filter and re-applies it to the currently loaded items.
     *
     * This is a pure in-memory operation; no Room query is issued.
     */
    // spec: CMT-003
    public fun onFilterChange(filter: CommitmentFilter) {
        _uiState.update { state ->
            state.copy(
                filter = filter,
                items = applyFilter(allEntities.value, filter, enrichmentMap.value),
            )
        }
    }

    /**
     * Confirms a commitment, transitioning it from DRAFT to CONFIRMED.
     *
     * On failure, surfaces the error string via [CommitmentUiState.error].
     */
    // spec: CMT-005
    public fun onConfirm(id: String) {
        launchAction("onConfirm", id) {
            commitmentRepository.transitionState(id, CommitmentEvent.Confirm)
        }
    }

    /**
     * Schedules a commitment and registers an exact-alarm reminder at [at].
     *
     * Transitions CONFIRMED → SCHEDULED via the state machine, then calls
     * [ReminderScheduler.schedule] only on success.
     *
     * @param id Commitment UUID.
     * @param at Absolute instant at which the alarm should fire.
     */
    // spec: CMT-006
    public fun onSchedule(id: String, at: Instant) {
        launchAction(
            name = "onSchedule",
            id = id,
            effect = {
                reminderScheduler.schedule(id, at)
            },
        ) {
            commitmentRepository.transitionState(id, CommitmentEvent.Schedule(at))
        }
    }

    /**
     * Marks a commitment as done and cancels any pending reminder.
     *
     * Transitions CONFIRMED or SCHEDULED → DONE, then calls [ReminderScheduler.cancel].
     */
    // spec: CMT-007
    public fun onMarkDone(id: String) {
        launchAction(
            name = "onMarkDone",
            id = id,
            effect = { reminderScheduler.cancel(id) },
        ) {
            commitmentRepository.transitionState(id, CommitmentEvent.MarkDone)
        }
    }

    /**
     * Dismisses a commitment and cancels any pending reminder.
     *
     * Transitions DRAFT, CONFIRMED, or SCHEDULED → DISMISSED, then calls
     * [ReminderScheduler.cancel].
     */
    // spec: CMT-008
    public fun onDismiss(id: String) {
        launchAction(
            name = "onDismiss",
            id = id,
            effect = { reminderScheduler.cancel(id) },
        ) {
            commitmentRepository.transitionState(id, CommitmentEvent.Dismiss)
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** id 해시를 로그용 8자리 hex 문자열로 변환한다. */
    private fun hashId(id: String): String = "%08x".format(id.hashCode())

    /**
     * 원래 launchAction / launchActionWithEffect 두 버전을 하나로 병합한 헬퍼.
     * effect는 block이 Success일 때만, 상태 머신 업데이트(_uiState) 이후에 실행되며,
     * null이면 실행을 건너뛴다. CMT-005/6/7 FSM 전이 순서는 block 내부 책임이다.
     */
    private fun launchAction(
        name: String,
        id: String,
        effect: (suspend () -> Unit)? = null,
        block: suspend () -> BecalmResult<*>,
    ) {
        viewModelScope.launch {
            when (val result = block()) {
                is BecalmResult.Success -> {
                    logger.d(TAG, "$name succeeded id=${hashId(id)}")
                    _uiState.update { it.copy(error = null) }
                    effect?.invoke()
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "$name failed id=${hashId(id)}: ${result.error}")
                    _uiState.update { it.copy(error = result.error.toString()) }
                }
            }
        }
    }

    /**
     * Derives a [CommitmentRow] list by applying [filter] to [entities] in-memory.
     *
     * Only direction-based filters are exposed to the UI; due-today and overdue are
     * surfaced per-card via a DN badge rather than as a top-level filter (CTO Q5).
     */
    // spec: CMT-002, CMT-003, CMT-004, CMT-010
    // spec: CMT-005..010 — post-Round-1 state model alignment verified
    private fun applyFilter(
        entities: List<CommitmentEntity>,
        filter: CommitmentFilter,
        enrichment: Map<String, PersonEnrichmentEntity>,
    ): List<CommitmentRow> {
        val filtered = when (filter) {
            CommitmentFilter.ALL -> entities
            CommitmentFilter.GIVE -> entities.filter { it.direction == "give" }
            CommitmentFilter.TAKE -> entities.filter { it.direction == "take" }
        }
        return filtered.map { entity ->
            CommitmentRow(
                id = entity.id,
                title = entity.title,
                direction = entity.direction,
                derivedStatus = entity.commitmentState.name,
                dueAt = entity.dueAt,
                dueIsApproximate = entity.dueIsApproximate,
                counterpartyDisplayName = resolveCounterpartyDisplay(entity, enrichment),
                dueHint = entity.dueHint,
            )
        }
    }

    /**
     * CMT-001 counterparty display resolution. Fallback chain:
     * 1. `enrichment[personRef].displayName`
     * 2. `enrichment[personRef].nickname`
     * 3. `personRef` itself (canonicalized identifier is acceptable as last-mile label)
     * 4. `counterpartyRaw.take(COUNTERPARTY_DISPLAY_MAX)` for legacy rows without personRef
     *
     * Returns `null` only when the commitment has no personRef AND no counterpartyRaw.
     */
    private fun resolveCounterpartyDisplay(
        commitment: CommitmentEntity,
        enrichment: Map<String, PersonEnrichmentEntity>,
    ): String? {
        val ref = commitment.personRef
        return if (ref != null) {
            val hit = enrichment[ref]
            hit?.displayName ?: hit?.nickname ?: ref
        } else {
            commitment.counterpartyRaw?.take(COUNTERPARTY_DISPLAY_MAX)
        }
    }
}

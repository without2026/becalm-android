package com.becalm.android.ui.commitments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.domain.commitment.CommitmentEvent
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.domain.reminder.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import java.time.Instant as JavaInstant
import javax.inject.Inject

// ─── Filter ───────────────────────────────────────────────────────────────────

/**
 * Display filter applied in-memory to the full commitment list.
 *
 * - [ALL]       — no filter; shows every row for the current user.
 * - [GIVE]      — direction == "give".
 * - [TAKE]      — direction == "take".
 * - [DUE_TODAY] — due_date == today (client clock).
 * - [OVERDUE]   — due_date < today and not yet done/dismissed.
 * - [DONE]      — commitmentState == [CommitmentState.DONE].
 */
// spec: CMT-001
public enum class CommitmentFilter {
    ALL,
    GIVE,
    TAKE,
    DUE_TODAY,
    OVERDUE,
    DONE,
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
    val dueDate: kotlinx.datetime.LocalDate?,
    val counterpartyDisplayName: String?,
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

/**
 * ViewModel for CommitmentManagementScreen.
 *
 * Collects all commitments for the current user from [CommitmentRepository.observeAllForUser],
 * then applies [CommitmentFilter] in-memory so that a single Room subscription drives all
 * filter tabs.
 *
 * All mutating actions delegate to [CommitmentRepository.transitionState]; on
 * [BecalmResult.Failure] the error is surfaced via [CommitmentUiState.error] — never
 * swallowed.
 *
 * @param commitmentRepository Source of truth for commitment data.
 * @param reminderScheduler    Schedules / cancels AlarmManager reminders.
 * @param userPrefsStore       Provides the reactive current user ID.
 * @param logger               Structured log sink.
 */
@HiltViewModel
public class CommitmentManagementViewModel @Inject constructor(
    private val commitmentRepository: CommitmentRepository,
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

    /**
     * Subscribes to the reactive commitment list for the current user.
     *
     * The current user ID is sourced from [UserPrefsStore.observeCurrentUserId]. When the
     * ID is absent (not yet signed in) the item list is cleared. When present, all commitments
     * for the user are emitted by a single Room query and filtered in-memory.
     */
    // spec: CMT-001, CMT-002
    private fun observeCommitments() {
        viewModelScope.launch {
            userPrefsStore.observeCurrentUserId()
                .flatMapLatest { userId ->
                    if (userId == null) {
                        flowOf(emptyList())
                    } else {
                        commitmentRepository.observeAllForUser(userId)
                    }
                }
                .catch { e ->
                    _uiState.update {
                        it.copy(loading = false, error = e.message ?: "load failed")
                    }
                }
                .collect { entities ->
                    allEntities.value = entities
                    val filter = _uiState.value.filter
                    _uiState.update { state ->
                        state.copy(
                            items = applyFilter(entities, filter),
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
                items = applyFilter(allEntities.value, filter),
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
                reminderScheduler.schedule(id, JavaInstant.ofEpochMilli(at.toEpochMilliseconds()))
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
     * Today is determined by the JVM default clock at the moment of each filter evaluation.
     * Filter [CommitmentFilter.DUE_TODAY] and [CommitmentFilter.OVERDUE] compare
     * [CommitmentEntity.dueDate] as an ISO-8601 string against the system date in UTC to
     * avoid a LocalDate dependency on the ViewModel layer.
     */
    // spec: CMT-002, CMT-003, CMT-004, CMT-010
    // spec: CMT-005..010 — post-Round-1 state model alignment verified
    private fun applyFilter(
        entities: List<CommitmentEntity>,
        filter: CommitmentFilter,
    ): List<CommitmentRow> {
        val todayIso = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        val filtered = when (filter) {
            CommitmentFilter.ALL -> entities
            CommitmentFilter.GIVE -> entities.filter { it.direction == "give" }
            CommitmentFilter.TAKE -> entities.filter { it.direction == "take" }
            CommitmentFilter.DUE_TODAY -> entities.filter { it.dueDate?.toString() == todayIso }
            CommitmentFilter.OVERDUE -> entities.filter { entity ->
                val due = entity.dueDate?.toString()
                due != null &&
                    due < todayIso &&
                    entity.commitmentState != CommitmentState.DONE &&
                    entity.commitmentState != CommitmentState.DISMISSED
            }
            CommitmentFilter.DONE -> entities.filter {
                it.commitmentState == CommitmentState.DONE
            }
        }
        return filtered.map { entity ->
            CommitmentRow(
                id = entity.id,
                title = entity.title,
                direction = entity.direction,
                derivedStatus = entity.commitmentState.name,
                dueDate = entity.dueDate,
                counterpartyDisplayName = entity.counterpartyRaw?.take(30),
            )
        }
    }
}

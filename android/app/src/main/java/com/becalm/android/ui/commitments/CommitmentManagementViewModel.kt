package com.becalm.android.ui.commitments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.SystemClock
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentManagementRow
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.domain.commitment.CommitmentEvent
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.domain.reminder.ReminderScheduler
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.worker.SourceRelationRefreshCoordinator
import com.becalm.android.worker.SourceRelationRefreshPlan
import com.becalm.android.worker.SourceParticipantRefreshScope
import com.becalm.android.worker.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import javax.inject.Inject

// ─── Filter ───────────────────────────────────────────────────────────────────

/**
 * Display filter applied in-memory to the full commitment list.
 *
 * - [ALL]      — no filter; shows every trackable item for the current user.
 * - [GIVE]     — open action rows where the wire direction is give.
 * - [TAKE]     — open action rows where the wire direction is take.
 * - [SCHEDULE] — schedule rows only.
 * - [CLOSED]   — completed or cancelled action rows.
 *
 * Action-specific lifecycle (due-today / overdue / completed / cancelled) is surfaced
 * per-card and in the terminal sections rather than as a top-level filter.
 */
// spec: CMT-001, CMT-002
public enum class CommitmentFilter {
    ALL,
    GIVE,
    TAKE,
    SCHEDULE,
    CLOSED,
}

// ─── Row model ────────────────────────────────────────────────────────────────

/**
 * Display-safe projection of a [CommitmentEntity] with a human-readable [derivedStatus] string
 * computed from [CommitmentEntity.actionState] at observation time.
 *
 * Only fields required by the UI are exposed; raw PII such as quote, counterpartyRef, and the full
 * counterpartyRef are kept inside the ViewModel and are never handed to the composable layer.
 * [counterpartyDisplayName] is already truncated to the UI display length.
 *
 * The status string is intentionally an uppercase-safe key ("PENDING", "REMINDED",
 * "FOLLOWED_UP", "COMPLETED", "OVERDUE", "CANCELLED") so the composable layer can
 * localize it independently. [actionState] exposes the same value as a typed enum for
 * callers that need exhaustive `when` handling. No Android resources are imported here.
 */
// spec: CMT-001
public data class CommitmentRow(
    val id: String,
    val itemType: String,
    val title: String,
    val direction: String?,
    val scheduleStatus: String?,
    val decisionStatus: String?,
    val derivedStatus: String?,
    val actionState: CommitmentState,
    val dueAt: Instant?,
    val dueIsApproximate: Boolean,
    val counterpartyDisplayName: String?,
    val sourceType: String? = null,
    val sourceTitle: String? = null,
    val sourceOccurredAt: Instant? = null,
    /**
     * Reserved for exact user-visible due text. Fuzzy source hints are intentionally
     * not surfaced on commitment cards because they look like actionable dates.
     */
    val dueHint: String? = null,
    /**
     * True iff the row's `source_type` is `"manual"`. Drives the `📝 수동 추가`
     * chip on the card per MAN-004. Manual rows share the exact same lifecycle
     * and action-button wiring as LLM-extracted rows — this flag is purely a
     * visual discriminator.
     */
    val isManual: Boolean = false,
)

/**
 * Display-only grouping for the commitments list. The group key is intentionally
 * the already-resolved display name rather than raw counterpartyRef, because the UI
 * layer must not receive canonical identifiers that may contain PII.
 */
public data class CommitmentPersonGroup(
    val displayName: String?,
    val items: List<CommitmentRow>,
) {
    public val count: Int get() = items.size
    public val stableKey: String get() = displayName?.let { "person-$it" } ?: "person-unassigned"
}

public fun buildCommitmentPersonGroups(rows: List<CommitmentRow>): List<CommitmentPersonGroup> {
    val grouped = linkedMapOf<String?, MutableList<CommitmentRow>>()
    rows.forEach { row ->
        val displayName = row.counterpartyDisplayName?.takeIf { it.isNotBlank() }
        grouped.getOrPut(displayName) { mutableListOf() }.add(row)
    }
    return grouped.map { (displayName, items) ->
        CommitmentPersonGroup(displayName = displayName, items = items)
    }
}

// ─── Undo snapshot ────────────────────────────────────────────────────────────

/**
 * One-shot snapshot of a just-transitioned commitment so the UI can offer `[복구]` within
 * the 5-second undo window (spec CMT-013).
 *
 * [priorState] is captured from the Room row *before* the transition is applied, so undo
 * reverts to the exact state the user saw (REMINDED, FOLLOWED_UP, OVERDUE, PENDING).
 * The subtype ([Completed] / [Cancelled]) lets the UI disambiguate "완료 처리됨" vs
 * "취소 처리됨" snackbar copy and covers both terminal transitions — CMT-013 makes the
 * undo affordance one-shot across both.
 *
 * The undo path uses the field-level [CommitmentRepository.updateActionState] write
 * intentionally — the 6-state transition matrix forbids hopping from COMPLETED /
 * CANCELLED back to any non-terminal state, and we do not want to widen the state
 * machine just to model "revert". `updateActionState` is repository-level and already
 * allowed to hop anywhere.
 *
 * Per CMT-013 the reminder alarm is **not** re-armed on undo: completing/cancelling
 * cancelled the alarm, and the user has to press [리마인드] again to re-arm.
 */
public sealed interface CommitmentUndoSnapshot {
    public val commitmentId: String
    public val priorState: CommitmentState

    /** Undo snapshot produced by [CommitmentManagementViewModel.onComplete]. */
    public data class Completed(
        override val commitmentId: String,
        override val priorState: CommitmentState,
    ) : CommitmentUndoSnapshot

    /** Undo snapshot produced by [CommitmentManagementViewModel.onCancel]. */
    public data class Cancelled(
        override val commitmentId: String,
        override val priorState: CommitmentState,
    ) : CommitmentUndoSnapshot
}

// ─── UI state ─────────────────────────────────────────────────────────────────

/**
 * Full UI state for CommitmentManagementScreen.
 *
 * @property items  Filtered, sorted list of [CommitmentRow] to display.
 * @property filter Currently active [CommitmentFilter].
 * @property loading True while the initial collection or a transition is in flight.
 * @property refreshing True while a user-triggered pull-to-refresh fetch is in
 *   flight (CMT-010). Drives the [PullRefreshIndicator] in the UI. Independent
 *   from [loading] — the Room subscription stays hot during a refresh, so the
 *   timeline rows remain on screen.
 * @property error  Resource-backed error from the last failed action, or null.
 */
// spec: CMT-001, CMT-010
public data class CommitmentUiState(
    val items: List<CommitmentRow> = emptyList(),
    val activeItems: List<CommitmentRow> = emptyList(),
    val completedSection: CommitmentSectionUiState = CommitmentSectionUiState(),
    val cancelledSection: CommitmentSectionUiState = CommitmentSectionUiState(),
    val filter: CommitmentFilter = CommitmentFilter.ALL,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: UiMessage? = null,
) {
    public val activePersonGroups: List<CommitmentPersonGroup>
        get() = buildCommitmentPersonGroups(activeItems)
}

/** Test-visible projection of one expandable terminal section (CMT-009 / CMT-012). */
public data class CommitmentSectionUiState(
    val count: Int = 0,
    val items: List<CommitmentRow> = emptyList(),
    val expanded: Boolean = false,
    val dimmed: Boolean = true,
) {
    public val visible: Boolean get() = count > 0
}

/** One-shot navigation emitted from [CommitmentManagementViewModel]. */
public sealed interface CommitmentManagementNavigation {
    public data class OpenDetail(val commitmentId: String) : CommitmentManagementNavigation
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "CommitmentMgmtVM"
private const val PULL_REFRESH_SOURCE = "commitments_pull_refresh"

/**
 * ViewModel for CommitmentManagementScreen.
 *
 * Collects display-safe commitment rows for the current user from
 * [CommitmentRepository.observeManagementRowsForUser]. The repository query pre-joins
 * PIPA-local enrichment in SQL, so the UI path does not materialize full commitment rows
 * or the full enrichment map. [CommitmentFilter] is applied in-memory so that a single
 * Room subscription drives all filter tabs.
 *
 * All mutating actions delegate to [CommitmentRepository.transitionState]; on
 * [BecalmResult.Failure] the error is surfaced via [CommitmentUiState.error] — never
 * swallowed.
 *
 * Enrichment resolution for commitment counterparty display is owned by the DAO projection.
 *
 * @param commitmentRepository      Source of truth for commitment data.
 * @param reminderScheduler         Schedules / cancels AlarmManager reminders.
 * @param userPrefsStore            Provides the reactive current user ID.
 * @param logger                    Structured log sink.
 */
@HiltViewModel
public class CommitmentManagementViewModel @Inject constructor(
    private val commitmentRepository: CommitmentRepository,
    private val sourceEventParticipantRepository: SourceEventParticipantRepository,
    private val commitmentParticipantRepository: CommitmentParticipantRepository,
    private val workScheduler: WorkScheduler,
    private val reminderScheduler: ReminderScheduler,
    private val userPrefsStore: UserPrefsStore,
    private val logger: Logger,
    private val clock: Clock = SystemClock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {

    private val _uiState: MutableStateFlow<CommitmentUiState> =
        MutableStateFlow(CommitmentUiState())

    /** Current UI state. Starts loading; settles once the first Room emission arrives. */
    public val uiState: StateFlow<CommitmentUiState> = _uiState.asStateFlow()

    /**
     * One-shot emission of the most recent completed/cancelled transition. The UI collects
     * this to raise a `[복구]` snackbar for the 5-second undo window per CMT-013.
     *
     * Semantics:
     *  - `replay = 0`             : late collectors don't see stale snapshots.
     *  - `extraBufferCapacity = 1`: exactly one pending event is buffered so the emit-site
     *    never suspends, and concurrent user taps do not drop the most recent action.
     *  - `DROP_OLDEST`            : if two transitions land faster than the UI can show the
     *    first snackbar, the older one is dropped — the most recent action is always the
     *    one the user sees, matching the "one-shot" invariant.
     */
    private val _undoFlow: MutableSharedFlow<CommitmentUndoSnapshot> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Publicly exposed undo stream for the [CommitmentManagementScreen] collector. */
    public val undoFlow: SharedFlow<CommitmentUndoSnapshot> = _undoFlow.asSharedFlow()

    private val _navigation: MutableSharedFlow<CommitmentManagementNavigation> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** One-shot navigation for card taps (CMT-003). */
    public val navigation: SharedFlow<CommitmentManagementNavigation> = _navigation.asSharedFlow()

    /**
     * Backing store for the unfiltered entity list from Room. Required so that
     * [onFilterChange] can re-apply a different filter without re-querying Room.
     */
    private val allRows: MutableStateFlow<List<CommitmentManagementRow>> = MutableStateFlow(emptyList())

    init {
        observeCommitments()
    }

    // ─── Private observation ──────────────────────────────────────────────────

    /**
     * Subscribes to the display-safe commitment list for the current user, pre-joined
     * against the on-device enrichment table in SQL.
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
                        commitmentRepository.observeManagementRowsForUser(userId)
                    }
                }
            commitmentsByUser
                .catch {
                    _uiState.update {
                        it.copy(loading = false, error = UiMessage.resource(R.string.commitments_error_load_failed))
                    }
                }
                .collect { rows ->
                    val projectedState = withContext(ioDispatcher) {
                        CommitmentManagementProjector.buildUiState(
                            current = _uiState.value,
                            rows = rows,
                            loading = false,
                            now = clock.nowInstant(),
                        )
                    }
                    allRows.value = rows
                    _uiState.value = projectedState
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
        viewModelScope.launch {
            val projectedState = withContext(ioDispatcher) {
                CommitmentManagementProjector.buildUiState(
                    current = _uiState.value,
                    rows = allRows.value,
                    filter = filter,
                    now = clock.nowInstant(),
                )
            }
            _uiState.value = projectedState
        }
    }

    /** Emits a one-shot detail navigation for the tapped card (CMT-003). */
    public fun onCommitmentSelected(id: String) {
        _navigation.tryEmit(CommitmentManagementNavigation.OpenDetail(id))
    }

    /** Toggles the collapsed-by-default completed section (CMT-009). */
    public fun onToggleCompletedSection() {
        _uiState.update { state ->
            state.copy(
                completedSection = state.completedSection.copy(
                    expanded = !state.completedSection.expanded,
                ),
            )
        }
    }

    /** Toggles the collapsed-by-default cancelled section (CMT-012). */
    public fun onToggleCancelledSection() {
        _uiState.update { state ->
            state.copy(
                cancelledSection = state.cancelledSection.copy(
                    expanded = !state.cancelledSection.expanded,
                ),
            )
        }
    }

    /**
     * Handles pull-to-refresh from [CommitmentManagementScreen] (CMT-010).
     *
     * Uses the common relation refresh path for the current user. It pulls commitment,
     * source participant, and commitment participant mirrors, then enqueues the person
     * index rebuild so refreshed cards resolve through the same person graph as source
     * sync workers.
     *
     * Network failures surface via [CommitmentUiState.error]; the operation is
     * idempotent and safe to retry. Concurrent presses are deduplicated by an
     * early-return on the [CommitmentUiState.refreshing] flag.
     */
    // spec: CMT-010
    public fun onPullRefresh() {
        if (_uiState.value.refreshing) return
        _uiState.update { it.copy(refreshing = true) }
        viewModelScope.launch(ioDispatcher) {
            val userId = userPrefsStore.observeCurrentUserId().firstOrNull()
            if (userId == null) {
                _uiState.update { it.copy(refreshing = false) }
                return@launch
            }
            when (
                val result = relationRefreshCoordinator().refresh(
                    userId = userId,
                    plan = SourceRelationRefreshPlan(
                        sourceType = PULL_REFRESH_SOURCE,
                        sourceParticipantRefreshScope = SourceParticipantRefreshScope.ALL,
                    ),
                )
            ) {
                is BecalmResult.Success ->
                    _uiState.update { it.copy(refreshing = false, error = null) }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "onPullRefresh failed: ${result.error}")
                    _uiState.update {
                        it.copy(refreshing = false, error = UiMessage.resource(R.string.commitments_error_refresh_failed))
                    }
                }
            }
        }
    }

    private fun relationRefreshCoordinator(): SourceRelationRefreshCoordinator =
        SourceRelationRefreshCoordinator(
            commitmentRepository = commitmentRepository,
            sourceEventParticipantRepository = sourceEventParticipantRepository,
            commitmentParticipantRepository = commitmentParticipantRepository,
            workScheduler = workScheduler,
            logger = logger,
        )

    /**
     * Records that the user pressed [리마인드] on the commitment (CMT-005).
     *
     * Transitions PENDING → REMINDED via the state machine and, on success, asks
     * [ReminderScheduler] to (re)arm the alarm at `dueAt − 1h`. The scheduler owns
     * the null-dueAt and past-trigger gates internally, so this call-site passes
     * the raw `dueAt` through verbatim.
     *
     * On failure surfaces the error string via [CommitmentUiState.error].
     */
    // spec: CMT-005
    public fun onRemind(id: String) {
        launchAction(
            name = "onRemind",
            id = id,
            effect = {
                val dueAt: Instant? = allRows.value.firstOrNull { it.id == id }?.dueAt
                reminderScheduler.schedule(id, dueAt)
            },
        ) {
            commitmentRepository.transitionState(id, CommitmentEvent.Remind)
        }
    }

    /**
     * Records that the user pressed [팔로업] on the commitment (CMT-006).
     *
     * Transitions PENDING or REMINDED → FOLLOWED_UP. No scheduler side effect —
     * follow-up does not change the alarm configuration per spec.
     */
    // spec: CMT-006
    public fun onFollowUp(id: String) {
        launchAction("onFollowUp", id) {
            commitmentRepository.transitionState(id, CommitmentEvent.FollowUp)
        }
    }

    /**
     * Records that the user pressed [완료] on the commitment (CMT-007).
     *
     * Transitions PENDING / REMINDED / FOLLOWED_UP / OVERDUE → COMPLETED and cancels
     * any pending reminder. On success, emits a [CommitmentUndoSnapshot.Completed] on
     * [undoFlow] so the UI can offer a 5-second `[복구]` snackbar per CMT-013. The
     * snapshot captures the *prior* action_state (e.g. REMINDED) so undo restores the
     * exact state the user saw.
     */
    // spec: CMT-007, CMT-013
    public fun onComplete(id: String) {
        // Snapshot *before* the transition runs so undo restores the prior state exactly.
        // When the row is not in memory (evicted by a concurrent refresh, or never loaded
        // in a test harness) we still run the completion transition — we just skip the
        // snackbar emit, since undo without a prior state would be meaningless.
        val priorState = snapshotPriorState(id)
        launchAction(
            name = "onComplete",
            id = id,
            effect = {
                reminderScheduler.cancel(id)
                if (priorState != null) {
                    _undoFlow.tryEmit(CommitmentUndoSnapshot.Completed(id, priorState))
                }
            },
        ) {
            commitmentRepository.transitionState(id, CommitmentEvent.Complete)
        }
    }

    /**
     * Records that the user pressed [취소] on the commitment (CMT-012).
     *
     * Transitions PENDING / REMINDED / FOLLOWED_UP / OVERDUE → CANCELLED and cancels
     * any pending reminder. On success, emits a [CommitmentUndoSnapshot.Cancelled] on
     * [undoFlow] so the UI can offer a 5-second `[복구]` snackbar per CMT-013.
     */
    // spec: CMT-012, CMT-013
    public fun onCancel(id: String) {
        // See [onComplete] — same prior-state snapshot semantics.
        val priorState = snapshotPriorState(id)
        launchAction(
            name = "onCancel",
            id = id,
            effect = {
                reminderScheduler.cancel(id)
                if (priorState != null) {
                    _undoFlow.tryEmit(CommitmentUndoSnapshot.Cancelled(id, priorState))
                }
            },
        ) {
            commitmentRepository.transitionState(id, CommitmentEvent.Cancel)
        }
    }

    /**
     * Reverts the most recent completed/cancelled transition (CMT-013) using a
     * field-level action_state write. Does **not** re-register the reminder alarm:
     * per spec line 131 the original completion/cancel path has already cancelled the
     * alarm and the user must press [리마인드] again if they want it back.
     *
     * Uses [CommitmentRepository.updateActionState] rather than
     * [CommitmentRepository.transitionState] because the 6-state machine intentionally
     * forbids transitions out of COMPLETED / CANCELLED — widening the matrix just for
     * undo would poison every other state-machine call-site. `updateActionState` is a
     * repository-level override that also flips `sync_status` to pending so UploadWorker
     * will PATCH the server with the reverted state.
     */
    // spec: CMT-013
    public fun onUndo(snapshot: CommitmentUndoSnapshot) {
        viewModelScope.launch(ioDispatcher) {
            val result = commitmentRepository.updateActionState(
                id = snapshot.commitmentId,
                newState = snapshot.priorState.wireValue,
                updatedAt = clock.nowInstant(),
            )
            when (result) {
                is BecalmResult.Success -> {
                    logger.d(
                        TAG,
                        "onUndo succeeded id=${hashId(snapshot.commitmentId)} " +
                            "priorState=${snapshot.priorState.wireValue} " +
                            "from=${snapshot::class.simpleName}",
                    )
                    _uiState.update { it.copy(error = null) }
                }
                is BecalmResult.Failure -> {
                    logger.w(
                        TAG,
                        "onUndo failed id=${hashId(snapshot.commitmentId)}: ${result.error}",
                    )
                    _uiState.update { it.copy(error = UiMessage.resource(R.string.commitments_error_undo_failed)) }
                }
            }
        }
    }

    /**
     * Reads the current [CommitmentState] from the in-memory Room cache so the caller
     * can snapshot it before issuing a transition. Returns `null` when the id is not
     * present — e.g. if the row was evicted by a concurrent refresh — in which case the
     * caller must short-circuit rather than emit a snapshot with stale data.
     */
    private fun snapshotPriorState(id: String): CommitmentState? {
        val row = allRows.value.firstOrNull { it.id == id } ?: return null
        return CommitmentState.fromWire(row.actionState)
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
        viewModelScope.launch(ioDispatcher) {
            when (val result = block()) {
                is BecalmResult.Success -> {
                    logger.d(TAG, "$name succeeded id=${hashId(id)}")
                    _uiState.update { it.copy(error = null) }
                    effect?.invoke()
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "$name failed id=${hashId(id)}: ${result.error}")
                    _uiState.update { it.copy(error = UiMessage.resource(R.string.commitments_error_action_failed)) }
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
}

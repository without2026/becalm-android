package com.becalm.android.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import javax.inject.Inject

// ─── UI types ─────────────────────────────────────────────────────────────────

/**
 * UI state for the cold-sync splash shown on first run (TDY-008..010).
 *
 * @param overallProgress Aggregate progress in [0f, 1f]; 1f when all sources have
 *   finished their first sync (either [SourceConnectionStatus.CONNECTED] or
 *   [SourceConnectionStatus.ERROR]).
 * @param perSourceProgress Per-source progress in [0f, 1f] keyed by [SourceType] string.
 *   0f = never started or syncing; 1f = first sync complete (success or error).
 * @param done True when [overallProgress] == 1f; callers should navigate away.
 */
public data class ColdSyncUiState(
    val overallProgress: Float = 0f,
    val perSourceProgress: Map<String, Float> = emptyMap(),
    val done: Boolean = false,
    val skipEnabled: Boolean = false,
    val transitioning: Boolean = false,
    val transitionError: Boolean = false,
    val lastTransition: ColdSyncTransitionSnapshot? = null,
)

/** One-shot effects emitted by [ColdSyncViewModel]. */
public sealed interface ColdSyncEffect {
    public data object NavigateToToday : ColdSyncEffect
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "ColdSyncViewModel"

/**
 * ViewModel for the cold-sync loading screen (TDY-008..010).
 *
 * Shown only on first run when no data is present. Observes [SourceStatusRepository.observeAll]
 * and derives a per-source progress value:
 * - [SourceConnectionStatus.NEVER_CONNECTED] or [SourceConnectionStatus.SYNCING] → 0f
 * - [SourceConnectionStatus.CONNECTED] or [SourceConnectionStatus.ERROR] → 1f
 *
 * [ColdSyncUiState.done] becomes true when every source has reached 1f progress.
 */
@HiltViewModel
public class ColdSyncViewModel @Inject constructor(
    private val sourceStatusRepository: SourceStatusRepository,
    private val lifecycleCoordinator: ColdSyncLifecycleCoordinator,
    private val runtimeCoordinator: ColdSyncRuntimeCoordinator,
    private val userPrefsStore: UserPrefsStore,
    private val logger: Logger,
) : ViewModel() {

    private val skipEnabledFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val transitioningFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val transitionErrorFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val transitionSnapshotFlow: MutableStateFlow<ColdSyncTransitionSnapshot?> = MutableStateFlow(null)
    private val _effects: MutableSharedFlow<ColdSyncEffect> = MutableSharedFlow(extraBufferCapacity = 1)
    private val backingState: MutableStateFlow<ColdSyncUiState> = MutableStateFlow(ColdSyncUiState())
    private var skipTimerStarted: Boolean = false
    private var stage1Started: Boolean = false

    /** One-shot navigation emitted after persistence/scheduling succeeds. */
    public val effects: SharedFlow<ColdSyncEffect> = _effects.asSharedFlow()

    /**
     * Observable state consumed by the cold-sync composable.
     */
    public val state: StateFlow<ColdSyncUiState> = backingState.asStateFlow()

    /** Starts the skip countdown once the cold-sync screen becomes visible. */
    public fun onScreenVisible() {
        ensureSkipTimerStarted()
        if (stage1Started) return
        stage1Started = true
        viewModelScope.launch {
            transitionErrorFlow.value = false
            when (val result = runtimeCoordinator.startStage1(Instant.fromEpochMilliseconds(System.currentTimeMillis()))) {
                is BecalmResult.Success -> logger.d(TAG, "Stage 1 started")
                is BecalmResult.Failure -> {
                    logger.w(TAG, "Stage 1 start failed: ${result.error}")
                    transitionErrorFlow.value = true
                }
            }
        }
    }

    private val derivedState = combine(
        sourceStatusRepository.observeAll(),
        runtimeCoordinator.observeUserProfileReady(),
        userPrefsStore.observeEnabledSources(),
        skipEnabledFlow,
        transitioningFlow,
    ) { statuses, userProfileReady, enabledSources, skipEnabled, transitioning ->
            CombinedFlowValues(statuses, userProfileReady, enabledSources, skipEnabled, transitioning)
        }
        .combine(transitionSnapshotFlow) { values, lastTransition ->
            values to lastTransition
        }
        .combine(transitionErrorFlow) { (values, lastTransition), transitionError ->
            buildUiState(
                values.statuses,
                values.userProfileReady,
                values.enabledSources,
                values.skipEnabled,
                values.transitioning,
                transitionError,
                lastTransition,
            )
        }
        .catch { e ->
            logger.e(TAG, "source status observation failed", e as? Exception ?: Exception(e))
            emit(ColdSyncUiState())
        }

    init {
        logger.d(TAG, "init")
        viewModelScope.launch {
            derivedState.collect { backingState.value = it }
        }
    }

    /** COLD-003 / COLD-008 transition after Stage 1 reaches a terminal state. */
    public fun onStage1Completed(at: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())) {
        viewModelScope.launch {
            transitioningFlow.value = true
            transitionErrorFlow.value = false
            when (val result = lifecycleCoordinator.completeStage1(at)) {
                is BecalmResult.Success -> {
                    transitionSnapshotFlow.value = result.value
                    _effects.tryEmit(ColdSyncEffect.NavigateToToday)
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "completeStage1 failed: ${result.error}")
                    transitionErrorFlow.value = true
                }
            }
            transitioningFlow.value = false
        }
    }

    /** COLD-006 / COLD-008 "[나중에 하기]" branch. */
    public fun onSkipForNow(at: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())) {
        if (!skipEnabledFlow.value) return
        viewModelScope.launch {
            transitioningFlow.value = true
            transitionErrorFlow.value = false
            when (val result = lifecycleCoordinator.deferStage1(at)) {
                is BecalmResult.Success -> {
                    transitionSnapshotFlow.value = result.value
                    _effects.tryEmit(ColdSyncEffect.NavigateToToday)
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "deferStage1 failed: ${result.error}")
                    transitionErrorFlow.value = true
                }
            }
            transitioningFlow.value = false
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Maps a [SourceConnectionStatus] to a progress fraction.
     *
     * NEVER_CONNECTED and SYNCING are treated as in-progress (0f).
     * CONNECTED and ERROR are treated as complete (1f) — an error still counts as
     * "done trying" for the purpose of dismissing the cold-sync screen.
     */
    private fun sourceProgress(status: SourceConnectionStatus): Float = when (status) {
        SourceConnectionStatus.NEVER_CONNECTED,
        SourceConnectionStatus.SYNCING,
        -> 0f

        SourceConnectionStatus.CONNECTED,
        SourceConnectionStatus.ERROR,
        -> 1f
    }

    private fun buildUiState(
        statuses: List<com.becalm.android.data.repository.SourceStatus>,
        userProfileReady: Boolean,
        enabledSources: Set<String>,
        skipEnabled: Boolean,
        transitioning: Boolean,
        transitionError: Boolean,
        lastTransition: ColdSyncTransitionSnapshot?,
    ): ColdSyncUiState {
        // Only sources the user actually enabled during onboarding can ever leave
        // NEVER_CONNECTED — gating `done` on the full STAGE1_TRACKED_SOURCES set
        // would strand the screen forever for users who skip any source.
        // Mirrors the filter used by ColdSyncRuntimeCoordinator.startStage1.
        val activeTracked = STAGE1_TRACKED_SOURCES intersect enabledSources
        val sourceProgress = statuses
            .asSequence()
            .filter { it.sourceType in activeTracked }
            .associate { status -> status.sourceType to sourceProgress(status.status) }
        val trackedProgress = linkedMapOf<String, Float>().apply {
            putAll(sourceProgress)
            put(
                DefaultColdSyncRuntimeCoordinator.USER_PROFILE_SOURCE_ID,
                if (userProfileReady) 1f else 0f,
            )
        }
        val overall = if (trackedProgress.isEmpty()) 0f else trackedProgress.values.average().toFloat()
        return ColdSyncUiState(
            overallProgress = overall,
            perSourceProgress = trackedProgress,
            done = trackedProgress.isNotEmpty() && trackedProgress.values.all { it >= 1f },
            skipEnabled = skipEnabled,
            transitioning = transitioning,
            transitionError = transitionError,
            lastTransition = lastTransition,
        )
    }

    private data class CombinedFlowValues(
        val statuses: List<com.becalm.android.data.repository.SourceStatus>,
        val userProfileReady: Boolean,
        val enabledSources: Set<String>,
        val skipEnabled: Boolean,
        val transitioning: Boolean,
    )

    private companion object {
        private const val SKIP_ENABLE_DELAY_MS: Long = 5_000L
        private val STAGE1_TRACKED_SOURCES: Set<String> = setOf(
            SourceType.GMAIL,
            SourceType.OUTLOOK_MAIL,
            SourceType.NAVER_IMAP,
            SourceType.DAUM_IMAP,
            SourceType.GOOGLE_CALENDAR,
            SourceType.OUTLOOK_CALENDAR,
        )
    }

    private fun ensureSkipTimerStarted() {
        if (skipTimerStarted) return
        skipTimerStarted = true
        viewModelScope.launch {
            delay(SKIP_ENABLE_DELAY_MS)
            skipEnabledFlow.value = true
        }
    }
}

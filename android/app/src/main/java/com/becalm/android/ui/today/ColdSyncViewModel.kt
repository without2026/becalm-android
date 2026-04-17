package com.becalm.android.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.util.Logger
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
)

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
    private val logger: Logger,
) : ViewModel() {

    /**
     * Observable state consumed by the cold-sync composable.
     */
    public val state: StateFlow<ColdSyncUiState> = sourceStatusRepository.observeAll()
        .map { statuses ->
            if (statuses.isEmpty()) {
                return@map ColdSyncUiState()
            }
            val perSource = statuses.associate { s ->
                s.sourceType to sourceProgress(s.status)
            }
            val overall = perSource.values.average().toFloat()
            ColdSyncUiState(
                overallProgress = overall,
                perSourceProgress = perSource,
                done = perSource.values.all { it >= 1f },
            )
        }
        .catch { e ->
            logger.e(TAG, "source status observation failed", e as? Exception ?: Exception(e))
            emit(ColdSyncUiState())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ColdSyncUiState(),
        )

    init {
        logger.d(TAG, "init")
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
}

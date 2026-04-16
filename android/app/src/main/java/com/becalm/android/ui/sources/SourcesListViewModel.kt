package com.becalm.android.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.util.Logger
import com.becalm.android.data.repository.SourceStatusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Instant
import javax.inject.Inject

// ─── UI types ─────────────────────────────────────────────────────────────────

/**
 * A single row in the sources list (SMG-001..005).
 *
 * @param sourceType One of the [com.becalm.android.data.remote.dto.SourceType] string constants.
 * @param status Human-readable connection status label derived from
 *   [com.becalm.android.data.repository.SourceConnectionStatus.name].
 * @param lastSyncAt Wall-clock instant of the last successful sync, or null if never synced.
 * @param lastError Human-readable error from the last failed sync, or null when healthy.
 * @param itemsCount Reserved for future use; always 0 in the current data model because
 *   no per-source count query exists. Surfaced in the UI spec but not yet backed by data.
 */
public data class SourceStatusRow(
    val sourceType: String,
    val status: String,
    val lastSyncAt: Instant?,
    val lastError: String?,
    val itemsCount: Int,
)

/**
 * UI state for the sources list screen.
 *
 * @param items List of [SourceStatusRow] in [com.becalm.android.data.remote.dto.SourceType.ALL] order.
 */
public data class SourcesListUiState(
    val items: List<SourceStatusRow> = emptyList(),
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "SourcesListViewModel"

/**
 * ViewModel for the sources list screen (SMG-001..005).
 *
 * Observes [SourceStatusRepository.observeAll] and projects each [SourceStatus] to a
 * [SourceStatusRow].
 *
 * ## Disconnect
 * [SourceStatusRepository] has no `disconnect(sourceType)` method in the current API.
 * The disconnect flow (SMG-004) is deferred: [disconnectSource] logs a warning and is a
 * no-op until a server-side revoke endpoint and corresponding repository method are added.
 */
@HiltViewModel
public class SourcesListViewModel @Inject constructor(
    private val sourceStatusRepository: SourceStatusRepository,
    private val logger: Logger,
) : ViewModel() {

    /**
     * Observable state consumed by the sources list composable.
     */
    public val state: StateFlow<SourcesListUiState> = sourceStatusRepository.observeAll()
        .map { statuses ->
            SourcesListUiState(
                items = statuses.map { s ->
                    SourceStatusRow(
                        sourceType = s.sourceType,
                        status = s.status.name,
                        lastSyncAt = s.lastSyncedAt,
                        lastError = s.errorMessage,
                        itemsCount = 0, // SMG-002: no per-source count query exists yet
                    )
                },
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SourcesListUiState(),
        )

    init {
        logger.d(TAG, "init")
    }

    /**
     * Initiates a disconnect for [sourceType].
     *
     * API gap: [SourceStatusRepository] has no `disconnect` method. This call is logged
     * and is a no-op until SMG-004 is implemented with a server-side revoke endpoint.
     *
     * @param sourceType One of the [com.becalm.android.data.remote.dto.SourceType] constants.
     */
    public fun disconnectSource(sourceType: String) {
        // SMG-004: SourceStatusRepository.disconnect() does not exist in the current API.
        // When a revoke endpoint is added, replace this with a viewModelScope.launch call
        // that delegates to the repository.
        logger.w(TAG, "disconnectSource called for $sourceType but no API method exists yet (SMG-004)")
    }
}

package com.becalm.android.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.util.Logger
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.SourceStatusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
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
    val enrichedCount: Int? = null,
)

/**
 * UI state for the sources list screen.
 *
 * @param items List of [SourceStatusRow] in
 *   [com.becalm.android.data.remote.dto.SourceType.PRODUCT_SOURCES] order (prepended by
 *   the pseudo-`contacts` row). The schema-wide
 *   [com.becalm.android.data.remote.dto.SourceType.ALL] set is deliberately not used —
 *   wave-0 carves out `CALL_RECORDING` (no UI tile yet) and `VOICE` (captured locally).
 */
public data class SourcesListUiState(
    val items: List<SourceStatusRow> = emptyList(),
)

/** One-shot navigation effects emitted by [SourcesListViewModel]. */
public sealed interface SourcesListNavigation {
    /** Navigate to a concrete source detail route under `/settings/sources/{source_id}`. */
    public data class SourceDetail(val sourceType: String) : SourcesListNavigation

    /** Navigate to the READ_CONTACTS permission screen when the pseudo-source is unavailable. */
    public data object ContactsPermission : SourcesListNavigation

    /** Navigate to the contacts pseudo-source detail screen when permission is granted. */
    public data object ContactsDetail : SourcesListNavigation
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "SourcesListViewModel"

/**
 * ViewModel for the sources list screen (SMG-001..005).
 *
 * Observes [SourceStatusRepository.observeAll] and projects each [SourceStatus] to a
 * [SourceStatusRow].
 *
 * Disconnect / reconnect actions are owned by the detail screen; this list surface only
 * projects rows and resolves row-tap navigation.
 */
@HiltViewModel
public class SourcesListViewModel @Inject constructor(
    private val sourceStatusRepository: SourceStatusRepository,
    private val personEnrichmentRepository: PersonEnrichmentRepository,
    private val contactsPermissionChecker: ContactsPermissionChecker,
    private val logger: Logger,
) : ViewModel() {

    private val _navigation: MutableSharedFlow<SourcesListNavigation> =
        MutableSharedFlow(extraBufferCapacity = 1)

    /** One-shot navigation stream for row taps. */
    public val navigation: SharedFlow<SourcesListNavigation> = _navigation.asSharedFlow()

    /**
     * Observable state consumed by the sources list composable.
     */
    public val state: StateFlow<SourcesListUiState> = combine(
        sourceStatusRepository.observeAll(),
        personEnrichmentRepository.observeSummary(),
        contactsPermissionChecker.observeGrantState(),
    ) { statuses, enrichmentSummary, permissionGranted ->
            SourcesListProjector.buildState(
                statuses = statuses,
                enrichmentSummary = enrichmentSummary,
                permissionGranted = permissionGranted,
            )
        }
        .catch { e ->
            logger.e(TAG, "observeAll failed", e as? Exception ?: Exception(e))
            emit(SourcesListUiState())
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
     * Routes a tapped source row to the appropriate destination.
     *
     * The contacts pseudo-source is permission-gated and does not share the same route as
     * sync-backed sources, so its branch is surfaced here rather than hard-coded in Compose.
     */
    public fun onSourceSelected(sourceType: String) {
        val target = SourcesListNavigationResolver.resolve(
            sourceType = sourceType,
            contactsPermissionGranted = contactsPermissionChecker.isGranted(),
        )
        _navigation.tryEmit(target)
    }

}

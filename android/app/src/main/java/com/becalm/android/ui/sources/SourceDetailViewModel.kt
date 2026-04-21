package com.becalm.android.ui.sources

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Instant
import javax.inject.Inject

// ─── Navigation argument key ──────────────────────────────────────────────────

/** Nav-graph argument key used to pass the source type string to this screen. */
public const val ARG_SOURCE_TYPE: String = "source_id"

// ─── UI types ─────────────────────────────────────────────────────────────────

/**
 * PII-safe summary row for the recent-events list on SourceDetailScreen.
 *
 * Excludes [com.becalm.android.data.local.db.entity.RawIngestionEventEntity.eventSnippet]
 * and [com.becalm.android.data.local.db.entity.RawIngestionEventEntity.personRef]
 * so that Compose state snapshots cannot leak email-body content or raw counterparty
 * identifiers into Crashlytics or LeakCanary retained-object reports.
 *
 * @property id        Primary-key UUID of the source entity.
 * @property timestamp When the event was recorded.
 * @property title     Event title when available; null otherwise.
 */
public data class RecentEventSummary(
    val id: String,
    val timestamp: Instant,
    val title: String?,
)

/**
 * UI state for the source detail screen (SMG-003..005).
 *
 * @param sourceType The [com.becalm.android.data.remote.dto.SourceType] string for this screen.
 * @param status Human-readable connection status label.
 * @param recentEvents Up to 50 most-recent raw ingestion events for this source, newest-first.
 * @param error Non-null when the sourceType argument is absent or blank.
 */
public data class SourceDetailUiState(
    val sourceType: String = "",
    val status: String = "",
    val recentEvents: List<RecentEventSummary> = emptyList(),
    val error: String? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "SourceDetailViewModel"

/** Maximum number of recent events shown in the detail view (SMG-005). */
private const val RECENT_EVENTS_LIMIT = 50

/**
 * ViewModel for the source detail screen (SMG-003..005).
 *
 * Reads [sourceType] from [SavedStateHandle] (nav-graph argument [ARG_SOURCE_TYPE]),
 * observes sync health for that source, and streams the 50 most-recent raw ingestion
 * events that match [sourceType].
 *
 * ## API gap — per-source event query
 * [RawIngestionRepository] exposes no `observeForSourceType(userId, sourceType)` method.
 * The DAO also has no source_type predicate query. As a workaround, this ViewModel uses
 * [RawIngestionRepository.observeTimelineForUser] with an over-fetched limit and then
 * filters by [sourceType] in-memory. A dedicated DAO query should replace this in a
 * future sprint to avoid a full-table scan on large datasets.
 *
 * ## userId
 * The SP-41 spec does not inject [com.becalm.android.data.repository.AuthRepository] into
 * this ViewModel. The userId is therefore supplied by the host composable via [setUserId].
 * Until [setUserId] is called the events list remains empty.
 */
@HiltViewModel
public class SourceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sourceStatusRepository: SourceStatusRepository,
    private val rawIngestionRepository: RawIngestionRepository,
    private val logger: Logger,
) : ViewModel() {

    private val sourceType: String = savedStateHandle[ARG_SOURCE_TYPE] ?: ""

    /**
     * Must be populated by the host screen with the authenticated userId before events
     * are emitted. Required because [com.becalm.android.data.repository.AuthRepository]
     * is outside this ViewModel's scope.
     */
    private val _userId = MutableStateFlow<String?>(null)

    /** Provide the authenticated userId so that event queries are scoped per-user. */
    public fun setUserId(userId: String) {
        _userId.value = userId
    }

    // Events flow: flatMapLatest on userId so it resubscribes when the user changes.
    // API gap: filter by sourceType in-memory (see class KDoc).
    private val eventsFlow = _userId.flatMapLatest { userId ->
        if (userId == null) return@flatMapLatest flowOf(emptyList())
        rawIngestionRepository.observeTimelineForUser(userId, limit = RECENT_EVENTS_LIMIT * 4)
    }

    private val statusFlow = if (sourceType.isBlank()) {
        flowOf(null)
    } else {
        sourceStatusRepository.observeFor(sourceType)
    }

    /**
     * Observable state consumed by the source detail composable.
     *
     * When [sourceType] is blank (missing nav argument), the state immediately shows
     * an error and no repository flows are subscribed.
     */
    public val state: StateFlow<SourceDetailUiState> = if (sourceType.isBlank()) {
        MutableStateFlow(
            SourceDetailUiState(error = "sourceType argument is missing or blank"),
        )
    } else if (sourceType !in SourceType.ALL) {
        // Schema-level guard — any value not declared in data-model.yml's source_type
        // enum is rejected. We use ALL (not PRODUCT_SOURCES) so deep links to VOICE or
        // (future) CALL_RECORDING detail routes remain navigable once their UI ships.
        MutableStateFlow(
            SourceDetailUiState(error = "Invalid source type"),
        )
    } else {
        combine(
            statusFlow,
            eventsFlow,
        ) { status, allEvents ->
            // API gap: in-memory filter; see class KDoc.
            val filtered = allEvents
                .filter { it.sourceType == sourceType }
                .take(RECENT_EVENTS_LIMIT)
                .map { entity ->
                    RecentEventSummary(
                        id = entity.id,
                        timestamp = entity.timestamp,
                        title = entity.eventTitle,
                    )
                }
            SourceDetailUiState(
                sourceType = sourceType,
                status = status?.status?.name ?: "",
                recentEvents = filtered,
                error = null,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SourceDetailUiState(sourceType = sourceType),
        )
    }

    init {
        logger.d(TAG, "init sourceType=$sourceType")
        if (sourceType.isBlank()) {
            logger.e(TAG, "sourceType argument missing from SavedStateHandle")
        } else if (sourceType !in SourceType.ALL) {
            logger.w(TAG, "rejected unknown sourceType")
        }
    }

    override fun onCleared() {
        super.onCleared()
        logger.d(TAG, "cleared sourceType=$sourceType")
    }
}

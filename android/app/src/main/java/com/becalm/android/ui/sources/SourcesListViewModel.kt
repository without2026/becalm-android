package com.becalm.android.ui.sources

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.AuthState
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.worker.SourceConnectionLocalStateHydrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import javax.inject.Inject

// ─── UI types ─────────────────────────────────────────────────────────────────

/**
 * A single row in the sources list (SMG-001..005).
 *
 * @param sourceType One of the [com.becalm.android.data.remote.dto.SourceType] string constants.
 * @param status Typed UI status used by shared source-status indicators.
 * @param lastSyncAt Wall-clock instant of the last successful sync, or null if never synced.
 * @param hasError True when the repository reported a failure; raw error copy is not carried
 *   into UI state.
 * @param processingLabelRes Optional processing-phase label. Source connection health remains
 *   in [status]; processing progress is surfaced separately so "connected" is not confused with
 *   "all source data has been mirrored and organized".
 */
public data class SourceStatusRow(
    val sourceType: String,
    val status: SourceSyncStatus,
    val lastSyncAt: Instant?,
    val hasError: Boolean,
    val enrichedCount: Int? = null,
    val help: UiMessage? = null,
    @StringRes val recommendedActionLabelRes: Int? = null,
    @StringRes val processingLabelRes: Int? = null,
    val processingMessage: UiMessage? = null,
    val processingNeedsAction: Boolean = false,
)

/**
 * UI state for the sources list screen.
 *
 * @param items List of [SourceStatusRow] in
 *   [com.becalm.android.data.remote.dto.SourceType.PRODUCT_SOURCES] order (prepended by
 *   the pseudo-`contacts` row). The schema-wide
 *   [com.becalm.android.data.remote.dto.SourceType.ALL] set is deliberately not used.
 *   `VOICE`, `CALL_RECORDING`, and `MEETING` are shown as separate local audio sources,
 *   even when they share one Recordings folder grant.
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
    private val authRepository: AuthRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val personEnrichmentRepository: PersonEnrichmentRepository,
    private val contactsPermissionChecker: ContactsPermissionChecker,
    private val userPrefsStore: UserPrefsStore,
    private val sourceConnectionLocalStateHydrator: SourceConnectionLocalStateHydrator,
    private val sourceSyncPort: SourceSyncPort,
    private val logger: Logger,
) : ViewModel() {

    private val _navigation: MutableSharedFlow<SourcesListNavigation> =
        MutableSharedFlow(extraBufferCapacity = 1)

    /** One-shot navigation stream for row taps. */
    public val navigation: SharedFlow<SourcesListNavigation> = _navigation.asSharedFlow()
    private var refreshStatusesJob: Job? = null
    private val handledSourceConnectionResults = mutableSetOf<String>()

    /**
     * Observable state consumed by the sources list composable.
     */
    public val state: StateFlow<SourcesListUiState> = authRepository.observeAuthState()
        .flatMapLatest { authState ->
            if (authState !is AuthState.Authenticated) {
                return@flatMapLatest flowOf(SourcesListUiState())
            }
            combine(
                sourceStatusRepository.observeAll(),
                processingStatusRepository.observeAll(),
                personEnrichmentRepository.observeSummary(),
                contactsPermissionChecker.observeGrantState(),
                userPrefsStore.observeContactsConsent(),
            ) { statuses, processingStates, enrichmentSummary, permissionGranted, contactsConsented ->
                SourcesListProjector.buildState(
                    statuses = statuses,
                    processingStates = processingStates,
                    enrichmentSummary = enrichmentSummary,
                    permissionGranted = permissionGranted,
                    contactsConsented = contactsConsented,
                )
            }
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
        viewModelScope.launch {
            val target = SourcesListNavigationResolver.resolve(
                sourceType = sourceType,
                contactsPermissionGranted = contactsPermissionChecker.isGranted(),
                contactsConsented = userPrefsStore.observeContactsConsent().first(),
            )
            _navigation.tryEmit(target)
        }
    }

    /** Refreshes server-authoritative source state when the list becomes visible again. */
    public fun refreshStatuses() {
        if (refreshStatusesJob?.isActive == true) return
        refreshStatusesJob = viewModelScope.launch {
            hydrateSourceState()
        }
    }

    /**
     * Handles backend OAuth completion links that land on Settings > Sources.
     *
     * A completed browser callback only proves that the provider connection row exists. It does
     * not prove provider fetch, source-event persistence, or next-action projection. Trigger one
     * immediate backend-managed sync here so Settings behaves like onboarding instead of waiting
     * for the next periodic worker pass.
     */
    public fun onSourceConnectionResult(
        result: String?,
        provider: String?,
        family: String?,
    ) {
        val key = listOf(result.orEmpty(), provider.orEmpty(), family.orEmpty()).joinToString("|")
        if (!handledSourceConnectionResults.add(key)) return
        viewModelScope.launch {
            hydrateSourceState()
            val sourceType = sourceTypeForSuccessfulOAuthResult(
                result = result,
                provider = provider,
                family = family,
            ) ?: return@launch
            when (sourceSyncPort.requestManualSync(sourceType)) {
                is BecalmResult.Success -> logger.d(TAG, "post-OAuth source sync requested sourceType=$sourceType")
                is BecalmResult.Failure -> logger.w(TAG, "post-OAuth source sync failed sourceType=$sourceType")
            }
        }
    }

    private suspend fun hydrateSourceState() {
        val userId = authRepository.currentSession()?.userId
        if (userId.isNullOrBlank()) {
            logger.w(TAG, "source status refresh skipped without authenticated user")
            return
        }
        runCatching {
            sourceConnectionLocalStateHydrator.hydrate(userId)
        }.onSuccess {
            logger.d(TAG, "source connection local state hydrated from sources list")
        }.onFailure {
            logger.w(TAG, "source connection hydration failed from sources list")
            when (sourceStatusRepository.refreshFromServer()) {
                is BecalmResult.Success -> logger.d(TAG, "source statuses refreshed after hydration failure")
                is BecalmResult.Failure -> logger.w(TAG, "source status refresh failed")
            }
        }
    }

    private fun sourceTypeForSuccessfulOAuthResult(
        result: String?,
        provider: String?,
        family: String?,
    ): String? {
        if (result != "success") return null
        val normalizedProvider = provider?.trim()?.lowercase().orEmpty()
        val normalizedFamily = family?.trim()?.lowercase().orEmpty()
        return when {
            normalizedProvider == SourceType.GMAIL -> SourceType.GMAIL
            normalizedProvider == SourceType.OUTLOOK_MAIL -> SourceType.OUTLOOK_MAIL
            normalizedProvider == SourceType.GOOGLE_CALENDAR -> SourceType.GOOGLE_CALENDAR
            normalizedProvider == SourceType.OUTLOOK_CALENDAR -> SourceType.OUTLOOK_CALENDAR
            normalizedProvider == "google" && normalizedFamily == "mail" -> SourceType.GMAIL
            normalizedProvider == "outlook" && normalizedFamily == "mail" -> SourceType.OUTLOOK_MAIL
            normalizedProvider == "google" && normalizedFamily == "calendar" -> SourceType.GOOGLE_CALENDAR
            normalizedProvider == "outlook" && normalizedFamily == "calendar" -> SourceType.OUTLOOK_CALENDAR
            else -> null
        }
    }

}

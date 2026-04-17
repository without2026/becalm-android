package com.becalm.android.ui.persons

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.repository.RawIngestionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ─── UI model ─────────────────────────────────────────────────────────────────

/**
 * Immutable snapshot of the RawEventDetailScreen UI.
 *
 * Exposes only display-safe fields extracted from the underlying raw entity,
 * keeping PII (full event body, person ref, etc.) out of the UI layer.
 *
 * @property eventId Primary-key of the loaded event.
 * @property sourceType Source-type label (e.g. "sms", "email").
 * @property eventTitle Human-readable title of the event, when available.
 * @property timestamp Event occurrence time, when available.
 * @property snippet First 200 characters of the event body for preview display (SRC-004).
 * @property loading True while the lookup is in progress.
 * @property error Non-null when the event could not be found or an error occurred.
 */
public data class RawEventDetailUiState(
    val eventId: String = "",
    val sourceType: String? = null,
    val eventTitle: String? = null,
    val timestamp: kotlinx.datetime.Instant? = null,
    val snippet: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "RawEventDetailViewModel"
internal const val ARG_EVENT_ID = "event_id"

/**
 * ViewModel for RawEventDetailScreen (SRC-008).
 *
 * Loads a single raw ingestion event by its primary-key [eventId] using
 * [RawIngestionRepository.findById] and exposes display-safe fields via
 * [RawEventDetailUiState].
 *
 * The current user ID is resolved from [UserPrefsStore.observeCurrentUserId] and passed
 * to the user-scoped [RawIngestionRepository.findById] call — prevents cross-user leaks
 * when a stale navigation arg references another user's event UUID.
 *
 * @param savedStateHandle navigation argument; expects key [ARG_EVENT_ID].
 */
@HiltViewModel
public class RawEventDetailViewModel @Inject constructor(
    private val rawIngestionRepository: RawIngestionRepository,
    private val userPrefsStore: UserPrefsStore,
    savedStateHandle: SavedStateHandle,
    private val logger: Logger,
) : ViewModel() {

    private val eventId: String = savedStateHandle[ARG_EVENT_ID] ?: ""

    private val _uiState: MutableStateFlow<RawEventDetailUiState> =
        MutableStateFlow(RawEventDetailUiState())
    public val uiState: StateFlow<RawEventDetailUiState> = _uiState.asStateFlow()

    init {
        if (eventId.isEmpty()) {
            _uiState.value = RawEventDetailUiState(loading = false, error = "Event ID missing")
        } else {
            loadEvent()
        }
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun loadEvent() {
        viewModelScope.launch {
            // Resolve the current user ID so the DAO can reject cross-user reads. When no
            // session is active we cannot scope the query, so report "not found" rather than
            // returning an arbitrary row.
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) {
                logger.w(TAG, "loadEvent id=%08x userId absent — treating as not found".format(eventId.hashCode()))
                _uiState.value = RawEventDetailUiState(loading = false, error = "Event not found")
                return@launch
            }
            val entity = rawIngestionRepository.findById(id = eventId, userId = userId)
            logger.d(TAG, "loadEvent id=%08x found=${entity != null}".format(eventId.hashCode()))
            _uiState.value = if (entity != null) {
                RawEventDetailUiState(
                    eventId = entity.id,
                    sourceType = entity.sourceType,
                    eventTitle = entity.eventTitle,
                    timestamp = entity.timestamp,
                    snippet = entity.eventSnippet?.take(200),
                    loading = false,
                )
            } else {
                RawEventDetailUiState(
                    loading = false,
                    error = "Event not found",
                )
            }
        }
    }
}

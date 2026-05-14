package com.becalm.android.ui.persons

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceOriginalResolver
import com.becalm.android.ui.components.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─── UI model ─────────────────────────────────────────────────────────────────

/**
 * Immutable snapshot of the RawEventDetailScreen UI.
 *
 * Exposes only display-safe fields extracted from the underlying raw entity,
 * keeping PII (raw personRef, raw headers) out of the UI layer. Email-specific
 * fields are populated only when the loaded event is an email source; for other
 * sources they remain null / zero.
 *
 * @property eventId Primary-key of the loaded event.
 * @property sourceType Source-type label (e.g. "gmail", "voice").
 * @property eventTitle Human-readable title of the event, when available.
 * @property timestamp Event occurrence time, when available.
 * @property snippet First 200 characters of the event body for preview display (SRC-004).
 * @property emailBody Joined `email_body` projection. Null when the event is not
 *   an email source, or when no body row has been captured yet. See [EmailBodyUi].
 * @property attachmentCount Count of entries parsed from
 *   [EmailBodyEntity.attachmentsMeta]. Zero when the JSON is null / empty / malformed
 *   (graceful degrade per EMAIL-007).
 * @property commitmentsExtractedCount Mirror of
 *   [RawIngestionEventEntity.commitmentsExtractedCount] — rendered as the
 *   "약속 추출 N건" badge when > 0 (SRC-008).
 * @property loading True while the lookup is in progress.
 * @property error Non-null when the event could not be found or an error occurred.
 */
public data class RawEventDetailUiState(
    val eventId: String = "",
    val sourceType: String? = null,
    val eventTitle: String? = null,
    val timestamp: kotlinx.datetime.Instant? = null,
    val snippet: String? = null,
    val durationSeconds: Int? = null,
    val location: String? = null,
    val attendeesRaw: String? = null,
    val commitmentQuotes: List<String> = emptyList(),
    val emailBody: EmailBodyUi? = null,
    val archivedOriginal: ArchivedOriginalUi? = null,
    val attachmentCount: Int = 0,
    val commitmentsExtractedCount: Int = 0,
    val syncStatus: String? = null,
    val loading: Boolean = true,
    val error: UiMessage? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "RawEventDetailViewModel"
internal const val ARG_EVENT_ID = "event_id"

/**
 * ViewModel for RawEventDetailScreen (SRC-008).
 *
 * Loads a single raw ingestion event by its primary-key [eventId] using
 * [RawIngestionRepository.findById], then resolves any local original/email body
 * through [SourceOriginalResolver].
 *
 * The current user ID is resolved from [UserPrefsStore.observeCurrentUserId] and
 * passed to the user-scoped [RawIngestionRepository.findById] call — prevents
 * cross-user leaks when a stale navigation arg references another user's event
 * UUID.
 *
 * @param savedStateHandle navigation argument; expects key [ARG_EVENT_ID].
 */
@HiltViewModel
public class RawEventDetailViewModel @Inject constructor(
    private val rawIngestionRepository: RawIngestionRepository,
    private val sourceOriginalResolver: SourceOriginalResolver,
    private val projectionPort: RawEventDetailProjectionPort,
    private val userPrefsStore: UserPrefsStore,
    savedStateHandle: SavedStateHandle,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val eventId: String = savedStateHandle[ARG_EVENT_ID] ?: ""

    private val _uiState: MutableStateFlow<RawEventDetailUiState> =
        MutableStateFlow(RawEventDetailUiState())
    public val uiState: StateFlow<RawEventDetailUiState> = _uiState.asStateFlow()

    init {
        if (eventId.isEmpty()) {
            _uiState.value = RawEventDetailUiState(
                loading = false,
                error = UiMessage.resource(R.string.raw_event_detail_error_missing_id),
            )
        } else {
            loadEvent()
        }
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun loadEvent() {
        viewModelScope.launch {
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) {
                logger.w(TAG, "loadEvent id=%08x userId absent — treating as not found".format(eventId.hashCode()))
                _uiState.value = RawEventDetailProjector.notFoundState()
                return@launch
            }

            val entity = rawIngestionRepository.findById(id = eventId, userId = userId)
            logger.d(TAG, "loadEvent id=%08x found=${entity != null}".format(eventId.hashCode()))
            if (entity == null) {
                _uiState.value = RawEventDetailProjector.notFoundState()
                return@launch
            }

            val loadedState = withContext(ioDispatcher) {
                val commitmentQuotes = projectionPort.loadCommitmentQuotes(userId, entity)
                val attendeesRaw = projectionPort.loadCalendarAttendeesRaw(userId, entity)
                val sourceOriginal = sourceOriginalResolver.resolve(userId, entity)
                RawEventDetailProjector.buildLoadedState(
                    entity = entity,
                    emailBody = sourceOriginal.emailBody,
                    archivedOriginal = sourceOriginal.archivedOriginal,
                    commitmentQuotes = commitmentQuotes,
                    attendeesRaw = attendeesRaw,
                )
            }
            _uiState.value = loadedState
        }
    }

}

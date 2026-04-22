package com.becalm.android.ui.persons

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.repository.EmailBodyRepository
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
 * keeping PII (raw personRef, raw headers) out of the UI layer. Email-specific
 * fields are populated only when the loaded event's `source_type` is in
 * [EMAIL_SOURCE_TYPES]; for other sources they remain null / zero.
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
    val emailBody: EmailBodyUi? = null,
    val attachmentCount: Int = 0,
    val commitmentsExtractedCount: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "RawEventDetailViewModel"
internal const val ARG_EVENT_ID = "event_id"
private const val ERROR_EVENT_NOT_FOUND = "Event not found"

/**
 * ViewModel for RawEventDetailScreen (SRC-008).
 *
 * Loads a single raw ingestion event by its primary-key [eventId] using
 * [RawIngestionRepository.findById]. When the loaded event is in
 * [EMAIL_SOURCE_TYPES] the VM additionally joins [EmailBodyRepository] to hydrate
 * [RawEventDetailUiState.emailBody] and [RawEventDetailUiState.attachmentCount];
 * non-email sources skip that call entirely.
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
    private val emailBodyRepository: EmailBodyRepository,
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
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) {
                logger.w(TAG, "loadEvent id=%08x userId absent — treating as not found".format(eventId.hashCode()))
                _uiState.value = notFoundState()
                return@launch
            }

            val entity = rawIngestionRepository.findById(id = eventId, userId = userId)
            logger.d(TAG, "loadEvent id=%08x found=${entity != null}".format(eventId.hashCode()))
            if (entity == null) {
                _uiState.value = notFoundState()
                return@launch
            }

            _uiState.value = buildLoadedState(entity = entity, emailBody = maybeLoadEmailBody(entity))
        }
    }

    /**
     * Returns the joined [EmailBodyEntity] when [entity] is an email source, or
     * null otherwise. Non-email sources skip the repository round-trip entirely
     * so voice / calendar / call-recording events pay no extra cost.
     */
    private suspend fun maybeLoadEmailBody(entity: RawIngestionEventEntity): EmailBodyEntity? =
        if (entity.sourceType in EMAIL_SOURCE_TYPES) {
            emailBodyRepository.getByRawEventId(entity.id)
        } else {
            null
        }

    private fun buildLoadedState(
        entity: RawIngestionEventEntity,
        emailBody: EmailBodyEntity?,
    ): RawEventDetailUiState {
        val ui = emailBody?.let { EmailBodyUi(bodyPlain = it.bodyPlain, bodyHtml = it.bodyHtml) }
        val attachments = AttachmentMetaParser.parse(emailBody?.attachmentsMeta)
        return RawEventDetailUiState(
            eventId = entity.id,
            sourceType = entity.sourceType,
            eventTitle = entity.eventTitle,
            timestamp = entity.timestamp,
            snippet = entity.eventSnippet?.take(SNIPPET_CHAR_LIMIT),
            emailBody = ui,
            attachmentCount = attachments.size,
            commitmentsExtractedCount = entity.commitmentsExtractedCount,
            loading = false,
        )
    }

    private fun notFoundState(): RawEventDetailUiState =
        RawEventDetailUiState(loading = false, error = ERROR_EVENT_NOT_FOUND)

    private companion object {
        /** Spec SRC-004: timeline preview clips body to 200 chars. */
        const val SNIPPET_CHAR_LIMIT: Int = 200
    }
}

package com.becalm.android.ui.persons

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.repository.RawIngestionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─── UI model ─────────────────────────────────────────────────────────────────

/**
 * Immutable snapshot of the RawEventDetailScreen UI.
 *
 * @property event The loaded [RawIngestionEventEntity], or null when not yet loaded or not found.
 * @property loading True while the lookup is in progress.
 * @property error Non-null when the event could not be found or an error occurred.
 */
public data class RawEventDetailUiState(
    val event: RawIngestionEventEntity? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "RawEventDetailViewModel"
internal const val ARG_EVENT_ID = "eventId"

/**
 * ViewModel for RawEventDetailScreen (SRC-008).
 *
 * Loads a single [RawIngestionEventEntity] by its primary-key [id] using
 * [RawIngestionRepository.findById].
 *
 * @param savedStateHandle navigation argument; expects key [ARG_EVENT_ID].
 */
@HiltViewModel
public class RawEventDetailViewModel @Inject constructor(
    private val rawIngestionRepository: RawIngestionRepository,
    savedStateHandle: SavedStateHandle,
    private val logger: Logger,
) : ViewModel() {

    private val eventId: String = checkNotNull(savedStateHandle[ARG_EVENT_ID]) {
        "RawEventDetailViewModel requires '$ARG_EVENT_ID' in SavedStateHandle"
    }

    private val _uiState: MutableStateFlow<RawEventDetailUiState> =
        MutableStateFlow(RawEventDetailUiState())
    public val uiState: StateFlow<RawEventDetailUiState> = _uiState.asStateFlow()

    init {
        loadEvent()
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun loadEvent() {
        viewModelScope.launch {
            val entity = rawIngestionRepository.findById(eventId)
            logger.d(TAG, "loadEvent id=%08x found=${entity != null}".format(eventId.hashCode()))
            _uiState.value = if (entity != null) {
                RawEventDetailUiState(event = entity, loading = false)
            } else {
                RawEventDetailUiState(
                    event = null,
                    loading = false,
                    error = "Event not found",
                )
            }
        }
    }
}

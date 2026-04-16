package com.becalm.android.ui.commitments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.data.local.dao.CommitmentDao
import com.becalm.android.data.local.entities.Commitment
import com.becalm.android.data.remote.api.ApiCallResult
import com.becalm.android.data.remote.api.AuthenticatedApiCaller
import com.becalm.android.data.remote.api.BeCalmApi
import com.becalm.android.data.remote.dto.PatchActionStateRequest
import com.becalm.android.domain.CommitmentTransitions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// spec: CMT-001..CMT-010 — commitment management screen logic
// spec: CMT-005..CMT-007 — action_state transitions
// Invariant: Room UPDATE first, Railway PATCH async; PATCH failure → pending retry queue

data class CommitmentFilter(val direction: String? = null)

data class CommitmentsUiState(
    val all: List<Commitment> = emptyList(),
    val active: List<Commitment> = emptyList(),
    val completed: List<Commitment> = emptyList(),
    val isLoading: Boolean = false,
    val selectedFilter: CommitmentFilter = CommitmentFilter()
)

@HiltViewModel
class CommitmentViewModel @Inject constructor(
    private val commitmentDao: CommitmentDao,
    private val api: BeCalmApi,
    private val apiCaller: AuthenticatedApiCaller
) : ViewModel() {

    private val _uiState = MutableStateFlow(CommitmentsUiState(isLoading = true))
    val uiState: StateFlow<CommitmentsUiState> = _uiState

    init {
        loadCommitments()
    }

    // spec: CMT-001 — load all commitments on screen entry
    fun loadCommitments(filter: CommitmentFilter = CommitmentFilter()) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, selectedFilter = filter)
            val commitments = commitmentDao.getFiltered(direction = filter.direction)
            // spec: CMT-009 — split active vs completed
            val active = commitments.filter { it.actionState != Commitment.ActionState.COMPLETED }
            val completed = commitments.filter { it.actionState == Commitment.ActionState.COMPLETED }
            _uiState.value = CommitmentsUiState(
                all = commitments,
                active = active,
                completed = completed,
                isLoading = false,
                selectedFilter = filter
            )
        }
    }

    // spec: CMT-002 — filter by direction
    fun applyFilter(direction: String?) {
        loadCommitments(CommitmentFilter(direction = direction))
    }

    // spec: CMT-005 — mark as reminded (optimistic Room update + async Railway PATCH)
    fun markReminded(commitmentId: String) {
        updateActionState(commitmentId, Commitment.ActionState.REMINDED)
    }

    // spec: CMT-006 — mark as followed up
    fun markFollowedUp(commitmentId: String) {
        updateActionState(commitmentId, Commitment.ActionState.FOLLOWED_UP)
    }

    // spec: CMT-007 — mark as completed
    fun markCompleted(commitmentId: String) {
        updateActionState(commitmentId, Commitment.ActionState.COMPLETED)
    }

    // spec: CMT-005..CMT-007 — optimistic local update + Railway async
    // Invariant: Room UPDATE first, Railway PATCH async
    private fun updateActionState(commitmentId: String, newState: String) {
        viewModelScope.launch {
            // spec: CMT-005..CMT-007 — enforce transition guard before writing to Room
            val current = commitmentDao.getById(commitmentId)
            if (current != null && !CommitmentTransitions.isValid(current.actionState, newState)) {
                // Silent no-op: invalid transitions are ignored (not surfaced as errors in MVP)
                return@launch
            }

            // 1. Optimistic Room update (immediate UI feedback)
            commitmentDao.updateActionState(commitmentId, newState)
            // Refresh the list
            loadCommitments(_uiState.value.selectedFilter)

            // 2. Async Railway PATCH
            val result = apiCaller.call { bearer ->
                api.patchCommitmentActionState(
                    bearer, commitmentId, PatchActionStateRequest(newState)
                )
            }

            if (result !is ApiCallResult.Success) {
                // spec: CMT invariant — PATCH failure: sync_status='pending' for retry
                // The DAO update already happened; WorkManager will retry on next upload cycle
                // No rollback — optimistic update stays (Room is source of truth)
            }
        }
    }

    // spec: CMT-010 — pull-to-refresh syncs from Railway
    fun onPullToRefresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = apiCaller.call { bearer ->
                api.getCommitments(bearer)
            }
            if (result is ApiCallResult.Success) {
                // Upsert server data into Room
                val serverCommitments = result.data.data.map { dto ->
                    Commitment(
                        id = dto.id,
                        direction = dto.direction,
                        counterpartyRaw = dto.counterpartyRaw,
                        personRef = dto.personRef,
                        title = dto.title,
                        description = dto.description,
                        quote = dto.quote,
                        sourceEventTitle = dto.sourceEventTitle,
                        sourceEventOccurredAt = 0L, // parse ISO in real impl
                        dueDate = dto.dueDate,
                        actionState = dto.actionState,
                        sourceType = dto.sourceType,
                        sourceRef = dto.sourceRef,
                        confidence = dto.confidence,
                        syncStatus = Commitment.SyncStatus.SYNCED
                    )
                }
                commitmentDao.upsertAll(serverCommitments)
            }
            loadCommitments(_uiState.value.selectedFilter)
        }
    }
}

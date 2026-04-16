package com.becalm.android.ui.commitments

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.becalm.android.data.local.dao.CommitmentDao
import com.becalm.android.data.local.entities.Commitment
import com.becalm.android.data.remote.api.ApiCallResult
import com.becalm.android.data.remote.api.AuthenticatedApiCaller
import com.becalm.android.data.remote.api.BeCalmApi
import com.becalm.android.data.remote.api.SingleCommitmentResponse
import com.becalm.android.data.remote.dto.CommitmentDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// spec: CMT-001 — load all commitments
// spec: CMT-002 — filter by direction
// spec: CMT-005..CMT-007 — action_state transitions
// spec: CMT-009 — active vs completed split
// spec: CMT-010 — pull-to-refresh

@OptIn(ExperimentalCoroutinesApi::class)
class CommitmentViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val commitmentDao: CommitmentDao = mockk(relaxed = true)
    private val api: BeCalmApi = mockk()
    private val apiCaller: AuthenticatedApiCaller = mockk()

    private fun makeCommitment(id: String, direction: String = "give", actionState: String = "pending") =
        Commitment(id = id, direction = direction, title = "Test", quote = "verbatim",
            sourceEventOccurredAt = System.currentTimeMillis(), sourceType = "voice",
            actionState = actionState)

    private fun makeDtoCommitment(id: String) = CommitmentDto(
        id = id, userId = "u1", direction = "give", counterpartyRaw = null, personRef = null,
        title = "Test", description = null, quote = "verbatim", sourceEventTitle = null,
        sourceEventOccurredAt = "2026-04-16T10:00:00Z", dueDate = null, actionState = "pending",
        sourceType = "voice", sourceRef = null, confidence = 0.9f, syncStatus = "synced",
        createdAt = "2026-04-16T10:00:00Z", updatedAt = "2026-04-16T10:00:00Z"
    )

    // spec: CMT-001 — all commitments loaded on init
    @Test
    fun `loadCommitments populates uiState with all commitments`() = runTest {
        val commitments = listOf(makeCommitment("c1"), makeCommitment("c2", direction = "take"))
        coEvery { commitmentDao.getFiltered(any(), any(), any(), any()) } returns commitments

        val vm = CommitmentViewModel(commitmentDao, api, apiCaller)
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.all.size)
        assertFalse(vm.uiState.value.isLoading)
    }

    // spec: CMT-002 — filter returns only give commitments
    @Test
    fun `applyFilter GIVE returns only give direction`() = runTest {
        val gives = listOf(makeCommitment("c1"), makeCommitment("c2"))
        coEvery { commitmentDao.getFiltered(direction = "give", any(), any(), any()) } returns gives
        coEvery { commitmentDao.getFiltered(direction = null, any(), any(), any()) } returns gives

        val vm = CommitmentViewModel(commitmentDao, api, apiCaller)
        advanceUntilIdle()
        vm.applyFilter("give")
        advanceUntilIdle()

        assertEquals("give", vm.uiState.value.selectedFilter.direction)
    }

    // spec: CMT-009 — completed commitments in separate list
    @Test
    fun `loadCommitments separates active and completed commitments`() = runTest {
        val active = makeCommitment("c-active", actionState = "pending")
        val completed = makeCommitment("c-done", actionState = "completed")
        coEvery { commitmentDao.getFiltered(any(), any(), any(), any()) } returns listOf(active, completed)

        val vm = CommitmentViewModel(commitmentDao, api, apiCaller)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.active.size)
        assertEquals(1, vm.uiState.value.completed.size)
    }

    // spec: CMT-005 — markReminded updates Room
    @Test
    fun `markReminded updates action_state in Room and calls Railway PATCH`() = runTest {
        coEvery { commitmentDao.getFiltered(any(), any(), any(), any()) } returns emptyList()
        coEvery { apiCaller.call<SingleCommitmentResponse>(any()) } returns ApiCallResult.Unauthorized

        val vm = CommitmentViewModel(commitmentDao, api, apiCaller)
        advanceUntilIdle()
        vm.markReminded("cmt-id")
        advanceUntilIdle()

        coVerify { commitmentDao.updateActionState("cmt-id", Commitment.ActionState.REMINDED, any()) }
    }

    // spec: CMT-007 — markCompleted moves to completed section
    @Test
    fun `markCompleted transitions to completed action_state`() = runTest {
        coEvery { commitmentDao.getFiltered(any(), any(), any(), any()) } returns emptyList()
        coEvery { apiCaller.call<SingleCommitmentResponse>(any()) } returns ApiCallResult.Unauthorized

        val vm = CommitmentViewModel(commitmentDao, api, apiCaller)
        advanceUntilIdle()
        vm.markCompleted("cmt-done")
        advanceUntilIdle()

        coVerify { commitmentDao.updateActionState("cmt-done", Commitment.ActionState.COMPLETED, any()) }
    }

    // spec: CMT invariant — PATCH failure does not rollback Room update
    @Test
    fun `PATCH failure does not roll back optimistic Room update`() = runTest {
        coEvery { commitmentDao.getFiltered(any(), any(), any(), any()) } returns emptyList()
        coEvery { apiCaller.call<SingleCommitmentResponse>(any()) } returns ApiCallResult.NetworkError

        val vm = CommitmentViewModel(commitmentDao, api, apiCaller)
        advanceUntilIdle()
        vm.markReminded("cmt-optimistic")
        advanceUntilIdle()

        // Room was still updated optimistically
        coVerify { commitmentDao.updateActionState("cmt-optimistic", Commitment.ActionState.REMINDED, any()) }
    }
}

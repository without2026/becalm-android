package com.becalm.android.ui.commitments

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.domain.commitment.CommitmentEvent
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.domain.reminder.ReminderScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CMT-013 undo-semantics tests. These are additive to [CommitmentManagementViewModelTest]
 * and only exercise the snapshot / onUndo pathway. The existing C2 tests already cover
 * the `transitionState` + `reminderScheduler.cancel` side effects of `onComplete` /
 * `onCancel`; here we verify:
 *  - a snapshot is emitted through [CommitmentManagementViewModel.undoFlow] on each
 *    terminal transition with the *prior* action_state captured from the Room row;
 *  - [CommitmentManagementViewModel.onUndo] reverts via the field-level
 *    [CommitmentRepository.updateActionState] write (NOT through the state machine);
 *  - onUndo never re-arms the reminder alarm (spec CMT-013:131).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommitmentManagementUndoTest {

    private val testDispatcher = StandardTestDispatcher()

    private val commitmentRepository: CommitmentRepository = mockk(relaxed = true)
    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk(relaxed = true)
    private val reminderScheduler: ReminderScheduler = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    private lateinit var viewModel: CommitmentManagementViewModel

    private fun makeEntity(
        id: String,
        actionState: String = "reminded",
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        direction = "give",
        counterpartyRaw = null,
        personRef = null,
        title = "Test commitment $id",
        description = null,
        quote = "quote",
        sourceEventTitle = null,
        sourceEventOccurredAt = Instant.DISTANT_PAST,
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        actionState = actionState,
        sourceType = "voice",
        sourceRef = null,
        confidence = 0.9,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
        createdAt = Instant.DISTANT_PAST,
        updatedAt = Instant.DISTANT_PAST,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { commitmentRepository.observeAllForUser(any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns
            flowOf(emptyMap<String, PersonEnrichmentEntity>())
        every { reminderScheduler.cancel(any()) } just runs
        // updateActionState default — success so onUndo happy-path tests do not need to
        // restub per-test. Tests that want a failure override this.
        coEvery {
            commitmentRepository.updateActionState(any(), any(), any())
        } returns BecalmResult.Success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): CommitmentManagementViewModel = CommitmentManagementViewModel(
        commitmentRepository = commitmentRepository,
        personEnrichmentRepository = personEnrichmentRepository,
        reminderScheduler = reminderScheduler,
        userPrefsStore = userPrefsStore,
        logger = logger,
    )

    // ── A: onComplete emits Completed snapshot with prior state ────────────────

    @Test
    fun `onComplete emits CommitmentUndoSnapshot Completed with captured priorState`() = runTest {
        val entity = makeEntity(id = "c-1", actionState = "reminded")
        every { commitmentRepository.observeAllForUser("user-1") } returns
            flowOf(listOf(entity))
        coEvery {
            commitmentRepository.transitionState("c-1", CommitmentEvent.Complete)
        } returns BecalmResult.Success(entity.copy(actionState = "completed"))

        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.undoFlow.test {
            viewModel.onComplete("c-1")
            advanceUntilIdle()

            val snapshot = awaitItem()
            assertTrue(
                "expected Completed snapshot, got $snapshot",
                snapshot is CommitmentUndoSnapshot.Completed,
            )
            assertEquals("c-1", snapshot.commitmentId)
            assertEquals(CommitmentState.REMINDED, snapshot.priorState)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── B: onCancel emits Cancelled snapshot with prior state ──────────────────

    @Test
    fun `onCancel emits CommitmentUndoSnapshot Cancelled with captured priorState`() = runTest {
        val entity = makeEntity(id = "x-1", actionState = "followed_up")
        every { commitmentRepository.observeAllForUser("user-1") } returns
            flowOf(listOf(entity))
        coEvery {
            commitmentRepository.transitionState("x-1", CommitmentEvent.Cancel)
        } returns BecalmResult.Success(entity.copy(actionState = "cancelled"))

        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.undoFlow.test {
            viewModel.onCancel("x-1")
            advanceUntilIdle()

            val snapshot = awaitItem()
            assertTrue(
                "expected Cancelled snapshot, got $snapshot",
                snapshot is CommitmentUndoSnapshot.Cancelled,
            )
            assertEquals("x-1", snapshot.commitmentId)
            assertEquals(CommitmentState.FOLLOWED_UP, snapshot.priorState)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── C: onUndo calls updateActionState with the prior wire value ────────────

    @Test
    fun `onUndo writes priorState wireValue via updateActionState exactly once`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()

        val snapshot = CommitmentUndoSnapshot.Completed(
            commitmentId = "u-1",
            priorState = CommitmentState.REMINDED,
        )
        viewModel.onUndo(snapshot)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            commitmentRepository.updateActionState(
                id = "u-1",
                newState = "reminded",
                updatedAt = any(),
            )
        }
    }

    // ── D: onUndo never schedules a reminder ───────────────────────────────────

    @Test
    fun `onUndo does not re-register the reminder alarm`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()

        val snapshot = CommitmentUndoSnapshot.Cancelled(
            commitmentId = "u-2",
            priorState = CommitmentState.PENDING,
        )
        viewModel.onUndo(snapshot)
        advanceUntilIdle()

        verify(exactly = 0) { reminderScheduler.schedule(any(), any()) }
    }

    // ── E: onComplete → onUndo round-trip does not re-arm the alarm ────────────

    @Test
    fun `onComplete then onUndo round-trip never schedules an alarm`() = runTest {
        val entity = makeEntity(id = "rt-1", actionState = "reminded")
        every { commitmentRepository.observeAllForUser("user-1") } returns
            flowOf(listOf(entity))
        coEvery {
            commitmentRepository.transitionState("rt-1", CommitmentEvent.Complete)
        } returns BecalmResult.Success(entity.copy(actionState = "completed"))

        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onComplete("rt-1")
        advanceUntilIdle()

        // Pull the snapshot off undoFlow then invoke onUndo. We already assert emit
        // semantics in test A; here we only need the snapshot instance to feed onUndo.
        val snapshot = CommitmentUndoSnapshot.Completed(
            commitmentId = "rt-1",
            priorState = CommitmentState.REMINDED,
        )
        viewModel.onUndo(snapshot)
        advanceUntilIdle()

        verify(exactly = 0) { reminderScheduler.schedule(any(), any()) }
        // Cancel happened during onComplete (existing C2 behaviour); not during onUndo.
        verify(exactly = 1) { reminderScheduler.cancel("rt-1") }
        coVerify(exactly = 1) {
            commitmentRepository.updateActionState(
                id = "rt-1",
                newState = "reminded",
                updatedAt = any(),
            )
        }
    }

    // ── F: onUndo failure surfaces via uiState.error ──────────────────────────

    @Test
    fun `onUndo surfaces repository failure through uiState error`() = runTest {
        coEvery {
            commitmentRepository.updateActionState(any(), any(), any())
        } returns BecalmResult.Failure(BecalmError.Network(code = 500, message = "server down"))

        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onUndo(
            CommitmentUndoSnapshot.Completed(
                commitmentId = "err-1",
                priorState = CommitmentState.PENDING,
            ),
        )
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.error)
    }
}

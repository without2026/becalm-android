package com.becalm.android.ui.commitments

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.domain.commitment.CommitmentEditPatch
import com.becalm.android.ui.navigation.BecalmRoute
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [CommitmentEditViewModel] covering EDIT-001..008:
 *
 * - Load path seeds the form with the observed entity fields.
 * - Validation errors from [CommitmentEditValidator] surface on [onSave] without
 *   invoking the repository.
 * - Successful [onSave] emits [EditDismissEvent.Saved].
 * - Repository failures surface as human-readable [EditUiState.saveError] and
 *   do NOT emit Dismissed.
 * - [onToggleDispute] flips the read-only flag locally after Success.
 * - [onConfirmDelete] emits [EditDismissEvent.Deleted] on Success.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommitmentEditViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val commitmentRepository: CommitmentRepository = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun savedState(id: String = "cmt-1"): SavedStateHandle =
        SavedStateHandle(mapOf(BecalmRoute.CommitmentEdit.ARG_ID to id))

    private fun buildViewModel(id: String = "cmt-1"): CommitmentEditViewModel =
        CommitmentEditViewModel(
            commitmentRepository = commitmentRepository,
            savedStateHandle = savedState(id),
            logger = logger,
        )

    // ─── Load path ────────────────────────────────────────────────────────────

    @Test
    fun `load path seeds form with entity fields`() = runTest(testDispatcher) {
        val entity = makeEntity(
            id = "cmt-1",
            title = "Pay rent",
            direction = "take",
            personRef = "+821012345678",
        )
        every { commitmentRepository.observeById("cmt-1") } returns flowOf(entity)

        val viewModel = buildViewModel("cmt-1")

        viewModel.uiState.test {
            awaitItem() // initial loading=true
            val settled = awaitItem()
            assertEquals(false, settled.loading)
            assertEquals("Pay rent", settled.title)
            assertEquals("take", settled.direction)
            assertEquals("+821012345678", settled.personRef)
            assertNotNull(settled.readOnly)
            assertEquals("quote body", settled.readOnly!!.quote)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `load path flips notFound when observeById emits null`() = runTest(testDispatcher) {
        every { commitmentRepository.observeById("missing") } returns flowOf(null)

        val viewModel = buildViewModel("missing")

        viewModel.uiState.test {
            awaitItem() // loading=true
            val settled = awaitItem()
            assertEquals(false, settled.loading)
            assertTrue(settled.notFound)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Validation ───────────────────────────────────────────────────────────

    @Test
    fun `onSave with empty title surfaces field error and does not call repo`() =
        runTest(testDispatcher) {
            val entity = makeEntity(id = "cmt-v", title = "")
            every { commitmentRepository.observeById("cmt-v") } returns flowOf(entity)

            val viewModel = buildViewModel("cmt-v")
            // Drain the initial seed emission.
            viewModel.uiState.test {
                awaitItem()
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            viewModel.onSave()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(
                "TITLE field error must appear",
                state.fieldErrors.isNotEmpty(),
            )
            // Repo must NOT have been called — verification via coVerify(exactly = 0).
            coVerify(exactly = 0) {
                commitmentRepository.editCommitment(any(), any())
            }
        }

    // ─── Save happy path ──────────────────────────────────────────────────────

    @Test
    fun `onSave with valid draft emits Saved dismiss event`() = runTest(testDispatcher) {
        val entity = makeEntity(id = "cmt-s", title = "Pay rent")
        every { commitmentRepository.observeById("cmt-s") } returns flowOf(entity)
        coEvery {
            commitmentRepository.editCommitment(eq("cmt-s"), any<CommitmentEditPatch>())
        } returns BecalmResult.Success(Unit)

        val viewModel = buildViewModel("cmt-s")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.dismiss.test {
            viewModel.onSave()
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(EditDismissEvent.Saved, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onSave failure surfaces saveError and does not dismiss`() = runTest(testDispatcher) {
        val entity = makeEntity(id = "cmt-f", title = "Pay rent")
        every { commitmentRepository.observeById("cmt-f") } returns flowOf(entity)
        coEvery {
            commitmentRepository.editCommitment(eq("cmt-f"), any<CommitmentEditPatch>())
        } returns BecalmResult.Failure(BecalmError.Unauthorized)

        val viewModel = buildViewModel("cmt-f")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSave()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull("saveError must be populated", state.saveError)
        assertEquals(false, state.saving)
    }

    // ─── Dispute toggle ───────────────────────────────────────────────────────

    @Test
    fun `onToggleDispute flips readOnly flag after Success`() = runTest(testDispatcher) {
        val entity = makeEntity(id = "cmt-d", quoteDisputed = false)
        every { commitmentRepository.observeById("cmt-d") } returns flowOf(entity)
        coEvery { commitmentRepository.markQuoteDisputed("cmt-d") } returns BecalmResult.Success(Unit)

        val viewModel = buildViewModel("cmt-d")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onToggleDispute()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(true, state.readOnly?.quoteDisputed)
        coVerify { commitmentRepository.markQuoteDisputed("cmt-d") }
    }

    // ─── Soft-delete ──────────────────────────────────────────────────────────

    @Test
    fun `onConfirmDelete emits Deleted dismiss event on Success`() = runTest(testDispatcher) {
        val entity = makeEntity(id = "cmt-del")
        every { commitmentRepository.observeById("cmt-del") } returns flowOf(entity)
        coEvery { commitmentRepository.softDelete("cmt-del") } returns BecalmResult.Success(Unit)

        val viewModel = buildViewModel("cmt-del")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.dismiss.test {
            viewModel.onConfirmDelete()
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(EditDismissEvent.Deleted, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Cancel ───────────────────────────────────────────────────────────────

    @Test
    fun `onCancel emits Cancelled without touching repository`() = runTest(testDispatcher) {
        val entity = makeEntity(id = "cmt-c")
        every { commitmentRepository.observeById("cmt-c") } returns flowOf(entity)

        val viewModel = buildViewModel("cmt-c")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.dismiss.test {
            viewModel.onCancel()
            assertEquals(EditDismissEvent.Cancelled, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { commitmentRepository.editCommitment(any(), any()) }
        coVerify(exactly = 0) { commitmentRepository.softDelete(any()) }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun makeEntity(
        id: String = "cmt-1",
        title: String = "Title",
        direction: String = "give",
        personRef: String? = null,
        quoteDisputed: Boolean = false,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        direction = direction,
        counterpartyRaw = null,
        personRef = personRef,
        title = title,
        description = null,
        quote = "quote body",
        sourceEventTitle = "Standup",
        sourceEventOccurredAt = Instant.fromEpochMilliseconds(1_000),
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        actionState = "pending",
        sourceType = "voice",
        sourceRef = null,
        confidence = 0.8,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
        lastEditedBy = null,
        lastEditedAt = null,
        quoteDisputed = quoteDisputed,
        quoteDisputedAt = null,
        deletedAt = null,
        supersedesCommitmentId = null,
    )
}

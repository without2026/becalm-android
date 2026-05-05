package com.becalm.android.unit.ui.commitments

import app.cash.turbine.test
import androidx.lifecycle.SavedStateHandle
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.ui.commitments.CommitmentEditViewModel
import com.becalm.android.ui.commitments.EditDismissEvent
import com.becalm.android.ui.navigation.BecalmRoute
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommitmentEditViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val commitmentRepository: CommitmentRepository = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `EDIT-002 load seeds editable fields while preserving read only quote and source`() = runTest {
        val dueAt = Instant.parse("2026-04-18T06:00:00Z")
        every { commitmentRepository.observeByIdForUser("user-1", "c1") } returns flowOf(
            entity(
                id = "c1",
                direction = "take",
                counterpartyRef = "alice@example.com",
                dueAt = dueAt,
                dueHint = "내일 오전",
                dueIsApproximate = true,
                quoteDisputed = true,
            ),
        )

        val viewModel = buildViewModel("c1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(false, state.loading)
        assertEquals("Title", state.title)
        assertEquals("take", state.direction)
        assertEquals("alice@example.com", state.counterpartyRef)
        assertEquals(dueAt.toEpochMilliseconds(), state.dueAtMillis)
        assertEquals("내일 오전", state.dueHint)
        assertEquals(true, state.dueIsApproximate)
        assertEquals("quote body", state.readOnly?.quote)
        assertEquals("Standup:1/1 09:00", state.readOnly?.sourceLabel)
        assertEquals(true, state.readOnly?.quoteDisputed)
    }

    @Test
    fun `EDIT invalid save surfaces field errors and skips repository write`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "c2") } returns flowOf(entity(id = "c2"))

        val viewModel = buildViewModel("c2")
        advanceUntilIdle()
        viewModel.onTitleChange("   ")
        viewModel.onSave()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.fieldErrors.isNotEmpty())
        coVerify(exactly = 0) { commitmentRepository.editCommitment(any(), any()) }
    }

    @Test
    fun `EDIT save not-found error maps to deleted commitment message`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "c3") } returns flowOf(entity(id = "c3"))
        coEvery { commitmentRepository.editCommitment(eq("c3"), any()) } returns
            BecalmResult.Failure(BecalmError.NotFound("commitment"))

        val viewModel = buildViewModel("c3")
        advanceUntilIdle()
        viewModel.onSave()
        advanceUntilIdle()

        assertEquals("삭제된 약속입니다", viewModel.uiState.value.saveError)
    }

    @Test
    fun `EDIT-003 successful save emits Saved dismiss event`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "c3-save") } returns
            flowOf(entity(id = "c3-save"))
        coEvery { commitmentRepository.editCommitment(eq("c3-save"), any()) } returns
            BecalmResult.Success(Unit)

        val viewModel = buildViewModel("c3-save")
        advanceUntilIdle()

        viewModel.dismiss.test {
            viewModel.onSave()
            advanceUntilIdle()
            assertEquals(EditDismissEvent.Saved, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `EDIT-005 dispute set path calls markQuoteDisputed and flips read-only badge on`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "c3-dispute") } returns
            flowOf(entity(id = "c3-dispute", quoteDisputed = false))
        coEvery { commitmentRepository.markQuoteDisputed("c3-dispute") } returns BecalmResult.Success(Unit)

        val viewModel = buildViewModel("c3-dispute")
        advanceUntilIdle()
        viewModel.onToggleDispute()
        advanceUntilIdle()

        coVerify(exactly = 1) { commitmentRepository.markQuoteDisputed("c3-dispute") }
        assertEquals(true, viewModel.uiState.value.readOnly?.quoteDisputed)
    }

    @Test
    fun `EDIT dispute toggle clear path calls clearQuoteDispute when already disputed`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "c4") } returns
            flowOf(entity(id = "c4", quoteDisputed = true))
        coEvery { commitmentRepository.clearQuoteDispute("c4") } returns BecalmResult.Success(Unit)

        val viewModel = buildViewModel("c4")
        advanceUntilIdle()
        viewModel.onToggleDispute()
        advanceUntilIdle()

        coVerify(exactly = 1) { commitmentRepository.clearQuoteDispute("c4") }
        assertEquals(false, viewModel.uiState.value.readOnly?.quoteDisputed)
    }

    @Test
    fun `EDIT-006 delete delegates to soft delete and emits Deleted dismiss event`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "c5") } returns flowOf(entity(id = "c5"))
        coEvery { commitmentRepository.softDelete("c5") } returns BecalmResult.Success(Unit)

        val viewModel = buildViewModel("c5")
        advanceUntilIdle()

        viewModel.dismiss.test {
            viewModel.onConfirmDelete()
            advanceUntilIdle()
            assertEquals(EditDismissEvent.Deleted, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun buildViewModel(id: String): CommitmentEditViewModel = CommitmentEditViewModel(
        commitmentRepository = commitmentRepository,
        userPrefsStore = userPrefsStore,
        savedStateHandle = SavedStateHandle(mapOf(BecalmRoute.CommitmentEdit.ARG_ID to id)),
        logger = logger,
    )

    private fun entity(
        id: String,
        direction: String = "give",
        counterpartyRef: String? = "lee@corp.com",
        dueAt: Instant? = null,
        dueHint: String? = null,
        dueIsApproximate: Boolean = false,
        quoteDisputed: Boolean = false,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        direction = direction,
        counterpartyRaw = null,
        counterpartyRef = counterpartyRef,
        title = "Title",
        description = null,
        quote = "quote body",
        sourceEventTitle = "Standup",
        sourceEventOccurredAt = Instant.fromEpochMilliseconds(1_000),
        dueAt = dueAt,
        dueHint = dueHint,
        dueIsApproximate = dueIsApproximate,
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

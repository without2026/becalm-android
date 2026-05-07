package com.becalm.android.unit.ui.commitments

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.becalm.android.R
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.domain.commitment.CommitmentManualValidator
import com.becalm.android.domain.commitment.ManualCommitmentInput
import com.becalm.android.ui.commitments.CommitmentCreateMode
import com.becalm.android.ui.commitments.CommitmentCreateViewModel
import com.becalm.android.ui.navigation.BecalmRoute
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommitmentCreateViewModelSpecTest {

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
    fun `MAN-001 manual mode starts blank with give default and validation blocks save`() = runTest {
        val viewModel = buildViewModel()

        val initial = viewModel.uiState.value
        assertEquals(CommitmentCreateMode.MANUAL, initial.mode)
        assertEquals("give", initial.draft.direction)
        assertEquals("", initial.draft.title)
        assertEquals("", initial.draft.quote)
        assertNull(initial.supersedeSource)

        viewModel.onSave()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CommitmentCreateMode.MANUAL, state.mode)
        assertTrue(state.fieldErrors.containsKey(CommitmentManualValidator.Field.TITLE))
        assertTrue(state.fieldErrors.containsKey(CommitmentManualValidator.Field.QUOTE))
        assertFalse(state.saved)
        coVerify(exactly = 0) { commitmentRepository.saveManualCommitment(any(), any()) }
    }

    @Test
    fun `MAN-003 valid manual save forwards normalized draft and emits dismiss`() = runTest {
        coEvery { commitmentRepository.saveManualCommitment(any(), null) } returns
            BecalmResult.Success("new-id")

        val viewModel = buildViewModel()
        viewModel.onTitleChange("Send proposal")
        viewModel.onDirectionChange("take")
        viewModel.onQuoteChange("Please send me the proposal by Friday.")
        viewModel.onCounterpartyRefChange("  alice@example.com  ")
        viewModel.onDueHintChange("Friday afternoon")
        viewModel.onApproxChange(true)

        viewModel.dismiss.test {
            viewModel.onSave()
            advanceUntilIdle()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        val input = slot<ManualCommitmentInput>()
        coVerify(exactly = 1) { commitmentRepository.saveManualCommitment(capture(input), null) }
        assertEquals("Send proposal", input.captured.title)
        assertEquals("take", input.captured.direction)
        assertEquals("Please send me the proposal by Friday.", input.captured.quote)
        assertEquals("alice@example.com", input.captured.counterpartyRef)
        assertEquals("Friday afternoon", input.captured.dueHint)
        assertTrue(input.captured.dueIsApproximate)
        assertTrue(viewModel.uiState.value.saved)
        assertNull(viewModel.uiState.value.saveError)
    }

    @Test
    fun `EDIT-007 supersede mode keeps quote immutable and carries source contract into save`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "old-1") } returns
            flowOf(entity(id = "old-1", quote = "Old evidentiary quote"))
        coEvery { commitmentRepository.saveManualCommitment(any(), "old-1") } returns
            BecalmResult.Success("new-id")

        val viewModel = buildViewModel(supersedeOf = "old-1")
        advanceUntilIdle()

        val initial = viewModel.uiState.value
        assertEquals(CommitmentCreateMode.SUPERSEDE, initial.mode)
        assertEquals("Old evidentiary quote", initial.draft.quote)
        assertEquals(
            setOf("quote", "sourceEventOccurredAt", "sourceEventTitle"),
            initial.supersedeSource
                ?.let { source -> source::class.members.map { member -> member.name }.toSet() }
                ?.intersect(setOf("quote", "sourceEventOccurredAt", "sourceEventTitle")),
        )

        viewModel.onTitleChange("Replacement commitment")
        viewModel.onQuoteChange("tampered")
        viewModel.onSave()
        advanceUntilIdle()

        val input = slot<ManualCommitmentInput>()
        coVerify(exactly = 1) { commitmentRepository.saveManualCommitment(capture(input), "old-1") }
        assertEquals("Old evidentiary quote", input.captured.quote)
        assertEquals("Replacement commitment", input.captured.title)
    }

    @Test
    fun `SUPERSEDE missing source surfaces explicit save error and skips repository`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "missing") } returns flowOf(null)

        val viewModel = buildViewModel(supersedeOf = "missing")
        advanceUntilIdle()
        viewModel.onTitleChange("Replacement commitment")
        viewModel.onSave()
        advanceUntilIdle()

        assertEquals(
            R.string.commitment_save_error_supersede_source_not_found,
            viewModel.uiState.value.saveError?.resId,
        )
        assertFalse(viewModel.uiState.value.saved)
        coVerify(exactly = 0) { commitmentRepository.saveManualCommitment(any(), any()) }
    }

    @Test
    fun `save failure surfaces message and clearSaveError clears one-shot banner`() = runTest {
        coEvery { commitmentRepository.saveManualCommitment(any(), null) } returns
            BecalmResult.Failure(BecalmError.Network(0, "offline"))

        val viewModel = buildViewModel()
        viewModel.onTitleChange("Title")
        viewModel.onQuoteChange("Quote")
        viewModel.onSave()
        advanceUntilIdle()

        assertEquals(R.string.commitment_save_error_generic, viewModel.uiState.value.saveError?.resId)
        viewModel.clearSaveError()
        assertNull(viewModel.uiState.value.saveError)
    }

    @Test
    fun `save validation failure uses localized generic validation copy`() = runTest {
        coEvery { commitmentRepository.saveManualCommitment(any(), null) } returns
            BecalmResult.Failure(BecalmError.Validation("title", "Title cannot be empty"))

        val viewModel = buildViewModel()
        viewModel.onTitleChange("Title")
        viewModel.onQuoteChange("Quote")
        viewModel.onSave()
        advanceUntilIdle()

        assertEquals(R.string.commitment_save_error_validation, viewModel.uiState.value.saveError?.resId)
    }

    private fun buildViewModel(supersedeOf: String? = null): CommitmentCreateViewModel =
        CommitmentCreateViewModel(
            commitmentRepository = commitmentRepository,
            userPrefsStore = userPrefsStore,
            savedStateHandle = SavedStateHandle(
                if (supersedeOf == null) {
                    emptyMap()
                } else {
                    mapOf(BecalmRoute.CommitmentCreate.ARG_SUPERSEDE_OF to supersedeOf)
                },
            ),
            logger = logger,
        )

    private fun entity(
        id: String,
        quote: String = "quote body",
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        direction = "give",
        counterpartyRaw = null,
        counterpartyRef = "alice@example.com",
        title = "Old title",
        description = null,
        quote = quote,
        sourceEventTitle = "Call",
        sourceEventOccurredAt = Instant.fromEpochMilliseconds(1_000),
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        actionState = "pending",
        sourceType = "voice",
        sourceRef = null,
        confidence = 0.9,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
        lastEditedBy = null,
        lastEditedAt = null,
        quoteDisputed = false,
        quoteDisputedAt = null,
        deletedAt = null,
        supersedesCommitmentId = null,
    )
}

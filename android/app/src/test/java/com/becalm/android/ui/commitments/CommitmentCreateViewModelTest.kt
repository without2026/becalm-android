package com.becalm.android.ui.commitments

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.domain.commitment.CommitmentManualValidator
import com.becalm.android.domain.commitment.ManualCommitmentInput
import com.becalm.android.ui.navigation.BecalmRoute
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [CommitmentCreateViewModel] covering MAN-001..006 and the
 * EDIT-007 supersede handoff:
 *
 * - MANUAL mode + empty form → save surfaces field errors (via
 *   [CommitmentManualValidator]) and never calls the repository.
 * - MANUAL mode + valid draft → repository is invoked with `supersedeOf=null`
 *   and the sheet flips [CreateUiState.saved] true.
 * - SUPERSEDE mode loads the old row and seeds the read-only quote + form.
 * - SUPERSEDE save uses the old row's quote verbatim and passes the old id
 *   through to [CommitmentRepository.saveManualCommitment].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommitmentCreateViewModelTest {

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

    private fun savedState(supersedeOf: String? = null): SavedStateHandle =
        SavedStateHandle(
            if (supersedeOf == null) emptyMap()
            else mapOf(BecalmRoute.CommitmentCreate.ARG_SUPERSEDE_OF to supersedeOf),
        )

    private fun buildViewModel(supersedeOf: String? = null): CommitmentCreateViewModel =
        CommitmentCreateViewModel(
            commitmentRepository = commitmentRepository,
            savedStateHandle = savedState(supersedeOf),
            logger = logger,
        )

    // ─── MANUAL mode ──────────────────────────────────────────────────────────

    @Test
    fun `MANUAL empty form onSave surfaces validation errors and skips repository`() =
        runTest(testDispatcher) {
            val viewModel = buildViewModel(supersedeOf = null)
            assertEquals(CommitmentCreateMode.MANUAL, viewModel.uiState.value.mode)

            viewModel.onSave()
            testDispatcher.scheduler.advanceUntilIdle()

            // Title + quote are empty → validator flags both fields.
            val errors = viewModel.uiState.value.fieldErrors
            assertTrue(
                "TITLE must be flagged",
                errors.containsKey(CommitmentManualValidator.Field.TITLE),
            )
            assertTrue(
                "QUOTE must be flagged",
                errors.containsKey(CommitmentManualValidator.Field.QUOTE),
            )
            // Not saved; repository was not called.
            assertFalse(viewModel.uiState.value.saved)
            coVerify(exactly = 0) {
                commitmentRepository.saveManualCommitment(any(), any())
            }
        }

    @Test
    fun `MANUAL valid draft onSave calls repository with supersedeOf null and flips saved`() =
        runTest(testDispatcher) {
            coEvery {
                commitmentRepository.saveManualCommitment(any(), null)
            } returns BecalmResult.Success("new-id-1")

            val viewModel = buildViewModel(supersedeOf = null)
            viewModel.onTitleChange("Send contract")
            viewModel.onDirectionChange("give")
            viewModel.onQuoteChange("I'll send the contract by Friday.")

            viewModel.dismiss.test {
                viewModel.onSave()
                testDispatcher.scheduler.advanceUntilIdle()
                awaitItem() // Dismiss emitted on success.
                cancelAndIgnoreRemainingEvents()
            }

            assertTrue(viewModel.uiState.value.saved)
            assertNull(viewModel.uiState.value.saveError)
            // Capture the input that reached the repo.
            val inputSlot = slot<ManualCommitmentInput>()
            coVerify(exactly = 1) {
                commitmentRepository.saveManualCommitment(capture(inputSlot), null)
            }
            assertEquals("Send contract", inputSlot.captured.title)
            assertEquals("give", inputSlot.captured.direction)
            assertEquals(
                "I'll send the contract by Friday.",
                inputSlot.captured.quote,
            )
        }

    @Test
    fun `MANUAL repository failure surfaces saveError and does not flip saved`() =
        runTest(testDispatcher) {
            coEvery {
                commitmentRepository.saveManualCommitment(any(), null)
            } returns BecalmResult.Failure(BecalmError.Network(0, "offline"))

            val viewModel = buildViewModel(supersedeOf = null)
            viewModel.onTitleChange("t")
            viewModel.onDirectionChange("give")
            viewModel.onQuoteChange("q")

            viewModel.onSave()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse("saved must remain false on failure", state.saved)
            assertEquals("저장 실패 — 다시 시도해주세요", state.saveError)
        }

    // ─── SUPERSEDE mode ───────────────────────────────────────────────────────

    @Test
    fun `SUPERSEDE mode loads old row and seeds read-only quote`() = runTest(testDispatcher) {
        val old = makeEntity(
            id = "cmt-old",
            title = "Old title",
            quote = "Old evidentiary quote",
        )
        coEvery { commitmentRepository.observeById("cmt-old") } returns flowOf(old)

        val viewModel = buildViewModel(supersedeOf = "cmt-old")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CommitmentCreateMode.SUPERSEDE, state.mode)
        assertEquals(old, state.supersedeSource)
        // Old row quote seeded into draft so onSave can re-send it verbatim.
        assertEquals("Old evidentiary quote", state.draft.quote)
        assertEquals("Old title", state.draft.title)
    }

    @Test
    fun `SUPERSEDE onSave passes old id and preserves old quote verbatim`() =
        runTest(testDispatcher) {
            val old = makeEntity(
                id = "cmt-old",
                title = "Old",
                quote = "Old quote verbatim",
            )
            coEvery { commitmentRepository.observeById("cmt-old") } returns flowOf(old)
            coEvery {
                commitmentRepository.saveManualCommitment(any(), "cmt-old")
            } returns BecalmResult.Success("new-id-2")

            val viewModel = buildViewModel(supersedeOf = "cmt-old")
            testDispatcher.scheduler.advanceUntilIdle()

            // User tweaks the title but does NOT mutate the read-only quote.
            viewModel.onTitleChange("New title")
            // Defensive: the VM must ignore quote writes in SUPERSEDE mode.
            viewModel.onQuoteChange("tampered quote")

            viewModel.onSave()
            testDispatcher.scheduler.advanceUntilIdle()

            val inputSlot = slot<ManualCommitmentInput>()
            coVerify(exactly = 1) {
                commitmentRepository.saveManualCommitment(
                    capture(inputSlot),
                    "cmt-old",
                )
            }
            // Quote is the old row's verbatim string, not the tampered one.
            assertEquals("Old quote verbatim", inputSlot.captured.quote)
            assertEquals("New title", inputSlot.captured.title)
            assertTrue(viewModel.uiState.value.saved)
        }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun makeEntity(
        id: String,
        title: String = "Title",
        quote: String = "quote body",
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        direction = "give",
        counterpartyRaw = null,
        personRef = null,
        title = title,
        description = null,
        quote = quote,
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
        quoteDisputed = false,
        quoteDisputedAt = null,
        deletedAt = null,
        supersedesCommitmentId = null,
    )
}

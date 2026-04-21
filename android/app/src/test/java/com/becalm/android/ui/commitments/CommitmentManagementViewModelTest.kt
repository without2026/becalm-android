package com.becalm.android.ui.commitments

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.domain.commitment.CommitmentEvent
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.domain.reminder.ReminderScheduler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
class CommitmentManagementViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val commitmentRepository: CommitmentRepository = mockk(relaxed = true)
    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk(relaxed = true)
    private val reminderScheduler: ReminderScheduler = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    private lateinit var viewModel: CommitmentManagementViewModel

    /** Minimal [CommitmentEntity] factory — only the fields exercised by tests are meaningful. */
    private fun makeEntity(
        id: String = "id-${System.nanoTime()}",
        direction: String = "give",
        commitmentState: CommitmentState = CommitmentState.DRAFT,
        actionState: String = "pending",
        dueAt: Instant? = null,
        dueHint: String? = null,
        dueIsApproximate: Boolean = false,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        direction = direction,
        counterpartyRaw = null,
        personRef = null,
        title = "Test commitment $id",
        description = null,
        quote = "quote",
        sourceEventTitle = null,
        sourceEventOccurredAt = Instant.DISTANT_PAST,
        dueAt = dueAt,
        dueHint = dueHint,
        dueIsApproximate = dueIsApproximate,
        actionState = actionState,
        sourceType = "voice",
        sourceRef = null,
        confidence = 0.9,
        commitmentState = commitmentState,
        syncStatus = "synced",
        createdAt = Instant.DISTANT_PAST,
        updatedAt = Instant.DISTANT_PAST,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Default: user is signed in with id "user-1".
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")

        // Default: observeAllForUser returns empty list.
        every { commitmentRepository.observeAllForUser(any()) } returns flowOf(emptyList())

        // Default: enrichment map is empty (no personRef resolution).
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())
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

    // ── Test 1: list + filter ─────────────────────────────────────────────────

    /**
     * Given commitments of different directions, filter changes to GIVE then TAKE
     * should contain only matching entities. Covers CMT-001..003.
     */
    @Test
    fun `list emits all items and filter reduces to matching direction`() = runTest {
        val giveEntity = makeEntity(id = "give-1", direction = "give")
        val takeEntity = makeEntity(id = "take-1", direction = "take")

        every { commitmentRepository.observeAllForUser("user-1") } returns
            flowOf(listOf(giveEntity, takeEntity))

        viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem() // loading=true, items=[]

            val settled = awaitItem()
            assertEquals(false, settled.loading)
            assertEquals(2, settled.items.size)

            viewModel.onFilterChange(CommitmentFilter.GIVE)
            val giveFiltered = awaitItem()
            assertEquals(CommitmentFilter.GIVE, giveFiltered.filter)
            assertTrue(giveFiltered.items.all { it.direction == "give" })
            assertEquals(1, giveFiltered.items.size)

            viewModel.onFilterChange(CommitmentFilter.TAKE)
            val takeFiltered = awaitItem()
            assertEquals(CommitmentFilter.TAKE, takeFiltered.filter)
            assertTrue(takeFiltered.items.all { it.direction == "take" })
            assertEquals(1, takeFiltered.items.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Test 2: observeAllForUser drives the list (not per-state observers) ───

    /**
     * When observeAllForUser emits, all items pass through applyFilter(ALL).
     * Covers the R6 refactor: single Room subscription.
     */
    @Test
    fun `observeAllForUser emits all items pass through applyFilter ALL`() = runTest {
        val entities = listOf(
            makeEntity(id = "e-1", commitmentState = CommitmentState.DRAFT),
            makeEntity(id = "e-2", commitmentState = CommitmentState.CONFIRMED),
            makeEntity(id = "e-3", commitmentState = CommitmentState.DONE),
        )
        every { commitmentRepository.observeAllForUser("user-1") } returns flowOf(entities)

        viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem() // loading=true

            val settled = awaitItem()
            assertEquals(false, settled.loading)
            assertEquals(CommitmentFilter.ALL, settled.filter)
            assertEquals(3, settled.items.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Test 2b: dueHint threaded through projection ──────────────────────────

    /**
     * The approximate-due hint verbatim expression must reach the UI layer so
     * users can understand inferred deadlines. Regression guard for R3-F1 —
     * commitment-management.spec.yml:9,13.
     */
    @Test
    fun `dueHint is threaded from entity to CommitmentRow`() = runTest {
        val approxEntity = makeEntity(
            id = "approx-1",
            dueAt = Instant.parse("2026-04-30T00:00:00Z"),
            dueHint = "월말",
            dueIsApproximate = true,
        )
        val exactEntity = makeEntity(
            id = "exact-1",
            dueAt = Instant.parse("2026-04-20T00:00:00Z"),
            dueHint = null,
            dueIsApproximate = false,
        )
        every { commitmentRepository.observeAllForUser("user-1") } returns
            flowOf(listOf(approxEntity, exactEntity))

        viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem() // loading
            val settled = awaitItem()

            val approxRow = settled.items.single { it.id == "approx-1" }
            val exactRow = settled.items.single { it.id == "exact-1" }

            assertEquals("월말", approxRow.dueHint)
            assertTrue(approxRow.dueIsApproximate)
            assertNull(exactRow.dueHint)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Test 3: null userId → empty list, loading=false ───────────────────────

    @Test
    fun `null userId from userPrefsStore emits empty items and loading false`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(null)

        viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem() // loading=true

            val settled = awaitItem()
            assertEquals(false, settled.loading)
            assertTrue(settled.items.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Test 4: onConfirm ─────────────────────────────────────────────────────

    /**
     * When onConfirm succeeds, error is cleared. When it fails, error is set.
     * Covers CMT-005.
     */
    @Test
    fun `onConfirm surfaces success and failure via uiState`() = runTest {
        viewModel = buildViewModel()

        val updatedEntity = makeEntity(id = "c-1", commitmentState = CommitmentState.CONFIRMED)

        coEvery {
            commitmentRepository.transitionState("c-1", CommitmentEvent.Confirm)
        } returns BecalmResult.Success(updatedEntity)

        viewModel.uiState.test {
            awaitItem() // initial loading
            skipItems(1) // settled after collection

            viewModel.onConfirm("c-1")
            val afterSuccess = awaitItem()
            assertNull(afterSuccess.error)

            coEvery {
                commitmentRepository.transitionState("c-1", CommitmentEvent.Confirm)
            } returns BecalmResult.Failure(BecalmError.Validation("commitmentState", "Illegal transition"))

            viewModel.onConfirm("c-1")
            val afterFailure = awaitItem()
            assertNotNull(afterFailure.error)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Test 5: onSchedule triggers reminder ──────────────────────────────────

    /**
     * When onSchedule succeeds, ReminderScheduler.schedule is called with the
     * commitment id and the kotlinx Instant forwarded unchanged. Covers CMT-006.
     */
    @Test
    fun `onSchedule calls reminderScheduler on success`() = runTest {
        viewModel = buildViewModel()

        val at: Instant = Clock.System.now() + 1.hours
        val updatedEntity = makeEntity(id = "s-1", commitmentState = CommitmentState.SCHEDULED)

        coEvery {
            commitmentRepository.transitionState("s-1", CommitmentEvent.Schedule(at))
        } returns BecalmResult.Success(updatedEntity)

        every { reminderScheduler.schedule(any(), any()) } just runs

        viewModel.uiState.test {
            awaitItem()
            skipItems(1)

            viewModel.onSchedule("s-1", at)
            val afterSchedule = awaitItem()
            assertNull(afterSchedule.error)

            verify(exactly = 1) {
                reminderScheduler.schedule("s-1", at)
            }

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Test 6: onMarkDone cancels reminder ───────────────────────────────────

    /**
     * When onMarkDone succeeds, ReminderScheduler.cancel is called and error is cleared.
     * Covers CMT-007.
     */
    @Test
    fun `onMarkDone cancels reminder on success`() = runTest {
        viewModel = buildViewModel()

        val doneEntity = makeEntity(id = "d-1", commitmentState = CommitmentState.DONE)

        coEvery {
            commitmentRepository.transitionState("d-1", CommitmentEvent.MarkDone)
        } returns BecalmResult.Success(doneEntity)

        every { reminderScheduler.cancel(any()) } just runs

        viewModel.uiState.test {
            awaitItem()
            skipItems(1)

            viewModel.onMarkDone("d-1")
            val afterDone = awaitItem()
            assertNull(afterDone.error)

            verify(exactly = 1) { reminderScheduler.cancel("d-1") }

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Test 7: onErrorDismissed clears error (R8/H-5) ────────────────────────

    @Test
    fun `onErrorDismissed clears error`() = runTest {
        viewModel = buildViewModel()

        coEvery {
            commitmentRepository.transitionState(any(), CommitmentEvent.Confirm)
        } returns BecalmResult.Failure(BecalmError.Validation("state", "bad transition"))

        viewModel.uiState.test {
            awaitItem() // loading
            skipItems(1) // settled

            viewModel.onConfirm("any-id")
            val withError = awaitItem()
            assertNotNull(withError.error)

            viewModel.onErrorDismissed()
            val afterDismiss = awaitItem()
            assertNull(afterDismiss.error)

            cancelAndIgnoreRemainingEvents()
        }
    }
}

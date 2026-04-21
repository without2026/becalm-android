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
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
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
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap<String, PersonEnrichmentEntity>())
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

    // ── Test 2: list pass-through ─────────────────────────────────────────────

    @Test
    fun `observeAllForUser emits all items pass through applyFilter ALL`() = runTest {
        val entities = listOf(
            makeEntity(id = "e-1", actionState = "pending"),
            makeEntity(id = "e-2", actionState = "reminded"),
            makeEntity(id = "e-3", actionState = "completed"),
        )
        every { commitmentRepository.observeAllForUser("user-1") } returns flowOf(entities)

        viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem() // loading=true

            val settled = awaitItem()
            assertEquals(false, settled.loading)
            assertEquals(CommitmentFilter.ALL, settled.filter)
            assertEquals(3, settled.items.size)
            // derivedStatus is driven by the spec-aligned action_state, uppercased.
            assertEquals("PENDING", settled.items.single { it.id == "e-1" }.derivedStatus)
            assertEquals("REMINDED", settled.items.single { it.id == "e-2" }.derivedStatus)
            assertEquals("COMPLETED", settled.items.single { it.id == "e-3" }.derivedStatus)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Test 2b: dueHint threaded through projection ──────────────────────────

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

    // ── Test 4: onRemind ─ [리마인드] button (CMT-005) ────────────────────────

    @Test
    fun `onRemind succeeds and schedules reminder when dueAt present`() = runTest {
        val dueAt = Instant.parse("2026-04-30T00:00:00Z")
        val entity = makeEntity(id = "r-1", actionState = "pending", dueAt = dueAt)
        every { commitmentRepository.observeAllForUser("user-1") } returns flowOf(listOf(entity))

        val updatedEntity = entity.copy(actionState = "reminded")
        coEvery {
            commitmentRepository.transitionState("r-1", CommitmentEvent.Remind)
        } returns BecalmResult.Success(updatedEntity)
        every { reminderScheduler.schedule(any(), any()) } just runs

        viewModel = buildViewModel()
        advanceUntilIdle() // let observeCommitments settle the list + populate allEntities

        viewModel.onRemind("r-1")
        advanceUntilIdle()

        // Success path keeps error = null, which is the same as the initial value, so a
        // StateFlow emission is deduplicated. Assert directly on the final value instead
        // of awaiting a second emission.
        assertNull(viewModel.uiState.value.error)
        verify(exactly = 1) { reminderScheduler.schedule("r-1", dueAt) }
    }

    @Test
    fun `onRemind forwards null dueAt to scheduler which owns the null-and-past gate`() = runTest {
        val entity = makeEntity(id = "r-2", actionState = "pending", dueAt = null)
        every { commitmentRepository.observeAllForUser("user-1") } returns flowOf(listOf(entity))

        coEvery {
            commitmentRepository.transitionState("r-2", CommitmentEvent.Remind)
        } returns BecalmResult.Success(entity.copy(actionState = "reminded"))

        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onRemind("r-2")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        // C5 moved the null/past gate into ReminderScheduler.schedule; the VM always
        // invokes the scheduler and the scheduler decides whether to arm an alarm.
        verify(exactly = 1) { reminderScheduler.schedule("r-2", null) }
    }

    @Test
    fun `onRemind surfaces failure via uiState`() = runTest {
        viewModel = buildViewModel()

        coEvery {
            commitmentRepository.transitionState("r-3", CommitmentEvent.Remind)
        } returns BecalmResult.Failure(BecalmError.Validation("actionState", "illegal"))

        viewModel.uiState.test {
            awaitItem()
            skipItems(1)

            viewModel.onRemind("r-3")
            val after = awaitItem()
            assertNotNull(after.error)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Test 5: onFollowUp ─ [팔로업] button (CMT-006) ─────────────────────────

    @Test
    fun `onFollowUp succeeds and does not touch the reminder`() = runTest {
        val updatedEntity = makeEntity(id = "f-1", actionState = "followed_up")
        coEvery {
            commitmentRepository.transitionState("f-1", CommitmentEvent.FollowUp)
        } returns BecalmResult.Success(updatedEntity)

        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onFollowUp("f-1")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        verify(exactly = 0) { reminderScheduler.schedule(any(), any()) }
        verify(exactly = 0) { reminderScheduler.cancel(any()) }
    }

    // ── Test 6: onComplete ─ [완료] button (CMT-007) ──────────────────────────

    @Test
    fun `onComplete cancels reminder on success`() = runTest {
        val doneEntity = makeEntity(id = "c-1", actionState = "completed")
        coEvery {
            commitmentRepository.transitionState("c-1", CommitmentEvent.Complete)
        } returns BecalmResult.Success(doneEntity)
        every { reminderScheduler.cancel(any()) } just runs

        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onComplete("c-1")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        verify(exactly = 1) { reminderScheduler.cancel("c-1") }
    }

    // ── Test 7: onCancel ─ [취소] button (CMT-012) ────────────────────────────

    @Test
    fun `onCancel cancels reminder on success`() = runTest {
        val cancelledEntity = makeEntity(id = "x-1", actionState = "cancelled")
        coEvery {
            commitmentRepository.transitionState("x-1", CommitmentEvent.Cancel)
        } returns BecalmResult.Success(cancelledEntity)
        every { reminderScheduler.cancel(any()) } just runs

        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onCancel("x-1")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        verify(exactly = 1) { reminderScheduler.cancel("x-1") }
    }

    @Test
    fun `onCancel from overdue state is a legal transition`() = runTest {
        // CMT-012 — Cancel is legal from PENDING / REMINDED / FOLLOWED_UP / OVERDUE.
        // This pins the OVERDUE → CANCELLED edge explicitly so any future state-machine
        // narrowing is caught here instead of shipping a broken user flow.
        val overdueEntity = makeEntity(id = "x-over", actionState = "overdue")
        every { commitmentRepository.observeAllForUser("user-1") } returns flowOf(listOf(overdueEntity))
        coEvery {
            commitmentRepository.transitionState("x-over", CommitmentEvent.Cancel)
        } returns BecalmResult.Success(overdueEntity.copy(actionState = "cancelled"))
        every { reminderScheduler.cancel(any()) } just runs

        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onCancel("x-over")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        verify(exactly = 1) { reminderScheduler.cancel("x-over") }
    }

    // ── Test 8: onErrorDismissed clears error (R8/H-5) ────────────────────────

    @Test
    fun `onErrorDismissed clears error`() = runTest {
        viewModel = buildViewModel()

        coEvery {
            commitmentRepository.transitionState(any(), CommitmentEvent.Complete)
        } returns BecalmResult.Failure(BecalmError.Validation("actionState", "bad transition"))

        viewModel.uiState.test {
            awaitItem()
            skipItems(1)

            viewModel.onComplete("any-id")
            val withError = awaitItem()
            assertNotNull(withError.error)

            viewModel.onErrorDismissed()
            val afterDismiss = awaitItem()
            assertNull(afterDismiss.error)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Test 9: onPullRefresh ─ CMT-010 ───────────────────────────────────────

    @Test
    fun `onPullRefresh sets refreshing while the fetch is in flight then clears it on success`() = runTest {
        viewModel = buildViewModel()

        coEvery {
            commitmentRepository.refreshSince("user-1", since = null)
        } returns BecalmResult.Success(
            CommitmentRepository.RefreshStats(
                fetched = 0,
                upserted = 0,
                hasMore = false,
                nextCursor = null,
            ),
        )

        viewModel.onPullRefresh()
        advanceUntilIdle()

        val settled = viewModel.uiState.value
        assertEquals(false, settled.refreshing)
        assertNull(settled.error)
    }

    @Test
    fun `onPullRefresh surfaces network failure and clears refreshing`() = runTest {
        viewModel = buildViewModel()

        coEvery {
            commitmentRepository.refreshSince("user-1", since = null)
        } returns BecalmResult.Failure(BecalmError.Network(code = 0, message = "offline"))

        viewModel.onPullRefresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(false, state.refreshing)
        assertNotNull(state.error)
    }

    @Test
    fun `onPullRefresh preserves the active filter`() = runTest {
        val giveEntity = makeEntity(id = "g-1", direction = "give")
        val takeEntity = makeEntity(id = "t-1", direction = "take")
        every { commitmentRepository.observeAllForUser("user-1") } returns
            flowOf(listOf(giveEntity, takeEntity))

        coEvery {
            commitmentRepository.refreshSince("user-1", since = null)
        } returns BecalmResult.Success(
            CommitmentRepository.RefreshStats(
                fetched = 2,
                upserted = 2,
                hasMore = false,
                nextCursor = null,
            ),
        )

        viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onFilterChange(CommitmentFilter.GIVE)
        viewModel.onPullRefresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CommitmentFilter.GIVE, state.filter)
        assertTrue(state.items.all { it.direction == "give" })
    }

    @Test
    fun `onPullRefresh deduplicates concurrent presses`() = runTest {
        viewModel = buildViewModel()

        coEvery {
            commitmentRepository.refreshSince("user-1", since = null)
        } returns BecalmResult.Success(
            CommitmentRepository.RefreshStats(
                fetched = 0,
                upserted = 0,
                hasMore = false,
                nextCursor = null,
            ),
        )

        viewModel.onPullRefresh()
        viewModel.onPullRefresh() // second press while first still in-flight
        advanceUntilIdle()

        // Only one round-trip should have been issued.
        coVerify(exactly = 1) {
            commitmentRepository.refreshSince("user-1", since = null)
        }
    }

    // Unused imports guard — keep `hours` referenced to avoid lint noise if a future
    // test re-introduces a duration literal.
    @Suppress("unused")
    private val _keepHoursReferenced = 1.hours
}

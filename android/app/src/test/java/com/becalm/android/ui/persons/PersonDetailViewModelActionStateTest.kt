package com.becalm.android.ui.persons

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Before
import org.junit.Test

/**
 * Verifies that [PersonDetailViewModel] partitions commitments into "completed"
 * and "pending" sections using the **v5 `action_state` column only**, not the
 * legacy `commitment_state` column (which drifts on the dispute/edit path per
 * `CommitmentRepositoryImpl`).
 *
 * Covers the S5-C plan's `partitions_by_actionState_*` acceptance criteria.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonDetailViewModelActionStateTest {

    private val testDispatcher = StandardTestDispatcher()

    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val commitmentRepository: CommitmentRepository = mockk()
    private val calendarEventRepository: CalendarEventRepository = mockk()
    private val userPrefsStore: UserPrefsStore = mockk()
    private val logger: Logger = mockk(relaxed = true)

    private val now: Instant = Clock.System.now()
    private val personRef = "alice@example.com"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { personEnrichmentRepository.observeByPersonRef(personRef) } returns flowOf(null)
        every { rawIngestionRepository.observeForPerson(any(), eq(personRef), any()) } returns
            flowOf(emptyList())
        every { calendarEventRepository.observeForPerson(any(), eq(personRef), any()) } returns
            flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): PersonDetailViewModel {
        val handle = SavedStateHandle(mapOf(ARG_PERSON_REF to personRef))
        return PersonDetailViewModel(
            personEnrichmentRepository = personEnrichmentRepository,
            rawIngestionRepository = rawIngestionRepository,
            commitmentRepository = commitmentRepository,
            calendarEventRepository = calendarEventRepository,
            userPrefsStore = userPrefsStore,
            savedStateHandle = handle,
            logger = logger,
        )
    }

    private fun makeCommitment(
        id: String,
        actionState: String,
        legacy: CommitmentLifecycleLegacy = CommitmentLifecycleLegacy.DRAFT,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        direction = "give",
        counterpartyRaw = personRef,
        personRef = personRef,
        title = "Commit $id",
        description = null,
        quote = "quote",
        sourceEventTitle = null,
        sourceEventOccurredAt = now,
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        actionState = actionState,
        sourceType = "gmail",
        sourceRef = null,
        confidence = 0.0,
        commitmentState = legacy,
        createdAt = now,
        updatedAt = now,
    )

    // ─── Partition tests ─────────────────────────────────────────────────────

    @Test
    fun `completed action_state lands in completedCommitments`() = runTest(testDispatcher) {
        every { commitmentRepository.observeAllForPerson("user-1", personRef) } returns flowOf(
            listOf(
                makeCommitment(id = "a", actionState = "completed"),
                makeCommitment(id = "b", actionState = "pending"),
                makeCommitment(id = "c", actionState = "reminded"),
            ),
        )

        val vm = buildViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(1, state.completedCommitments.size)
            assertEquals(2, state.pendingCommitments.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `followed_up action_state stays pending`() = runTest(testDispatcher) {
        every { commitmentRepository.observeAllForPerson("user-1", personRef) } returns flowOf(
            listOf(makeCommitment(id = "a", actionState = "followed_up")),
        )

        val vm = buildViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(0, state.completedCommitments.size)
            assertEquals(1, state.pendingCommitments.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `legacy commitment_state DONE but action_state pending stays pending`() = runTest(testDispatcher) {
        // Drift scenario: dispute/edit path flipped the legacy column but action_state
        // remained pending. The partition MUST respect the v5 column.
        every { commitmentRepository.observeAllForPerson("user-1", personRef) } returns flowOf(
            listOf(
                makeCommitment(
                    id = "drift",
                    actionState = "pending",
                    legacy = CommitmentLifecycleLegacy.DONE,
                ),
            ),
        )

        val vm = buildViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(0, state.completedCommitments.size)
            assertEquals(1, state.pendingCommitments.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `action_state casing is normalized`() = runTest(testDispatcher) {
        // Defensive — some ingestion paths may hand us an uppercase string.
        every { commitmentRepository.observeAllForPerson("user-1", personRef) } returns flowOf(
            listOf(makeCommitment(id = "upper", actionState = "COMPLETED")),
        )

        val vm = buildViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(1, state.completedCommitments.size)
            assertEquals(0, state.pendingCommitments.size)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

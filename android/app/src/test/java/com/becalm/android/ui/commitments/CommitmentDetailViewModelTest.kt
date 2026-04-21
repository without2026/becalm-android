package com.becalm.android.ui.commitments

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.ui.navigation.BecalmRoute
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Unit tests for [CommitmentDetailViewModel] covering Wave 4 C4:
 *
 * 1. Happy path — repository emits an entity + enrichment map whose display-name
 *    key matches the entity's `personRef`, so the resolved counterparty label
 *    appears in [DetailUiState].
 * 2. Null-entity path — repository emits `null` (soft-deleted row or unknown id)
 *    → state flips to `error == EMPTY_ERROR_KEY`, `loading == false`.
 * 3. Disputed flag passthrough — `quote_disputed = true` on the backing entity
 *    flows through to the UI state so the composable can render the badge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommitmentDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val commitmentRepository: CommitmentRepository = mockk(relaxed = true)
    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Default: empty enrichment map so tests that do not care about display-name
        // resolution need no extra stubbing.
        every { personEnrichmentRepository.observeEnrichmentMap() } returns
            flowOf(emptyMap<String, PersonEnrichmentEntity>())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun savedState(id: String = "cmt-1"): SavedStateHandle =
        SavedStateHandle(mapOf(BecalmRoute.CommitmentDetail.ARG_ID to id))

    private fun buildViewModel(id: String = "cmt-1"): CommitmentDetailViewModel =
        CommitmentDetailViewModel(
            commitmentRepository = commitmentRepository,
            personEnrichmentRepository = personEnrichmentRepository,
            savedStateHandle = savedState(id),
            logger = logger,
        )

    // ─── Happy path ───────────────────────────────────────────────────────────

    @Test
    fun `repo emits entity and enrichment resolves counterparty display name`() = runTest(testDispatcher) {
        val entity = makeEntity(id = "cmt-1", personRef = "phone:+82-10-1234-5678")
        val enrichment = PersonEnrichmentEntity(
            personRef = "phone:+82-10-1234-5678",
            displayName = "Alice Kim",
            lastSyncedAt = Instant.fromEpochMilliseconds(1),
        )
        every { commitmentRepository.observeById("cmt-1") } returns flowOf(entity)
        every { personEnrichmentRepository.observeEnrichmentMap() } returns
            flowOf(mapOf(entity.personRef!! to enrichment))

        val viewModel = buildViewModel("cmt-1")

        viewModel.uiState.test {
            awaitItem() // initial loading=true, entity=null
            val settled = awaitItem()
            assertEquals(false, settled.loading)
            assertNull(settled.error)
            assertNotNull(settled.entity)
            assertEquals("cmt-1", settled.entity!!.id)
            assertEquals("Alice Kim", settled.counterpartyDisplayName)
            assertEquals(CommitmentState.PENDING, settled.actionState)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Null-entity path ─────────────────────────────────────────────────────

    @Test
    fun `null entity from repo flips error to empty-key and clears loading`() = runTest(testDispatcher) {
        every { commitmentRepository.observeById("cmt-gone") } returns flowOf(null)

        val viewModel = buildViewModel("cmt-gone")

        viewModel.uiState.test {
            awaitItem() // initial loading=true
            val settled = awaitItem()
            assertEquals(false, settled.loading)
            assertEquals(CommitmentDetailViewModel.EMPTY_ERROR_KEY, settled.error)
            assertNull(settled.entity)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Disputed flag passthrough ────────────────────────────────────────────

    @Test
    fun `quote disputed flows through to DetailUiState entity field`() = runTest(testDispatcher) {
        val disputedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val entity = makeEntity(
            id = "cmt-d",
            quoteDisputed = true,
            quoteDisputedAt = disputedAt,
        )
        every { commitmentRepository.observeById("cmt-d") } returns flowOf(entity)

        val viewModel = buildViewModel("cmt-d")

        viewModel.uiState.test {
            awaitItem()
            val settled = awaitItem()
            assertNotNull(settled.entity)
            assertTrue(settled.entity!!.quoteDisputed)
            assertEquals(disputedAt, settled.entity!!.quoteDisputedAt)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Reactive re-emit ─────────────────────────────────────────────────────

    @Test
    fun `state re-emits when repository flow emits a new entity`() = runTest(testDispatcher) {
        val initial = makeEntity(id = "cmt-r", actionState = "pending")
        val updated = initial.copy(actionState = "reminded")
        val flow = MutableStateFlow<CommitmentEntity?>(initial)
        every { commitmentRepository.observeById("cmt-r") } returns flow

        val viewModel = buildViewModel("cmt-r")

        viewModel.uiState.test {
            awaitItem() // loading
            val first = awaitItem()
            assertEquals(CommitmentState.PENDING, first.actionState)

            flow.value = updated
            val second = awaitItem()
            assertEquals(CommitmentState.REMINDED, second.actionState)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun makeEntity(
        id: String = "cmt-1",
        personRef: String? = null,
        actionState: String = "pending",
        quoteDisputed: Boolean = false,
        quoteDisputedAt: Instant? = null,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        direction = "give",
        counterpartyRaw = null,
        personRef = personRef,
        title = "Test commitment $id",
        description = null,
        quote = "quote body",
        sourceEventTitle = "Standup",
        sourceEventOccurredAt = Instant.fromEpochMilliseconds(1_000),
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        actionState = actionState,
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
        quoteDisputedAt = quoteDisputedAt,
        deletedAt = null,
        supersedesCommitmentId = null,
    )
}

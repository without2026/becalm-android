package com.becalm.android.unit.ui.commitments

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.ui.commitments.CommitmentDetailViewModel
import com.becalm.android.ui.navigation.BecalmRoute
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
import kotlin.reflect.full.memberProperties
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommitmentDetailViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val commitmentRepository: CommitmentRepository = mockk(relaxed = true)
    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `CMT-003 detail state projects full quote source attribution and available actions`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "detail-1") } returns flowOf(
            entity(
                id = "detail-1",
                quote = "다음 주까지 보고서 제출",
                sourceEventTitle = "2024-03-15 팀 미팅",
                sourceEventOccurredAt = Instant.parse("2026-04-18T06:00:00Z"),
                actionState = "pending",
            ),
        )

        val viewModel = buildViewModel("detail-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("다음 주까지 보고서 제출", state.quote)
        assertEquals("2024-03-15 팀 미팅", state.source.sourceTitle)
        assertEquals(Instant.parse("2026-04-18T06:00:00Z"), state.source.sourceOccurredAt)
        assertEquals(
            listOf("REMIND", "FOLLOW_UP", "COMPLETE", "CANCEL"),
            state.actionButtons.availableActions.map { action -> action.toString() },
        )
    }

    @Test
    fun `CMT-003 EDIT-001 tapping edit emits effect for editable commitment`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "detail-edit") } returns
            flowOf(entity(id = "detail-edit"))

        val viewModel = buildViewModel("detail-edit")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.actionButtons.editEnabled)

        viewModel.effects.test {
            viewModel.onEditClick()
            advanceUntilIdle()

            val effect = awaitItem()
            assertEquals("detail-edit", propertyValue(effect, "commitmentId"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `EDIT-001 cancelled or deleted commitments expose read only detail state`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "cancelled") } returns
            flowOf(entity(id = "cancelled", actionState = "cancelled"))
        every { commitmentRepository.observeByIdForUser("user-1", "deleted") } returns
            flowOf(entity(id = "deleted", deletedAt = Instant.parse("2026-04-18T07:00:00Z")))

        val cancelledViewModel = buildViewModel("cancelled")
        val deletedViewModel = buildViewModel("deleted")
        advanceUntilIdle()

        assertFalse(cancelledViewModel.uiState.value.actionButtons.editEnabled)
        assertFalse(deletedViewModel.uiState.value.actionButtons.editEnabled)
    }

    @Test
    fun `EDIT-008 history projects last edited dispute and supersede markers`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "history-1") } returns flowOf(
            entity(
                id = "history-1",
                lastEditedAt = Instant.parse("2026-04-18T05:30:00Z"),
                quoteDisputed = true,
                quoteDisputedAt = Instant.parse("2026-04-18T05:31:00Z"),
                supersedesCommitmentId = "old-1",
            ),
        )

        val viewModel = buildViewModel("history-1")
        advanceUntilIdle()

        val history = viewModel.uiState.value.history
        assertEquals("마지막 수정: 4/18 14:30 (본인)", history.lastEditedLabel)
        assertEquals("⚠️ 이의 제기됨 — 4/18 14:31", history.disputedLabel)
        assertTrue(history.showSupersedeLink)
    }

    @Test
    fun `MAN-004 manual commitments project manual source presentation and badge flag`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "manual-1") } returns flowOf(
            entity(
                id = "manual-1",
                sourceType = "manual",
                sourceEventTitle = null,
                sourceEventOccurredAt = Instant.parse("2026-04-18T05:30:00Z"),
                createdAt = Instant.parse("2026-04-18T05:30:00Z"),
            ),
        )

        val viewModel = buildViewModel("manual-1")
        advanceUntilIdle()

        val source = viewModel.uiState.value.source
        assertTrue(source.isManual)
        assertNull(source.sourceTitle)
        assertEquals("사용자 직접 추가 2026-04-18 14:30 KST", source.sourceLabel)
    }

    @Test
    fun `non action detail state exposes read only trackable with no action buttons`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "schedule-1") } returns flowOf(
            entity(
                id = "schedule-1",
                itemType = CommitmentItemType.SCHEDULE,
                direction = null,
            ),
        )

        val viewModel = buildViewModel("schedule-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.actionButtons.availableActions.isEmpty())
        assertFalse(state.actionButtons.editEnabled)
        assertEquals(CommitmentItemType.SCHEDULE, state.entity?.itemType)
    }

    private fun buildViewModel(id: String): CommitmentDetailViewModel = CommitmentDetailViewModel(
        commitmentRepository = commitmentRepository,
        personEnrichmentRepository = personEnrichmentRepository,
        userPrefsStore = userPrefsStore,
        savedStateHandle = SavedStateHandle(mapOf(BecalmRoute.CommitmentDetail.ARG_ID to id)),
        logger = logger,
        ioDispatcher = testDispatcher,
    )

    private fun propertyValue(instance: Any, name: String): Any? =
        instance::class.memberProperties.first { it.name == name }.getter.call(instance)

    private fun entity(
        id: String,
        itemType: String = CommitmentItemType.ACTION,
        direction: String? = "give",
        quote: String = "quote body",
        sourceEventTitle: String? = "Standup",
        sourceEventOccurredAt: Instant = Instant.parse("2026-04-18T06:00:00Z"),
        actionState: String = "pending",
        sourceType: String = "voice",
        createdAt: Instant = Instant.parse("2026-04-18T05:00:00Z"),
        lastEditedAt: Instant? = null,
        quoteDisputed: Boolean = false,
        quoteDisputedAt: Instant? = null,
        deletedAt: Instant? = null,
        supersedesCommitmentId: String? = null,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        itemType = itemType,
        direction = direction,
        scheduleStatus = null,
        decisionStatus = null,
        counterpartyRaw = null,
        counterpartyRef = "lee@corp.com",
        title = "Title",
        description = null,
        quote = quote,
        sourceEventTitle = sourceEventTitle,
        sourceEventOccurredAt = sourceEventOccurredAt,
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        actionState = actionState,
        sourceType = sourceType,
        sourceRef = null,
        confidence = 0.8,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
        createdAt = createdAt,
        updatedAt = createdAt,
        lastEditedBy = if (lastEditedAt == null) null else "user-1",
        lastEditedAt = lastEditedAt,
        quoteDisputed = quoteDisputed,
        quoteDisputedAt = quoteDisputedAt,
        deletedAt = deletedAt,
        supersedesCommitmentId = supersedesCommitmentId,
    )
}

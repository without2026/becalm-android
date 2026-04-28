package com.becalm.android.unit.ui.commitments

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentManagementRow
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.domain.commitment.CommitmentEvent
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.domain.reminder.ReminderScheduler
import com.becalm.android.ui.commitments.CommitmentFilter
import com.becalm.android.ui.commitments.CommitmentManagementViewModel
import com.becalm.android.ui.commitments.CommitmentUndoSnapshot
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
import kotlin.reflect.full.memberProperties
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommitmentManagementViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val commitmentRepository: CommitmentRepository = mockk(relaxed = true)
    private val reminderScheduler: ReminderScheduler = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `CMT-001 counterparty display resolution follows enrichment precedence`() = runTest {
        val displayNameEntity = entity(
            id = "a",
            direction = "give",
            personRef = "lee@corp.com",
            sourceType = "gmail",
            sourceEventTitle = "Kickoff mail",
            sourceEventOccurredAt = Instant.parse("2026-04-24T01:00:00Z"),
        )
        val samePersonEntity = entity(id = "a2", direction = "take", personRef = "lee@corp.com")
        val nicknameEntity = entity(id = "b", direction = "take", personRef = "kim@corp.com")
        val fallbackPersonRefEntity = entity(id = "c", direction = "give", personRef = "park@corp.com")
        val legacyRawEntity = entity(id = "d", direction = "take", personRef = null, counterpartyRaw = "Legacy Raw Name")

        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(
            managementRows(
                displayNameEntity,
                samePersonEntity,
                nicknameEntity,
                fallbackPersonRefEntity,
                legacyRawEntity,
                enrichment = mapOf(
                    "lee@corp.com" to "이대리",
                    "kim@corp.com" to "김팀장",
                ),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem()
            val settled = awaitItem()
            assertEquals(5, settled.items.size)
            assertEquals("이대리", settled.items.single { it.id == "a" }.counterpartyDisplayName)
            assertEquals("gmail", settled.items.single { it.id == "a" }.sourceType)
            assertEquals("Kickoff mail", settled.items.single { it.id == "a" }.sourceTitle)
            assertEquals(
                Instant.parse("2026-04-24T01:00:00Z"),
                settled.items.single { it.id == "a" }.sourceOccurredAt,
            )
            assertEquals("김팀장", settled.items.single { it.id == "b" }.counterpartyDisplayName)
            assertEquals("park@corp.com", settled.items.single { it.id == "c" }.counterpartyDisplayName)
            assertEquals("Legacy Raw Name", settled.items.single { it.id == "d" }.counterpartyDisplayName)
            assertEquals(listOf(2, 1, 1, 1), settled.activePersonGroups.map { it.count })
            assertEquals(listOf("a", "a2"), settled.activePersonGroups.first().items.map { it.id })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CMT-002 filter tabs isolate give take and all commitments`() = runTest {
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(
            managementRows(
                entity(id = "give-1", direction = "give"),
                entity(id = "give-2", direction = "give"),
                entity(id = "take-1", direction = "take"),
                entity(
                    id = "schedule-1",
                    itemType = "schedule",
                    direction = null,
                    scheduleStatus = "changed",
                ),
                entity(
                    id = "decision-1",
                    itemType = "decision",
                    direction = null,
                    decisionStatus = "chosen",
                ),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem()
            val initial = awaitItem()
            assertEquals(CommitmentFilter.ALL, initial.filter)
            assertEquals(
                listOf("give-1", "give-2", "take-1", "schedule-1", "decision-1"),
                initial.items.map { it.id },
            )

            viewModel.onFilterChange(CommitmentFilter.ACTION)
            val actionOnly = awaitItem()
            assertEquals(CommitmentFilter.ACTION, actionOnly.filter)
            assertEquals(listOf("give-1", "give-2", "take-1"), actionOnly.items.map { it.id })
            assertTrue(actionOnly.items.all { it.itemType == "action" })

            viewModel.onFilterChange(CommitmentFilter.GIVE)
            val giveOnly = awaitItem()
            assertEquals(CommitmentFilter.GIVE, giveOnly.filter)
            assertEquals(listOf("give-1", "give-2"), giveOnly.items.map { it.id })
            assertTrue(giveOnly.items.all { it.itemType == "action" && it.direction == "give" })

            viewModel.onFilterChange(CommitmentFilter.TAKE)
            val takeOnly = awaitItem()
            assertEquals(CommitmentFilter.TAKE, takeOnly.filter)
            assertEquals(listOf("take-1"), takeOnly.items.map { it.id })
            assertTrue(takeOnly.items.all { it.itemType == "action" && it.direction == "take" })

            viewModel.onFilterChange(CommitmentFilter.SCHEDULE)
            val scheduleOnly = awaitItem()
            assertEquals(CommitmentFilter.SCHEDULE, scheduleOnly.filter)
            assertEquals(listOf("schedule-1"), scheduleOnly.items.map { it.id })
            assertTrue(scheduleOnly.items.all { it.itemType == "schedule" && it.scheduleStatus == "changed" })

            viewModel.onFilterChange(CommitmentFilter.DECISION)
            val decisionOnly = awaitItem()
            assertEquals(CommitmentFilter.DECISION, decisionOnly.filter)
            assertEquals(listOf("decision-1"), decisionOnly.items.map { it.id })
            assertTrue(decisionOnly.items.all { it.itemType == "decision" && it.decisionStatus == "chosen" })

            viewModel.onFilterChange(CommitmentFilter.ALL)
            val allAgain = awaitItem()
            assertEquals(CommitmentFilter.ALL, allAgain.filter)
            assertEquals(
                listOf("give-1", "give-2", "take-1", "schedule-1", "decision-1"),
                allAgain.items.map { it.id },
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CMT-003 card selection emits detail navigation with tapped commitment id`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.navigation.test {
            viewModel.onCommitmentSelected("detail-1")

            val navigation = awaitItem()
            assertEquals("detail-1", propertyValue(navigation, "commitmentId"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CMT-009 completed and cancelled sections stay collapsed by default and toggle independently`() = runTest {
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(
            managementRows(
                entity(id = "pending-1", actionState = "pending"),
                entity(id = "completed-1", actionState = "completed"),
                entity(id = "completed-2", actionState = "completed"),
                entity(id = "cancelled-1", actionState = "cancelled"),
            ),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val initial = viewModel.uiState.value
        assertEquals(listOf("pending-1"), initial.activeItems.map { it.id })
        assertEquals(2, initial.completedSection.count)
        assertEquals(listOf("completed-1", "completed-2"), initial.completedSection.items.map { it.id })
        assertEquals(false, initial.completedSection.expanded)
        assertEquals(true, initial.completedSection.dimmed)
        assertEquals(1, initial.cancelledSection.count)
        assertEquals(listOf("cancelled-1"), initial.cancelledSection.items.map { it.id })
        assertEquals(false, initial.cancelledSection.expanded)
        assertEquals(true, initial.cancelledSection.dimmed)

        viewModel.onToggleCompletedSection()
        val completedExpanded = viewModel.uiState.value
        assertEquals(true, completedExpanded.completedSection.expanded)
        assertEquals(false, completedExpanded.cancelledSection.expanded)

        viewModel.onToggleCancelledSection()
        val cancelledExpanded = viewModel.uiState.value
        assertEquals(true, cancelledExpanded.completedSection.expanded)
        assertEquals(true, cancelledExpanded.cancelledSection.expanded)
    }

    @Test
    fun `MAN-004 manual rows keep manual badge projection separate from lifecycle state`() = runTest {
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(
            managementRows(
                entity(id = "manual-1", actionState = "pending", sourceType = "manual"),
                entity(id = "voice-1", actionState = "pending", sourceType = "voice"),
            ),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val items = viewModel.uiState.value.items.associateBy { it.id }
        assertEquals(true, items.getValue("manual-1").isManual)
        assertEquals(false, items.getValue("voice-1").isManual)
        assertEquals("PENDING", items.getValue("manual-1").derivedStatus)
    }

    @Test
    fun `CMT-001 rows preserve type subtype and counterparty display`() = runTest {
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(
            managementRows(
                entity(id = "action-1", direction = "give", personRef = "lee@corp.com"),
                entity(
                    id = "schedule-1",
                    itemType = "schedule",
                    direction = null,
                    personRef = "park@corp.com",
                    scheduleStatus = "changed",
                ),
                entity(
                    id = "decision-1",
                    itemType = "decision",
                    direction = null,
                    personRef = null,
                    counterpartyRaw = "Legacy Person",
                    decisionStatus = "ongoing",
                ),
                enrichment = mapOf(
                    "lee@corp.com" to "이대리",
                    "park@corp.com" to "박과장",
                ),
            ),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val rows = viewModel.uiState.value.items.associateBy { it.id }
        assertEquals("action", rows.getValue("action-1").itemType)
        assertEquals("give", rows.getValue("action-1").direction)
        assertEquals("이대리", rows.getValue("action-1").counterpartyDisplayName)
        assertEquals("schedule", rows.getValue("schedule-1").itemType)
        assertEquals("changed", rows.getValue("schedule-1").scheduleStatus)
        assertEquals("박과장", rows.getValue("schedule-1").counterpartyDisplayName)
        assertEquals("decision", rows.getValue("decision-1").itemType)
        assertEquals("ongoing", rows.getValue("decision-1").decisionStatus)
        assertEquals("Legacy Person", rows.getValue("decision-1").counterpartyDisplayName)
        assertEquals(null, rows.getValue("schedule-1").derivedStatus)
        assertEquals(null, rows.getValue("decision-1").derivedStatus)
    }

    @Test
    fun `CMT-010 pull refresh uses current user and surfaces repository failure`() = runTest {
        coEvery { commitmentRepository.refreshSince("user-1", since = null) } returns
            BecalmResult.Failure(BecalmError.Io("boom"))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onPullRefresh()
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.refreshing)
        assertTrue(viewModel.uiState.value.error?.contains("boom") == true)
        coVerify(exactly = 1) { commitmentRepository.refreshSince("user-1", since = null) }
    }

    @Test
    fun `CMT-005 remind success schedules reminder when dueAt is present`() = runTest {
        val dueAt = Instant.parse("2026-04-20T03:00:00Z")
        val pending = entity(id = "remind-1", dueAt = dueAt)
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(managementRows(pending))
        coEvery { commitmentRepository.transitionState("remind-1", CommitmentEvent.Remind) } returns
            BecalmResult.Success(pending.copy(actionState = "reminded"))
        coEvery { reminderScheduler.schedule(any(), any()) } just runs

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onRemind("remind-1")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        coVerify(exactly = 1) { reminderScheduler.schedule("remind-1", dueAt) }
    }

    @Test
    fun `CMT-005 remind forwards null dueAt to scheduler which owns alarm gating`() = runTest {
        val pending = entity(id = "remind-2", dueAt = null)
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(managementRows(pending))
        coEvery { commitmentRepository.transitionState("remind-2", CommitmentEvent.Remind) } returns
            BecalmResult.Success(pending.copy(actionState = "reminded"))
        coEvery { reminderScheduler.schedule(any(), any()) } just runs

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onRemind("remind-2")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        coVerify(exactly = 1) { reminderScheduler.schedule("remind-2", null) }
    }

    @Test
    fun `CMT-005 remind failure surfaces error and skips reminder scheduling`() = runTest {
        coEvery { commitmentRepository.transitionState("remind-3", CommitmentEvent.Remind) } returns
            BecalmResult.Failure(BecalmError.Validation("actionState", "illegal"))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onRemind("remind-3")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.error?.contains("illegal") == true)
        coVerify(exactly = 0) { reminderScheduler.schedule(any(), any()) }
    }

    @Test
    fun `CMT-006 follow up success changes state without reminder side effects`() = runTest {
        coEvery { commitmentRepository.transitionState("follow-1", CommitmentEvent.FollowUp) } returns
            BecalmResult.Success(entity(id = "follow-1", actionState = "followed_up"))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onFollowUp("follow-1")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        coVerify(exactly = 0) { reminderScheduler.schedule(any(), any()) }
        verify(exactly = 0) { reminderScheduler.cancel(any()) }
    }

    @Test
    fun `CMT-007 complete cancels reminder and emits undo snapshot with prior state`() = runTest {
        val item = entity(id = "complete-1", actionState = "reminded")
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(managementRows(item))
        coEvery { commitmentRepository.transitionState("complete-1", CommitmentEvent.Complete) } returns
            BecalmResult.Success(item.copy(actionState = "completed"))
        every { reminderScheduler.cancel("complete-1") } just runs

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.undoFlow.test {
            viewModel.onComplete("complete-1")
            advanceUntilIdle()

            assertEquals(
                CommitmentUndoSnapshot.Completed("complete-1", CommitmentState.REMINDED),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
        verifyCancel("complete-1")
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `CMT-012 cancel without cached entity still succeeds but emits no undo snapshot`() = runTest {
        coEvery { commitmentRepository.transitionState("cancel-1", CommitmentEvent.Cancel) } returns
            BecalmResult.Success(entity(id = "cancel-1", actionState = "cancelled"))
        every { reminderScheduler.cancel("cancel-1") } just runs

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.undoFlow.test {
            viewModel.onCancel("cancel-1")
            advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        verifyCancel("cancel-1")
    }

    @Test
    fun `CMT-012 cancel from overdue remains legal and captures overdue as undo prior state`() = runTest {
        val overdue = entity(id = "cancel-overdue", actionState = "overdue")
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(managementRows(overdue))
        coEvery { commitmentRepository.transitionState("cancel-overdue", CommitmentEvent.Cancel) } returns
            BecalmResult.Success(overdue.copy(actionState = "cancelled"))
        every { reminderScheduler.cancel("cancel-overdue") } just runs

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.undoFlow.test {
            viewModel.onCancel("cancel-overdue")
            advanceUntilIdle()

            assertEquals(
                CommitmentUndoSnapshot.Cancelled("cancel-overdue", CommitmentState.OVERDUE),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
        verifyCancel("cancel-overdue")
    }

    @Test
    fun `CMT-013 undo writes prior action state and surfaces failure`() = runTest {
        coEvery {
            commitmentRepository.updateActionState(
                id = "undo-1",
                newState = "pending",
                updatedAt = any(),
            )
        } returns BecalmResult.Failure(BecalmError.Io("undo failed"))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onUndo(CommitmentUndoSnapshot.Completed("undo-1", CommitmentState.PENDING))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.error?.contains("undo failed") == true)
        coVerify(exactly = 1) {
            commitmentRepository.updateActionState(
                id = "undo-1",
                newState = "pending",
                updatedAt = any(),
            )
        }
    }

    @Test
    fun `CMT-013 undo does not re-register reminder alarms`() = runTest {
        coEvery {
            commitmentRepository.updateActionState(
                id = "undo-2",
                newState = "reminded",
                updatedAt = any(),
            )
        } returns BecalmResult.Success(Unit)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onUndo(CommitmentUndoSnapshot.Completed("undo-2", CommitmentState.REMINDED))
        advanceUntilIdle()

        coVerify(exactly = 0) { reminderScheduler.schedule(any(), any()) }
    }

    private fun buildViewModel(): CommitmentManagementViewModel = CommitmentManagementViewModel(
        commitmentRepository = commitmentRepository,
        reminderScheduler = reminderScheduler,
        userPrefsStore = userPrefsStore,
        logger = logger,
    )

    private fun verifyCancel(id: String) = io.mockk.verify(exactly = 1) { reminderScheduler.cancel(id) }

    private fun propertyValue(instance: Any, name: String): Any? =
        instance::class.memberProperties.first { it.name == name }.getter.call(instance)

    private fun entity(
        id: String,
        itemType: String = "action",
        direction: String? = "give",
        actionState: String = "pending",
        personRef: String? = null,
        counterpartyRaw: String? = null,
        dueAt: Instant? = null,
        sourceType: String = "voice",
        sourceEventTitle: String? = null,
        sourceEventOccurredAt: Instant = Instant.parse("2026-04-18T00:00:00Z"),
        scheduleStatus: String? = null,
        decisionStatus: String? = null,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        itemType = itemType,
        direction = direction,
        scheduleStatus = scheduleStatus,
        decisionStatus = decisionStatus,
        counterpartyRaw = counterpartyRaw,
        personRef = personRef,
        title = "title-$id",
        description = null,
        quote = "quote",
        sourceEventTitle = sourceEventTitle,
        sourceEventOccurredAt = sourceEventOccurredAt,
        dueAt = dueAt,
        dueHint = null,
        dueIsApproximate = false,
        actionState = actionState,
        sourceType = sourceType,
        sourceRef = null,
        confidence = 1.0,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
        createdAt = Instant.parse("2026-04-18T00:00:00Z"),
        updatedAt = Instant.parse("2026-04-18T00:00:00Z"),
    )

    private fun managementRows(
        vararg entities: CommitmentEntity,
        enrichment: Map<String, String> = emptyMap(),
    ): List<CommitmentManagementRow> =
        entities.map { entity ->
            CommitmentManagementRow(
                id = entity.id,
                itemType = entity.itemType,
                title = entity.title,
                direction = entity.direction,
                scheduleStatus = entity.scheduleStatus,
                decisionStatus = entity.decisionStatus,
                actionState = entity.actionState,
                dueAt = entity.dueAt,
                dueIsApproximate = entity.dueIsApproximate,
                counterpartyDisplayName = entity.personRef?.let { ref ->
                    enrichment[ref] ?: ref
                } ?: entity.counterpartyRaw?.take(30),
                sourceType = entity.sourceType,
                sourceTitle = entity.sourceEventTitle,
                sourceOccurredAt = entity.sourceEventOccurredAt,
                dueHint = entity.dueHint,
            )
        }
}

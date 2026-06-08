package com.becalm.android.unit.ui.commitments

import app.cash.turbine.test
import com.becalm.android.R
import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.util.FakeClock
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentManagementRow
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentAgendaIntent
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.PersonActionSyncStateEntity
import com.becalm.android.data.local.db.entity.ScheduleEventLinkEntity
import com.becalm.android.data.local.db.entity.ScheduleEventLinkResolutionChoice
import com.becalm.android.data.remote.dto.PersonActionEvidenceOriginalDetailDto
import com.becalm.android.data.remote.dto.PersonActionEvidenceOriginalDto
import com.becalm.android.data.remote.dto.PersonActionEvidenceRefDto
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonActionMutationSyncStats
import com.becalm.android.data.repository.PersonActionRefreshStats
import com.becalm.android.data.repository.PersonActionRepository
import com.becalm.android.data.repository.ScheduleEventLinkRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.domain.commitment.CommitmentEvent
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.domain.reminder.ReminderScheduler
import com.becalm.android.ui.commitments.CommitmentFilter
import com.becalm.android.ui.commitments.CommitmentManagementViewModel
import com.becalm.android.ui.commitments.CommitmentPersonGroupType
import com.becalm.android.ui.commitments.CommitmentUndoSnapshot
import com.becalm.android.ui.actions.PersonActionFeedStatusKind
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class CommitmentManagementViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val commitmentRepository: CommitmentRepository = mockk(relaxed = true)
    private val sourceEventParticipantRepository: SourceEventParticipantRepository = mockk(relaxed = true)
    private val commitmentParticipantRepository: CommitmentParticipantRepository = mockk(relaxed = true)
    private val scheduleEventLinkRepository: ScheduleEventLinkRepository = mockk(relaxed = true)
    private val personActionRepository: PersonActionRepository = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val reminderScheduler: ReminderScheduler = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val clock = FakeClock(Instant.parse("2026-05-04T03:00:00Z"))
    private val actionSyncState = MutableStateFlow<PersonActionSyncStateEntity?>(null)

    @Before
    fun setUp() {
        actionSyncState.value = null
        Dispatchers.setMain(testDispatcher)
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeDisabledCommitmentReminderIds() } returns flowOf(emptySet())
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(emptyList())
        every {
            scheduleEventLinkRepository.observeForProjectionRefs(any(), any(), any(), any())
        } returns flowOf(emptyList())
        every { personActionRepository.observeActiveForSurface(any(), any(), any()) } returns flowOf(emptyList())
        every { personActionRepository.observeSyncState(any(), any(), any()) } returns actionSyncState
        coEvery { personActionRepository.refresh(any(), any()) } returns
            BecalmResult.Success(
                PersonActionRefreshStats(
                    fetched = 0,
                    deleted = 0,
                    serverWatermark = null,
                    recomputeState = null,
                ),
            )
        coEvery { personActionRepository.completeAction(any(), any(), any()) } returns
            BecalmResult.Success(PersonActionMutationSyncStats(queued = 1, synced = 1, retryable = 0, failed = 0))
        coEvery { sourceEventParticipantRepository.refreshSince(any(), any(), any()) } returns
            BecalmResult.Success(
                SourceEventParticipantRepository.RefreshStats(
                    fetched = 0,
                    upserted = 0,
                    hasMore = false,
                    nextCursor = null,
                ),
            )
        coEvery { commitmentParticipantRepository.refreshSince(any(), any(), any(), any()) } returns
            BecalmResult.Success(
                CommitmentParticipantRepository.RefreshStats(
                    fetched = 0,
                    upserted = 0,
                    hasMore = false,
                    nextCursor = null,
                ),
            )
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
            counterpartyRef = "lee@corp.com",
            sourceType = "gmail",
            sourceEventTitle = "Kickoff mail",
            sourceEventOccurredAt = Instant.parse("2026-04-24T01:00:00Z"),
        )
        val samePersonEntity = entity(id = "a2", direction = "take", counterpartyRef = "lee@corp.com")
        val nicknameEntity = entity(id = "b", direction = "take", counterpartyRef = "kim@corp.com")
        val fallbackCounterpartyRefEntity = entity(id = "c", direction = "give", counterpartyRef = "park@corp.com")
        val legacyRawEntity = entity(id = "d", direction = "take", counterpartyRef = null, counterpartyRaw = "Legacy Raw Name")

        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(
            managementRows(
                displayNameEntity,
                samePersonEntity,
                nicknameEntity,
                fallbackCounterpartyRefEntity,
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
    fun `person action complete calls backend mutation without touching commitment FSM`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onCompletePersonAction("pa-1")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            personActionRepository.completeAction(userId = "user-1", actionItemId = "pa-1", expectedUpdatedAt = null)
        }
        coVerify(exactly = 0) {
            commitmentRepository.transitionState(any(), any())
        }
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `person action complete surfaces retryable error when backend mutation cannot be queued`() = runTest {
        coEvery { personActionRepository.completeAction(any(), any(), any()) } returns
            BecalmResult.Failure(BecalmError.Network(503, "unavailable"))
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onCompletePersonAction("pa-fail")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            personActionRepository.completeAction(userId = "user-1", actionItemId = "pa-fail", expectedUpdatedAt = null)
        }
        assertEquals(R.string.commitments_error_action_failed, viewModel.uiState.value.error?.resId)
    }

    @Test
    fun `person action evidence fetch opens backend original detail`() = runTest {
        coEvery {
            personActionRepository.fetchEvidenceOriginal(
                userId = "user-1",
                actionItemId = "pa-1",
                evidenceKind = "source_event",
                evidenceId = "source-1",
            )
        } returns BecalmResult.Success(
            evidenceOriginal(
                actionItemId = "pa-1",
                evidenceKind = "source_event",
                evidenceId = "source-1",
                evidenceLabel = "Gmail thread",
                evidenceQuote = "Please send the proposal tomorrow.",
                originalTitle = "Proposal thread",
                originalQuote = "Full original email body",
                localOriginalText = "Full original email body",
                sourceType = "gmail",
            ),
        )
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onOpenPersonActionEvidence("pa-1", "source_event", "source-1")
        advanceUntilIdle()

        val detail = checkNotNull(viewModel.uiState.value.evidenceDetail)
        assertEquals("pa-1", detail.actionItemId)
        assertEquals("Gmail thread", detail.evidenceLabel)
        assertEquals("Please send the proposal tomorrow.", detail.whyText)
        assertEquals("Proposal thread", detail.originalTitle)
        assertEquals("Full original email body", detail.originalText)
        assertEquals("gmail", detail.sourceType)
        assertNull(viewModel.uiState.value.loadingEvidenceActionId)
        assertNull(viewModel.uiState.value.error)
        coVerify(exactly = 1) {
            personActionRepository.fetchEvidenceOriginal(
                userId = "user-1",
                actionItemId = "pa-1",
                evidenceKind = "source_event",
                evidenceId = "source-1",
            )
        }
    }

    @Test
    fun `person action evidence fetch failure surfaces retryable error`() = runTest {
        coEvery { personActionRepository.fetchEvidenceOriginal(any(), any(), any(), any()) } returns
            BecalmResult.Failure(BecalmError.Network(503, "unavailable"))
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onOpenPersonActionEvidence("pa-fail", "source_event", "source-fail")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.evidenceDetail)
        assertNull(viewModel.uiState.value.loadingEvidenceActionId)
        assertEquals(R.string.commitments_error_evidence_failed, viewModel.uiState.value.error?.resId)
    }

    @Test
    fun `person action evidence dismiss clears opened detail`() = runTest {
        coEvery { personActionRepository.fetchEvidenceOriginal(any(), any(), any(), any()) } returns
            BecalmResult.Success(evidenceOriginal(actionItemId = "pa-dismiss"))
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onOpenPersonActionEvidence("pa-dismiss", "source_event", "source-event-1")
        advanceUntilIdle()
        assertEquals("pa-dismiss", viewModel.uiState.value.evidenceDetail?.actionItemId)

        viewModel.onDismissPersonActionEvidence()

        assertNull(viewModel.uiState.value.evidenceDetail)
    }

    @Test
    fun `message screenshot management rows hide import timestamp from card source context`() = runTest {
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(
            managementRows(
                entity(
                    id = "screenshot-1",
                    sourceType = "message_screenshot",
                    sourceEventTitle = "카카오톡 캡처",
                    sourceEventOccurredAt = Instant.parse("2026-05-17T06:00:00Z"),
                    dueAt = Instant.parse("2026-05-20T05:00:00Z"),
                ),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem()
            val settled = awaitItem()
            val row = settled.items.single()
            assertEquals("message_screenshot", row.sourceType)
            assertEquals("카카오톡 캡처", row.sourceTitle)
            assertNull(row.sourceOccurredAt)
            assertEquals(Instant.parse("2026-05-20T05:00:00Z"), row.dueAt)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun schedule_coordination_actions_stay_in_ordinary_commitment_feed() = runTest {
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(
            managementRows(
                entity(id = "ordinary-action", direction = "give"),
                entity(
                    id = "meeting-coordinate",
                    direction = "take",
                    agendaIntent = CommitmentAgendaIntent.SCHEDULE_COORDINATION,
                ),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem()
            val settled = awaitItem()

            assertEquals(listOf("ordinary-action", "meeting-coordinate"), settled.items.map { it.id })
            assertTrue(settled.items.any { it.id == "meeting-coordinate" })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CMT-002 filter tabs isolate give take and normalize legacy closed without schedules`() = runTest {
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
                entity(id = "completed-1", direction = "give", actionState = "completed"),
                entity(id = "cancelled-1", direction = "take", actionState = "cancelled"),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem()
            val initial = awaitItem()
            assertEquals(CommitmentFilter.ALL, initial.filter)
            assertEquals(
                listOf(
                    "give-1",
                    "give-2",
                    "take-1",
                ),
                initial.items.map { it.id },
            )
            assertFalse(initial.items.any { it.itemType == "decision" })

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

            viewModel.onFilterChange(CommitmentFilter.CLOSED)
            val closedOnly = awaitItem()
            assertEquals(CommitmentFilter.ALL, closedOnly.filter)
            assertEquals(
                listOf(
                    "give-1",
                    "give-2",
                    "take-1",
                ),
                closedOnly.items.map { it.id },
            )
            assertTrue(closedOnly.items.none { it.actionState.isClosedForTest() })

            assertFalse(closedOnly.items.any { it.id == "decision-1" })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `schedule rows are excluded from commitment person groups`() = runTest {
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(
            managementRows(
                entity(id = "action-no-person", itemType = "action", direction = "give"),
                entity(id = "schedule-no-person", itemType = "schedule", direction = null),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem()
            val state = awaitItem()

            assertEquals(
                listOf(CommitmentPersonGroupType.UNKNOWN_PERSON),
                state.activePersonGroups.map { it.type },
            )
            assertEquals(listOf("action-no-person"), state.activePersonGroups[0].items.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `legacy schedule filter normalizes to all and keeps schedule timeline empty`() = runTest {
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(
            managementRows(
                entity(
                    id = "action",
                    itemType = "action",
                    direction = "give",
                    dueAt = Instant.parse("2026-05-04T01:30:00Z"),
                ),
                entity(
                    id = "schedule",
                    itemType = "schedule",
                    direction = null,
                    dueAt = Instant.parse("2026-05-07T00:00:00Z"),
                ),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem()
            awaitItem()

            viewModel.onFilterChange(CommitmentFilter.SCHEDULE)
            advanceUntilIdle()
            val schedule = viewModel.uiState.value

            assertEquals(CommitmentFilter.ALL, schedule.filter)
            assertEquals(listOf("action"), schedule.items.map { it.id })
            assertTrue(schedule.scheduleUpcomingItems.isEmpty())
            assertFalse(schedule.schedulePastSection.visible)

            viewModel.onTogglePastSection()
            assertTrue(awaitItem().pastSection.expanded)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CMT schedule link updates de-emphasize confirmed duplicate action without row requery`() = runTest {
        val linkFlow = MutableStateFlow<List<ScheduleEventLinkEntity>>(emptyList())
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(
            managementRows(entity(id = "action-1", itemType = "action", direction = "give")),
        )
        every {
            scheduleEventLinkRepository.observeForProjectionRefs(
                userId = "user-1",
                commitmentIds = listOf("action-1"),
                rawEventIds = emptyList(),
                calendarEventIds = emptyList(),
            )
        } returns linkFlow

        val viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem()
            val initial = awaitItem()
            assertFalse(initial.items.single { it.id == "action-1" }.deEmphasized)

            linkFlow.value = listOf(scheduleLink(commitmentId = "action-1"))

            val updated = awaitItem()
            assertTrue(updated.items.single { it.id == "action-1" }.deEmphasized)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CMT same schedule resolution de-emphasizes action but adjustment resolution keeps action prominent`() = runTest {
        val linkFlow = MutableStateFlow<List<ScheduleEventLinkEntity>>(emptyList())
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(
            managementRows(entity(id = "action-1", itemType = "action", direction = "give")),
        )
        every {
            scheduleEventLinkRepository.observeForProjectionRefs(
                userId = "user-1",
                commitmentIds = listOf("action-1"),
                rawEventIds = emptyList(),
                calendarEventIds = emptyList(),
            )
        } returns linkFlow

        val viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem()
            awaitItem()

            linkFlow.value = listOf(
                scheduleLink(
                    commitmentId = "action-1",
                    relationType = "conflicts",
                    status = "approved",
                    resolutionChoice = ScheduleEventLinkResolutionChoice.SAME_SCHEDULE,
                ),
            )
            val sameSchedule = awaitItem()
            assertTrue(sameSchedule.items.single { it.id == "action-1" }.deEmphasized)

            linkFlow.value = listOf(
                scheduleLink(
                    commitmentId = "action-1",
                    relationType = "conflicts",
                    status = "approved",
                    resolutionChoice = ScheduleEventLinkResolutionChoice.SCHEDULE_ADJUSTMENT_NEEDED,
                ),
            )
            val adjustmentNeeded = awaitItem()
            assertFalse(adjustmentNeeded.items.single { it.id == "action-1" }.deEmphasized)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `commitment cards only expose exact due dates`() = runTest {
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(
            managementRows(
                entity(
                    id = "exact",
                    dueAt = Instant.parse("2026-05-04T03:00:00Z"),
                    dueIsApproximate = false,
                    dueHint = "정확한 시간",
                ),
                entity(
                    id = "approx",
                    dueAt = Instant.parse("2026-05-05T03:00:00Z"),
                    dueIsApproximate = true,
                    dueHint = "다음주",
                ),
                entity(id = "missing", dueAt = null, dueHint = "언젠가"),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem()
            val settled = awaitItem()
            assertEquals(Instant.parse("2026-05-04T03:00:00Z"), settled.items.single { it.id == "exact" }.dueAt)
            assertNull(settled.items.single { it.id == "exact" }.dueHint)
            assertEquals(Instant.parse("2026-05-05T03:00:00Z"), settled.items.single { it.id == "approx" }.dueAt)
            assertTrue(settled.items.single { it.id == "approx" }.dueIsApproximate)
            assertEquals("다음주", settled.items.single { it.id == "approx" }.dueHint)
            assertNull(settled.items.single { it.id == "missing" }.dueAt)
            assertEquals("언젠가", settled.items.single { it.id == "missing" }.dueHint)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `commitment feed sorts open rows by due proximity from today`() = runTest {
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(
            managementRows(
                entity(
                    id = "no-due",
                    dueAt = null,
                    sourceEventOccurredAt = Instant.parse("2026-05-04T04:00:00Z"),
                ),
                entity(
                    id = "late-exact",
                    dueAt = Instant.parse("2026-05-04T01:00:00Z"),
                    sourceEventOccurredAt = Instant.parse("2026-04-29T03:00:00Z"),
                ),
                entity(
                    id = "approx",
                    dueAt = Instant.parse("2026-05-05T01:00:00Z"),
                    dueIsApproximate = true,
                    sourceEventOccurredAt = Instant.parse("2026-04-29T02:00:00Z"),
                ),
                entity(
                    id = "early-exact",
                    dueAt = Instant.parse("2026-05-03T01:00:00Z"),
                    sourceEventOccurredAt = Instant.parse("2026-04-29T01:00:00Z"),
                ),
                entity(
                    id = "stale-exact",
                    dueAt = Instant.parse("2026-05-02T01:00:00Z"),
                    sourceEventOccurredAt = Instant.parse("2026-04-29T01:30:00Z"),
                ),
                entity(
                    id = "stale-approx",
                    dueAt = Instant.parse("2026-05-02T01:00:00Z"),
                    dueIsApproximate = true,
                    sourceEventOccurredAt = Instant.parse("2026-04-29T01:30:00Z"),
                ),
                entity(
                    id = "stale-no-due",
                    dueAt = null,
                    sourceEventOccurredAt = Instant.parse("2026-04-29T01:30:00Z"),
                ),
                entity(
                    id = "future-exact",
                    dueAt = Instant.parse("2026-05-05T01:00:00Z"),
                    sourceEventOccurredAt = Instant.parse("2026-04-29T01:00:00Z"),
                ),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem()
            val settled = awaitItem()
            assertEquals(
                listOf(
                    "early-exact",
                    "stale-exact",
                    "late-exact",
                    "future-exact",
                    "no-due",
                    "approx",
                    "stale-approx",
                    "stale-no-due",
                ),
                settled.items.map { it.id },
            )
            assertEquals(
                settled.items.map { it.id },
                settled.activeItems.map { it.id },
            )
            assertEquals(
                listOf("early-exact", "late-exact", "future-exact"),
                settled.confirmedSection.items.map { it.id },
            )
            assertEquals(
                listOf("no-due", "approx", "stale-approx", "stale-no-due"),
                settled.reviewSection.items.map { it.id },
            )
            assertEquals(listOf("stale-exact"), settled.pastSection.items.map { it.id })
            assertTrue(settled.confirmedSection.expanded)
            assertTrue(settled.reviewSection.expanded)
            assertFalse(settled.pastSection.expanded)
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
    fun `completed and cancelled history stays hidden from action inbox even through legacy closed filter`() = runTest {
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
        assertEquals(listOf("pending-1"), initial.reviewSection.items.map { it.id })
        assertEquals(true, initial.confirmedSection.expanded)
        assertEquals(true, initial.reviewSection.expanded)
        assertEquals(false, initial.pastSection.expanded)
        assertEquals(0, initial.completedSection.count)
        assertEquals(0, initial.cancelledSection.count)

        viewModel.onFilterChange(CommitmentFilter.CLOSED)
        advanceUntilIdle()

        val closed = viewModel.uiState.value
        assertEquals(CommitmentFilter.ALL, closed.filter)
        assertEquals(listOf("pending-1"), closed.items.map { it.id })
        assertEquals(0, closed.completedSection.count)
        assertEquals(emptyList<String>(), closed.completedSection.items.map { it.id })
        assertEquals(0, closed.cancelledSection.count)
        assertEquals(emptyList<String>(), closed.cancelledSection.items.map { it.id })

        viewModel.onToggleCompletedSection()
        val completedExpanded = viewModel.uiState.value
        assertEquals(0, completedExpanded.completedSection.count)
        assertEquals(emptyList<String>(), completedExpanded.completedSection.items.map { it.id })

        viewModel.onToggleCancelledSection()
        val cancelledExpanded = viewModel.uiState.value
        assertEquals(0, cancelledExpanded.cancelledSection.count)
        assertEquals(emptyList<String>(), cancelledExpanded.cancelledSection.items.map { it.id })

        viewModel.onToggleReviewSection()
        assertEquals(false, viewModel.uiState.value.reviewSection.expanded)

        viewModel.onTogglePastSection()
        assertEquals(true, viewModel.uiState.value.pastSection.expanded)
    }

    @Test
    fun `service lifecycle notifications stay hidden from action inbox`() = runTest {
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(
            managementRows(
                entity(id = "pending-1", actionState = "pending"),
                entity(
                    id = "service-verification",
                    actionState = "pending",
                    counterpartyRaw = "Google",
                    sourceEventTitle = "Google account verification",
                ),
            ),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("pending-1"), state.items.map { it.id })
        assertEquals(listOf("pending-1"), state.activeItems.map { it.id })
        assertFalse(state.reviewSection.items.any { it.id == "service-verification" })
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
                entity(id = "action-1", direction = "give", counterpartyRef = "lee@corp.com"),
                entity(
                    id = "schedule-1",
                    itemType = "schedule",
                    direction = null,
                    counterpartyRef = "park@corp.com",
                    scheduleStatus = "changed",
                ),
                entity(
                    id = "decision-1",
                    itemType = "decision",
                    direction = null,
                    counterpartyRef = null,
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
        assertFalse(rows.containsKey("schedule-1"))
        assertFalse(rows.containsKey("decision-1"))
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
        assertEquals(R.string.commitments_error_refresh_failed, viewModel.uiState.value.error?.resId)
        coVerify(exactly = 1) {
            sourceEventParticipantRepository.refreshSince(userId = "user-1", sourceType = null, since = null)
        }
        coVerify(exactly = 1) { commitmentRepository.refreshSince("user-1", since = null) }
        coVerify(exactly = 0) { commitmentParticipantRepository.refreshSince(any(), any(), any(), any()) }
    }

    @Test
    fun `pull refresh clears refreshing and surfaces error when coordinator throws`() = runTest {
        coEvery { commitmentRepository.refreshSince("user-1", since = null) } throws
            IllegalStateException("boom")

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onPullRefresh()
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.refreshing)
        assertEquals(R.string.commitments_error_refresh_failed, viewModel.uiState.value.error?.resId)
        coVerify(exactly = 1) { commitmentRepository.refreshSince("user-1", since = null) }
    }

    @Test
    fun `CMT-005 reminder toggle on stores opt-in and schedules reminder when dueAt is present`() = runTest {
        val dueAt = Instant.parse("2026-04-20T03:00:00Z")
        val pending = entity(id = "remind-1", dueAt = dueAt)
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(managementRows(pending))
        coEvery { reminderScheduler.schedule(any(), any()) } just runs

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onToggleReminder("remind-1", enabled = true)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        coVerify(atLeast = 1) { userPrefsStore.setCommitmentReminderDisabled("remind-1", false) }
        coVerify(atLeast = 1) { reminderScheduler.schedule("remind-1", dueAt) }
        coVerify(exactly = 0) { commitmentRepository.transitionState("remind-1", CommitmentEvent.Remind) }
    }

    @Test
    fun `CMT-005 reminder toggle on forwards null dueAt to scheduler which owns alarm gating`() = runTest {
        val pending = entity(id = "remind-2", dueAt = null)
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(managementRows(pending))
        coEvery { reminderScheduler.schedule(any(), any()) } just runs

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onToggleReminder("remind-2", enabled = true)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        coVerify(atLeast = 1) { userPrefsStore.setCommitmentReminderDisabled("remind-2", false) }
        coVerify(exactly = 1) { reminderScheduler.schedule("remind-2", null) }
    }

    @Test
    fun `CMT-005 reminder toggle off stores opt-out and cancels alarm`() = runTest {
        val pending = entity(id = "remind-3", dueAt = Instant.parse("2026-04-20T03:00:00Z"))
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(managementRows(pending))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onToggleReminder("remind-3", enabled = false)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        coVerify(atLeast = 1) { userPrefsStore.setCommitmentReminderDisabled("remind-3", true) }
        verifyCancel("remind-3")
        coVerify(exactly = 0) { commitmentRepository.transitionState("remind-3", CommitmentEvent.Remind) }
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
    fun `action exceptions surface error instead of leaving coroutine silent`() = runTest {
        coEvery { commitmentRepository.transitionState("follow-throw", CommitmentEvent.FollowUp) } throws
            IllegalStateException("boom")

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onFollowUp("follow-throw")
        advanceUntilIdle()

        assertEquals(R.string.commitments_error_action_failed, viewModel.uiState.value.error?.resId)
    }

    @Test
    fun `CMT-007 complete cancels reminder and emits undo snapshot with prior state`() = runTest {
        val analytics = RecordingProductAnalyticsClient()
        val item = entity(id = "complete-1", actionState = "reminded")
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(managementRows(item))
        coEvery { commitmentRepository.transitionState("complete-1", CommitmentEvent.Complete) } returns
            BecalmResult.Success(item.copy(actionState = "completed"))
        every { reminderScheduler.cancel("complete-1") } just runs

        val viewModel = buildViewModel(productAnalytics = analytics)
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
        val quality = analytics.events.single {
            it.eventName == ProductAnalyticsEvents.COMMITMENT_QUALITY_REVIEW_SUBMITTED
        }
        assertEquals("accepted_commitment", quality.properties["quality_label"])
        assertEquals(true, quality.properties["is_true_commitment"])
        assertEquals("commitment_action_complete", quality.properties["review_signal"])
    }

    @Test
    fun `duplicate action taps on the same row are ignored while transition is in flight`() = runTest {
        val item = entity(id = "complete-dupe", actionState = "reminded")
        every { commitmentRepository.observeManagementRowsForUser("user-1") } returns flowOf(managementRows(item))
        coEvery { commitmentRepository.transitionState("complete-dupe", CommitmentEvent.Complete) } returns
            BecalmResult.Success(item.copy(actionState = "completed"))
        every { reminderScheduler.cancel("complete-dupe") } just runs

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onComplete("complete-dupe")
        viewModel.onComplete("complete-dupe")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            commitmentRepository.transitionState("complete-dupe", CommitmentEvent.Complete)
        }
        verifyCancel("complete-dupe")
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

        assertEquals(R.string.commitments_error_undo_failed, viewModel.uiState.value.error?.resId)
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

    @Test
    fun `P1-GAP-004 commitment action feed degraded state remains visible with cached rows`() = runTest {
        actionSyncState.value = actionSyncStateEntity(
            surfaceKey = "commitment",
            recomputeState = "degraded",
            capacityState = "backlog",
            backlogLagSeconds = 180,
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val status = viewModel.uiState.value.actionFeedStatus
        assertEquals(PersonActionFeedStatusKind.DEGRADED, status?.kind)
        assertEquals(180, status?.backlogLagSeconds)
    }

    private fun buildViewModel(
        productAnalytics: ProductAnalyticsClient = com.becalm.android.core.analytics.NoopProductAnalyticsClient(),
        personActionRepository: PersonActionRepository = this.personActionRepository,
    ): CommitmentManagementViewModel = CommitmentManagementViewModel(
        commitmentRepository = commitmentRepository,
        sourceEventParticipantRepository = sourceEventParticipantRepository,
        commitmentParticipantRepository = commitmentParticipantRepository,
        scheduleEventLinkRepository = scheduleEventLinkRepository,
        personActionRepository = personActionRepository,
        workScheduler = workScheduler,
        reminderScheduler = reminderScheduler,
        userPrefsStore = userPrefsStore,
        logger = logger,
        productAnalytics = productAnalytics,
        clock = clock,
    )

    private fun verifyCancel(id: String) = io.mockk.verify(exactly = 1) { reminderScheduler.cancel(id) }

    private fun propertyValue(instance: Any, name: String): Any? =
        instance::class.memberProperties.first { it.name == name }.getter.call(instance)

    private fun actionSyncStateEntity(
        surfaceKey: String,
        recomputeState: String?,
        capacityState: String?,
        backlogLagSeconds: Int? = null,
    ): PersonActionSyncStateEntity =
        PersonActionSyncStateEntity(
            userId = "user-1",
            surfaceKey = surfaceKey,
            status = "active",
            serverWatermark = clock.nowInstant(),
            recomputeState = recomputeState,
            capacityState = capacityState,
            capacityBacklogLagSeconds = backlogLagSeconds,
            lastSyncedAt = clock.nowInstant(),
            updatedAt = clock.nowInstant(),
        )

    private fun evidenceOriginal(
        actionItemId: String = "pa-1",
        evidenceKind: String = "source_event",
        evidenceId: String = "source-event-1",
        evidenceLabel: String = "Gmail thread",
        evidenceQuote: String? = "Please send the proposal tomorrow.",
        originalTitle: String? = "Proposal thread",
        originalQuote: String? = "Please send the proposal tomorrow.",
        originalSnippet: String? = "Proposal thread snippet",
        localOriginalText: String? = null,
        sourceType: String? = "gmail",
    ): PersonActionEvidenceOriginalDto =
        PersonActionEvidenceOriginalDto(
            actionItemId = actionItemId,
            actionStatus = "active",
            evidence = PersonActionEvidenceRefDto(
                kind = evidenceKind,
                id = evidenceId,
                sourceRef = "gmail-thread-1",
                occurredAt = Instant.parse("2026-06-03T01:00:00Z"),
                label = evidenceLabel,
                quote = evidenceQuote,
            ),
            original = PersonActionEvidenceOriginalDetailDto(
                kind = evidenceKind,
                id = evidenceId,
                originalAvailable = true,
                status = "metadata_resolved",
                sourceType = sourceType,
                sourceRef = "gmail-thread-1",
                title = originalTitle,
                snippet = originalSnippet,
                quote = originalQuote,
                occurredAt = Instant.parse("2026-06-03T01:00:00Z"),
                rawBodyIncluded = false,
                localOriginalTitle = originalTitle,
                localOriginalText = localOriginalText,
            ),
            resolvedAt = Instant.parse("2026-06-03T04:05:00Z"),
        )

    private fun entity(
        id: String,
        itemType: String = "action",
        direction: String? = "give",
        actionState: String = "pending",
        counterpartyRef: String? = null,
        counterpartyRaw: String? = null,
        dueAt: Instant? = null,
        dueIsApproximate: Boolean = false,
        dueHint: String? = null,
        sourceType: String = "voice",
        sourceEventTitle: String? = null,
        sourceEventOccurredAt: Instant = Instant.parse("2026-04-18T00:00:00Z"),
        scheduleStatus: String? = null,
        decisionStatus: String? = null,
        agendaIntent: String? = null,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        itemType = itemType,
        direction = direction,
        scheduleStatus = scheduleStatus,
        decisionStatus = decisionStatus,
        agendaIntent = agendaIntent,
        counterpartyRaw = counterpartyRaw,
        counterpartyRef = counterpartyRef,
        title = "title-$id",
        description = null,
        quote = "quote",
        sourceEventTitle = sourceEventTitle,
        sourceEventOccurredAt = sourceEventOccurredAt,
        dueAt = dueAt,
        dueHint = dueHint,
        dueIsApproximate = dueIsApproximate,
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
                agendaIntent = entity.agendaIntent,
                actionState = entity.actionState,
                dueAt = entity.dueAt,
                dueIsApproximate = entity.dueIsApproximate,
                counterpartyDisplayName = entity.counterpartyRef?.let { ref ->
                    enrichment[ref] ?: ref
                } ?: entity.counterpartyRaw?.take(30),
                sourceType = entity.sourceType,
                sourceTitle = entity.sourceEventTitle,
                sourceOccurredAt = entity.sourceEventOccurredAt,
                dueHint = entity.dueHint,
            )
        }

    private fun scheduleLink(
        commitmentId: String,
        relationType: String = "confirms",
        status: String = "auto_linked",
        resolutionChoice: String? = null,
    ): ScheduleEventLinkEntity =
        ScheduleEventLinkEntity(
            id = "link-$commitmentId",
            userId = "user-1",
            calendarEventId = "calendar-1",
            calendarSourceType = "google_calendar",
            calendarSourceRef = "calendar-ref-1",
            sourceType = "gmail",
            sourceRef = "mail-1",
            rawEventId = "raw-1",
            commitmentId = commitmentId,
            relationType = relationType,
            status = status,
            confidence = 0.95,
            proposedStartAt = Instant.parse("2026-05-04T04:00:00Z"),
            proposedEndAt = null,
            proposedTitle = "title-$commitmentId",
            evidence = "confirmed",
            resolutionChoice = resolutionChoice,
            createdAt = Instant.parse("2026-05-04T03:00:00Z"),
            updatedAt = Instant.parse("2026-05-04T03:00:00Z"),
        )

    private fun CommitmentState.isClosedForTest(): Boolean =
        this == CommitmentState.COMPLETED || this == CommitmentState.CANCELLED

    private class RecordingProductAnalyticsClient : ProductAnalyticsClient {
        val events: MutableList<ProductAnalyticsEvent> = mutableListOf()

        override fun track(event: ProductAnalyticsEvent) {
            events += event
        }

        override fun setUserScope(userId: String?) = Unit

        override fun resetUserScope() = Unit
    }
}

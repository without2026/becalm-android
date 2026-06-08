package com.becalm.android.unit.ui.persons

import androidx.lifecycle.SavedStateHandle
import com.becalm.android.R
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.ManualMemoryOutboxDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.ManualMemoryOutboxEntity
import com.becalm.android.data.local.db.entity.ManualMemoryOutboxSyncStatus
import com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.remote.dto.PersonActionDraftDto
import com.becalm.android.data.remote.dto.PersonActionDraftProvenanceDto
import com.becalm.android.data.remote.dto.PersonActionDraftSafetyDto
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.PersonActionDraftEvidenceRef
import com.becalm.android.data.repository.PersonActionMutationSyncStats
import com.becalm.android.data.repository.PersonActionRefreshStats
import com.becalm.android.data.repository.PersonActionRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.domain.reminder.ReminderScheduler
import com.becalm.android.ui.persons.ARG_PERSON_ID
import com.becalm.android.ui.persons.ManualMemorySyncStatusKind
import com.becalm.android.ui.persons.PersonActionDraftSheetStatus
import com.becalm.android.ui.persons.PersonDetailViewModel
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
class PersonDetailViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk()
    private val personIndexDao: PersonIndexDao = mockk()
    private val rawIngestionEventDao: RawIngestionEventDao = mockk()
    private val personActionRepository: PersonActionRepository = mockk(relaxed = true)
    private val manualMemoryOutboxDao: ManualMemoryOutboxDao = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val reminderScheduler: ReminderScheduler = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk()
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { personEnrichmentRepository.observeAll() } returns flowOf(emptyList())
        every { personIndexDao.observeIdentitiesForPerson(any(), any()) } returns flowOf(emptyList())
        every { personIndexDao.observeInteractionsForPerson(any(), any(), any()) } returns flowOf(emptyList())
        every { personActionRepository.observeActiveForPerson(any(), any(), any()) } returns flowOf(emptyList())
        every { manualMemoryOutboxDao.observeForPerson(any(), any()) } returns flowOf(emptyList())
        coEvery { personActionRepository.refresh(any(), any()) } returns
            BecalmResult.Success(
                PersonActionRefreshStats(
                    fetched = 0,
                    deleted = 0,
                    serverWatermark = null,
                    recomputeState = null,
                ),
            )
        coEvery { manualMemoryOutboxDao.markFailedForPersonPending(any(), any(), any()) } returns 0
        coEvery { rawIngestionEventDao.findByIdsForUser(any(), any()) } returns emptyList()
        coEvery { rawIngestionEventDao.findBySourceRefsForUser(any(), any()) } returns emptyList()
        coEvery { userPrefsStore.setCommitmentReminderDisabled(any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `missing person id yields immediate error state`() = runTest {
        val viewModel = buildViewModel(personId = "")

        assertEquals(R.string.person_detail_error_missing_id, viewModel.uiState.value.error?.resId)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `detail is projected only from person index rows`() = runTest {
        val personId = "person-1"
        every { personIndexDao.observeIdentitiesForPerson("user-1", personId) } returns
            flowOf(
                listOf(
                    identity(
                        personId = personId,
                        rawValue = "alice@example.com",
                        displayNameHint = "Alice",
                    ),
                ),
            )
        every { personEnrichmentRepository.observeAll() } returns
            flowOf(
                listOf(
                    PersonEnrichmentEntity(
                        personRef = "alice@example.com",
                        displayName = "Alice Kim",
                        nickname = "Al",
                        company = "BeCalm",
                        title = "PM",
                        lastSyncedAt = Instant.fromEpochMilliseconds(0),
                    ),
                ),
            )
        every { personIndexDao.observeInteractionsForPerson("user-1", personId, 150) } returns
            flowOf(
                listOf(
                    interaction(
                        id = "mail",
                        personId = personId,
                        sourceType = SourceType.GMAIL,
                        sourceRef = "raw:raw-mail-1",
                        interactionKind = "email",
                        role = "sender",
                        title = "메일",
                        snippet = "메일에서 다음 액션",
                        occurredAt = Instant.fromEpochMilliseconds(3_000),
                    ),
                    interaction(
                        id = "give",
                        personId = personId,
                        sourceType = SourceType.GMAIL,
                        sourceRef = "commitment:give",
                        interactionKind = "commitment",
                        sourceEventId = "raw-mail-1",
                        commitmentId = "give",
                        role = CommitmentItemType.ACTION,
                        direction = "give",
                        status = "pending",
                        title = "자료 보내기",
                        occurredAt = Instant.fromEpochMilliseconds(3_000),
                    ),
                    interaction(
                        id = "schedule",
                        personId = personId,
                        sourceType = SourceType.GMAIL,
                        sourceRef = "commitment:schedule",
                        interactionKind = "commitment",
                        sourceEventId = "raw-mail-1",
                        commitmentId = "schedule",
                        role = CommitmentItemType.SCHEDULE,
                        status = "confirmed",
                        title = "데모 미팅",
                        occurredAt = Instant.fromEpochMilliseconds(3_000),
                    ),
                    interaction(
                        id = "decision",
                        personId = personId,
                        sourceType = SourceType.GMAIL,
                        sourceRef = "raw:raw-mail-1",
                        interactionKind = "commitment",
                        role = CommitmentItemType.DECISION,
                        direction = null,
                        status = "approved",
                        title = "가격안 승인",
                        occurredAt = Instant.fromEpochMilliseconds(3_000),
                    ),
                    interaction(
                        id = "decision-only",
                        personId = personId,
                        sourceType = SourceType.GMAIL,
                        sourceRef = "commitment:decision-only",
                        interactionKind = "commitment",
                        role = CommitmentItemType.DECISION,
                        direction = null,
                        status = "chosen",
                        title = "단독 결정",
                        occurredAt = Instant.fromEpochMilliseconds(4_000),
                    ),
                ),
            )

        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals("Alice Kim", state.displayName)
        assertEquals("Al", state.nickname)
        assertEquals("BeCalm", state.companyName)
        assertEquals("PM", state.jobTitle)
        assertEquals(1, state.eventCount)
        assertEquals(1, state.emailInteractionCount)
        assertEquals(2, state.pendingCommitmentCount)
        assertEquals(setOf(SourceType.GMAIL), state.channelSources)
        assertEquals(1, state.sourceEventCards.size)
        val mailCard = state.sourceEventCards.single { it.sourceEventKey == "raw:raw-mail-1" }
        assertEquals("raw-mail-1", mailCard.rawEventId)
        assertEquals(
            listOf("자료 보내기", "데모 미팅"),
            mailCard.myActions.map { it.title } + mailCard.schedules.map { it.title },
        )
        assertTrue(mailCard.theirActions.isEmpty())
        assertFalse(
            (mailCard.myActions + mailCard.theirActions + mailCard.schedules)
                .any { it.title == "가격안 승인" },
        )
    }

    @Test
    fun `calendar history before yesterday remains visible in person detail`() = runTest {
        val personId = "person-1"
        every { personIndexDao.observeInteractionsForPerson("user-1", personId, 150) } returns
            flowOf(
                listOf(
                    interaction(
                        id = "old-calendar",
                        personId = personId,
                        sourceType = SourceType.GOOGLE_CALENDAR,
                        sourceRef = "calendar:old",
                        interactionKind = "calendar",
                        title = "old",
                        occurredAt = Instant.parse("2026-04-21T14:59:00Z"),
                    ),
                    interaction(
                        id = "yesterday-calendar",
                        personId = personId,
                        sourceType = SourceType.GOOGLE_CALENDAR,
                        sourceRef = "calendar:yesterday",
                        interactionKind = "calendar",
                        title = "yesterday",
                        occurredAt = Instant.parse("2026-04-21T15:00:00Z"),
                    ),
                ),
            )

        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        val titles = viewModel.uiState.value.sourceEventCards.map { it.title }
        assertEquals(listOf("yesterday", "old"), titles)
    }

    @Test
    fun `failed manual memory outbox is visible and retry reenqueues worker`() = runTest {
        val personId = "person-1"
        every { manualMemoryOutboxDao.observeForPerson("user-1", personId) } returns MutableStateFlow(
            listOf(manualMemoryOutbox(personId = personId, syncStatus = ManualMemoryOutboxSyncStatus.FAILED)),
        )
        coEvery {
            manualMemoryOutboxDao.markFailedForPersonPending(userId = "user-1", personId = personId, updatedAt = any())
        } returns 1

        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        val status = viewModel.uiState.value.manualMemorySyncStatus
        assertEquals(ManualMemorySyncStatusKind.FAILED, status?.kind)
        assertEquals(1, status?.failedCount)

        viewModel.onRetryManualMemorySync()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            manualMemoryOutboxDao.markFailedForPersonPending(userId = "user-1", personId = personId, updatedAt = any())
        }
        verify(exactly = 1) { workScheduler.enqueueManualMemoryOutboxRetry(initialDelaySeconds = 0L) }
        assertFalse(viewModel.uiState.value.retryingManualMemorySync)
    }

    @Test
    fun `meeting interactions count as meetings and remain visible in person detail`() = runTest {
        val personId = "person-1"
        every { personIndexDao.observeInteractionsForPerson("user-1", personId, 150) } returns
            flowOf(
                listOf(
                    interaction(
                        id = "meeting-audio",
                        personId = personId,
                        sourceType = SourceType.MEETING,
                        sourceRef = "raw:raw-meeting-audio-1",
                        interactionKind = "meeting",
                        title = "고객 미팅 녹음",
                        snippet = "다음 주 제안서를 다시 보내기로 했습니다.",
                        occurredAt = Instant.fromEpochMilliseconds(3_000),
                    ),
                ),
            )

        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.meetingCount)
        assertEquals(SourceType.MEETING, state.sourceEventCards.single().sourceType)
    }

    @Test
    fun `source artifact filename is not used as person detail primary event title`() = runTest {
        val personId = "person-1"
        every { personIndexDao.observeInteractionsForPerson("user-1", personId, 150) } returns
            flowOf(
                listOf(
                    interaction(
                        id = "transcript",
                        personId = personId,
                        sourceType = SourceType.MEETING,
                        sourceRef = "raw:raw-meeting-1",
                        interactionKind = "call",
                        title = "becalm-live-e2e-transcript-3.txt",
                        snippet = "다음 주 수요일 정오까지 갱신 견적서를 보내주세요.",
                        occurredAt = Instant.fromEpochMilliseconds(3_000),
                    ),
                ),
            )

        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        val row = viewModel.uiState.value.sourceEventCards.single()
        assertEquals("다음 주 수요일 정오까지 갱신 견적서를 보내주세요.", row.title)
        assertNull(row.snippet)
    }

    @Test
    fun `large person timeline projection stays bounded to latest indexed interactions`() = runTest {
        val personId = "person-1"
        val interactions = (0 until 150).map { index ->
            interaction(
                id = "mail-$index",
                personId = personId,
                sourceType = SourceType.GMAIL,
                sourceRef = "raw:mail-$index",
                interactionKind = "email",
                title = "고객 메일 $index",
                snippet = "다음 액션과 일정이 포함된 최근 상호작용 $index",
                occurredAt = Instant.fromEpochMilliseconds(index * 1_000L),
            )
        }
        every { personIndexDao.observeInteractionsForPerson("user-1", personId, 150) } returns flowOf(interactions)

        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals(150, state.eventCount)
        assertEquals(150, state.sourceEventCards.size)
        assertEquals("고객 메일 149", state.sourceEventCards.first().title)
        assertEquals("고객 메일 0", state.sourceEventCards.last().title)
        assertTrue(state.canLoadMoreTimeline)
    }

    @Test
    fun `load more timeline increases person interaction query limit`() = runTest {
        val personId = "person-1"
        val firstPage = (0 until 150).map { index ->
            interaction(
                id = "mail-$index",
                personId = personId,
                sourceType = SourceType.GMAIL,
                sourceRef = "raw:mail-$index",
                interactionKind = "email",
                title = "고객 메일 $index",
                snippet = "다음 액션과 일정이 포함된 최근 상호작용 $index",
                occurredAt = Instant.fromEpochMilliseconds(index * 1_000L),
            )
        }
        val expandedPage = (0 until 220).map { index ->
            interaction(
                id = "mail-$index",
                personId = personId,
                sourceType = SourceType.GMAIL,
                sourceRef = "raw:mail-$index",
                interactionKind = "email",
                title = "고객 메일 $index",
                snippet = "다음 액션과 일정이 포함된 최근 상호작용 $index",
                occurredAt = Instant.fromEpochMilliseconds(index * 1_000L),
            )
        }
        every { personIndexDao.observeInteractionsForPerson("user-1", personId, 150) } returns flowOf(firstPage)
        every { personIndexDao.observeInteractionsForPerson("user-1", personId, 300) } returns flowOf(expandedPage)

        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        assertEquals(150, viewModel.uiState.value.sourceEventCards.size)
        assertTrue(viewModel.uiState.value.canLoadMoreTimeline)

        viewModel.onLoadMoreTimeline()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(220, state.eventCount)
        assertEquals(220, state.sourceEventCards.size)
        assertEquals("고객 메일 219", state.sourceEventCards.first().title)
        assertEquals("고객 메일 0", state.sourceEventCards.last().title)
        assertFalse(state.canLoadMoreTimeline)
    }

    @Test
    fun `empty person index does not fall back to legacy person ref reads`() = runTest {
        val personId = "person-1"

        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals("아직 이름을 모르는 연락처", state.displayName)
        assertTrue(state.sourceEventCards.isEmpty())
    }

    @Test
    fun `inner observe failure surfaces error and dismiss action clears it`() = runTest {
        val personId = "person-1"
        every { personEnrichmentRepository.observeAll() } returns flow {
            throw IllegalStateException("observe failed")
        }

        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        assertEquals(R.string.person_detail_error_load_failed, viewModel.uiState.value.error?.resId)
        viewModel.onErrorDismissed()
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `retry after observe failure restarts detail projection`() = runTest {
        val personId = "person-1"
        var observeAttempts = 0
        every { personIndexDao.observeIdentitiesForPerson("user-1", personId) } returns flowOf(
            listOf(
                identity(
                    personId = personId,
                    rawValue = "alice@example.com",
                    displayNameHint = "Alice",
                ),
            ),
        )
        every { personEnrichmentRepository.observeAll() } answers {
            observeAttempts += 1
            if (observeAttempts == 1) {
                flow { throw IllegalStateException("observe failed") }
            } else {
                flowOf(emptyList())
            }
        }
        every { personIndexDao.observeInteractionsForPerson("user-1", personId, 150) } returns
            flowOf(
                listOf(
                    interaction(
                        id = "mail",
                        personId = personId,
                        sourceType = SourceType.GMAIL,
                        sourceRef = "raw:raw-mail-1",
                        interactionKind = "email",
                        title = "메일",
                        occurredAt = Instant.fromEpochMilliseconds(3_000),
                    ),
                ),
            )

        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        assertEquals(R.string.person_detail_error_load_failed, viewModel.uiState.value.error?.resId)

        viewModel.onRetryLoad()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.error)
        assertFalse(state.loading)
        assertEquals("Alice", state.displayName)
        assertEquals(1, state.sourceEventCards.size)
    }

    @Test
    fun `person action completion patches backend action and refreshes person feed`() = runTest {
        val personId = "person-1"
        coEvery {
            personActionRepository.completeAction(
                userId = "user-1",
                actionItemId = "pa-1",
                expectedUpdatedAt = null,
            )
        } returns BecalmResult.Success(PersonActionMutationSyncStats(queued = 1, synced = 1, retryable = 0, failed = 0))
        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        viewModel.onCompletePersonAction("pa-1")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            personActionRepository.completeAction(
                userId = "user-1",
                actionItemId = "pa-1",
                expectedUpdatedAt = null,
            )
        }
        coVerify(exactly = 2) {
            personActionRepository.refresh(userId = "user-1", surface = "person")
        }
        assertNull(viewModel.uiState.value.loadingCompleteActionId)
        assertEquals(R.string.person_action_complete_success, viewModel.uiState.value.error?.resId)
    }

    @Test
    fun `person action dismissal patches backend action and refreshes person feed`() = runTest {
        val personId = "person-1"
        coEvery {
            personActionRepository.dismissAction(
                userId = "user-1",
                actionItemId = "pa-1",
                reason = "not_actionable",
                expectedUpdatedAt = null,
            )
        } returns BecalmResult.Success(PersonActionMutationSyncStats(queued = 1, synced = 1, retryable = 0, failed = 0))
        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        viewModel.onDismissPersonAction("pa-1")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            personActionRepository.dismissAction(
                userId = "user-1",
                actionItemId = "pa-1",
                reason = "not_actionable",
                expectedUpdatedAt = null,
            )
        }
        coVerify(exactly = 2) {
            personActionRepository.refresh(userId = "user-1", surface = "person")
        }
        assertNull(viewModel.uiState.value.loadingDismissActionId)
        assertEquals(R.string.person_action_dismiss_success, viewModel.uiState.value.error?.resId)
    }

    @Test
    fun `person action reminder snoozes backend action and schedules local commitment alarm`() = runTest {
        val personId = "person-1"
        val dueAt = Instant.parse("2030-01-02T03:00:00Z")
        val expectedSnoozeUntil = Instant.parse("2030-01-01T03:00:00Z")
        every {
            personActionRepository.observeActiveForPerson("user-1", personId, any())
        } returns flowOf(
            listOf(
                personActionEntity(
                    id = "pa-remind-1",
                    personId = personId,
                    commitmentId = "commitment-remind-1",
                    dueAt = dueAt,
                    reasonCodesCsv = "source:gmail,direction:take,waiting_on",
                ),
            ),
        )
        coEvery {
            personActionRepository.snoozeAction(
                userId = "user-1",
                actionItemId = "pa-remind-1",
                snoozedUntil = expectedSnoozeUntil,
                reason = "user_reminder",
                expectedUpdatedAt = null,
            )
        } returns BecalmResult.Success(PersonActionMutationSyncStats(queued = 1, synced = 1, retryable = 0, failed = 0))
        coEvery { reminderScheduler.schedule(any(), any()) } returns Unit

        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        viewModel.onRemindPersonAction("pa-remind-1")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            personActionRepository.snoozeAction(
                userId = "user-1",
                actionItemId = "pa-remind-1",
                snoozedUntil = expectedSnoozeUntil,
                reason = "user_reminder",
                expectedUpdatedAt = null,
            )
        }
        coVerify(exactly = 1) { userPrefsStore.setCommitmentReminderDisabled("commitment-remind-1", false) }
        coVerify(exactly = 1) { reminderScheduler.schedule("commitment-remind-1", dueAt) }
        coVerify(exactly = 2) {
            personActionRepository.refresh(userId = "user-1", surface = "person")
        }
        assertNull(viewModel.uiState.value.loadingReminderActionId)
        assertEquals(R.string.person_action_reminder_scheduled, viewModel.uiState.value.error?.resId)
    }

    @Test
    fun `person action draft opens editable sheet from backend draft`() = runTest {
        val personId = "person-1"
        every {
            personActionRepository.observeActiveForPerson("user-1", personId, any())
        } returns flowOf(
            listOf(
                personActionEntity(
                    id = "pa-draft-1",
                    personId = personId,
                    commitmentId = "commitment-1",
                    dueAt = null,
                    reasonCodesCsv = "source:gmail,waiting_on",
                    actionKind = "follow_up",
                    primaryEvidenceKind = "source_event",
                    primaryEvidenceId = "source-event-1",
                    primaryEvidenceLabel = "Gmail thread",
                ),
            ),
        )
        coEvery {
            personActionRepository.generateDraft(
                userId = "user-1",
                actionItemId = "pa-draft-1",
                draftKind = "follow_up",
                channel = "email",
                evidenceRefs = listOf(PersonActionDraftEvidenceRef(kind = "source_event", evidenceId = "source-event-1")),
                userInstruction = null,
            )
        } returns BecalmResult.Success(draftDto(actionItemId = "pa-draft-1", draftKind = "follow_up"))

        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        viewModel.onOpenPersonActionDraft("pa-draft-1")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            personActionRepository.generateDraft(
                userId = "user-1",
                actionItemId = "pa-draft-1",
                draftKind = "follow_up",
                channel = "email",
                evidenceRefs = listOf(PersonActionDraftEvidenceRef(kind = "source_event", evidenceId = "source-event-1")),
                userInstruction = null,
            )
        }
        val draftSheet = requireNotNull(viewModel.uiState.value.draftSheet)
        assertEquals(PersonActionDraftSheetStatus.READY, draftSheet.status)
        assertEquals("Jane Kim proposal reply", draftSheet.subject)
        assertEquals("Jane님, 제안서 확인했습니다.", draftSheet.body)
        assertEquals(listOf("Gmail thread"), draftSheet.provenanceLabels)
        assertTrue(draftSheet.requiresUserReview)
        assertNull(viewModel.uiState.value.loadingDraftActionId)

        viewModel.onDraftSubjectChange("수정 제목")
        viewModel.onDraftBodyChange("수정 본문")
        assertEquals("수정 제목", viewModel.uiState.value.draftSheet?.subject)
        assertEquals("수정 본문", viewModel.uiState.value.draftSheet?.body)

        viewModel.onDismissPersonActionDraft()
        assertNull(viewModel.uiState.value.draftSheet)
    }

    @Test
    fun `person action draft network failure shows retryable sheet and retries`() = runTest {
        val personId = "person-1"
        every {
            personActionRepository.observeActiveForPerson("user-1", personId, any())
        } returns flowOf(
            listOf(
                personActionEntity(
                    id = "pa-draft-1",
                    personId = personId,
                    commitmentId = "commitment-1",
                    dueAt = null,
                    reasonCodesCsv = "source:gmail,waiting_on",
                    actionKind = "reply",
                    primaryEvidenceKind = "source_event",
                    primaryEvidenceId = "source-event-1",
                ),
            ),
        )
        coEvery {
            personActionRepository.generateDraft(
                userId = "user-1",
                actionItemId = "pa-draft-1",
                draftKind = "reply",
                channel = "email",
                evidenceRefs = listOf(PersonActionDraftEvidenceRef(kind = "source_event", evidenceId = "source-event-1")),
                userInstruction = null,
            )
        } returns BecalmResult.Failure(BecalmError.Network(0, "timeout"))

        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        viewModel.onOpenPersonActionDraft("pa-draft-1")
        advanceUntilIdle()

        var draftSheet = requireNotNull(viewModel.uiState.value.draftSheet)
        assertEquals(PersonActionDraftSheetStatus.ERROR, draftSheet.status)
        assertEquals(R.string.person_action_draft_failed, draftSheet.error?.resId)
        assertTrue(draftSheet.canRetry)

        viewModel.onRetryPersonActionDraft()
        advanceUntilIdle()

        draftSheet = requireNotNull(viewModel.uiState.value.draftSheet)
        assertEquals(PersonActionDraftSheetStatus.ERROR, draftSheet.status)
        coVerify(exactly = 2) {
            personActionRepository.generateDraft(
                userId = "user-1",
                actionItemId = "pa-draft-1",
                draftKind = "reply",
                channel = "email",
                evidenceRefs = listOf(PersonActionDraftEvidenceRef(kind = "source_event", evidenceId = "source-event-1")),
                userInstruction = null,
            )
        }
    }

    @Test
    fun `P1-GAP-002 person action draft timeout preserves existing local edits`() = runTest {
        val personId = "person-1"
        every {
            personActionRepository.observeActiveForPerson("user-1", personId, any())
        } returns flowOf(
            listOf(
                personActionEntity(
                    id = "pa-draft-1",
                    personId = personId,
                    commitmentId = "commitment-1",
                    dueAt = null,
                    reasonCodesCsv = "source:gmail,waiting_on",
                    actionKind = "reply",
                    primaryEvidenceKind = "source_event",
                    primaryEvidenceId = "source-event-1",
                ),
            ),
        )
        coEvery {
            personActionRepository.generateDraft(
                userId = "user-1",
                actionItemId = "pa-draft-1",
                draftKind = "reply",
                channel = "email",
                evidenceRefs = listOf(PersonActionDraftEvidenceRef(kind = "source_event", evidenceId = "source-event-1")),
                userInstruction = null,
            )
        } returnsMany listOf(
            BecalmResult.Success(draftDto(actionItemId = "pa-draft-1", draftKind = "reply")),
            BecalmResult.Failure(BecalmError.Network(0, "timeout")),
        )

        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        viewModel.onOpenPersonActionDraft("pa-draft-1")
        advanceUntilIdle()
        viewModel.onDraftSubjectChange("사용자가 고친 제목")
        viewModel.onDraftBodyChange("사용자가 고친 본문")

        viewModel.onOpenPersonActionDraft("pa-draft-1")
        advanceUntilIdle()

        val draftSheet = requireNotNull(viewModel.uiState.value.draftSheet)
        assertEquals(PersonActionDraftSheetStatus.READY, draftSheet.status)
        assertEquals("사용자가 고친 제목", draftSheet.subject)
        assertEquals("사용자가 고친 본문", draftSheet.body)
        assertEquals(R.string.person_action_draft_failed, draftSheet.error?.resId)
        assertTrue(draftSheet.canRetry)
        assertNull(viewModel.uiState.value.loadingDraftActionId)
        coVerify(exactly = 2) {
            personActionRepository.generateDraft(
                userId = "user-1",
                actionItemId = "pa-draft-1",
                draftKind = "reply",
                channel = "email",
                evidenceRefs = listOf(PersonActionDraftEvidenceRef(kind = "source_event", evidenceId = "source-event-1")),
                userInstruction = null,
            )
        }
    }

    @Test
    fun `P1-GAP-002 person action draft unauthorized opens auth required sheet without retry`() = runTest {
        val personId = "person-1"
        every {
            personActionRepository.observeActiveForPerson("user-1", personId, any())
        } returns flowOf(
            listOf(
                personActionEntity(
                    id = "pa-draft-auth",
                    personId = personId,
                    commitmentId = "commitment-1",
                    dueAt = null,
                    reasonCodesCsv = "source:gmail,waiting_on",
                    actionKind = "reply",
                    primaryEvidenceKind = "source_event",
                    primaryEvidenceId = "source-event-1",
                ),
            ),
        )
        coEvery {
            personActionRepository.generateDraft(
                userId = "user-1",
                actionItemId = "pa-draft-auth",
                draftKind = "reply",
                channel = "email",
                evidenceRefs = listOf(PersonActionDraftEvidenceRef(kind = "source_event", evidenceId = "source-event-1")),
                userInstruction = null,
            )
        } returns BecalmResult.Failure(BecalmError.Unauthorized)

        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        viewModel.onOpenPersonActionDraft("pa-draft-auth")
        advanceUntilIdle()

        val draftSheet = requireNotNull(viewModel.uiState.value.draftSheet)
        assertEquals(PersonActionDraftSheetStatus.AUTH_REQUIRED, draftSheet.status)
        assertEquals(R.string.person_action_draft_auth_failed, draftSheet.error?.resId)
        assertFalse(draftSheet.canRetry)
        assertNull(viewModel.uiState.value.loadingDraftActionId)
        coVerify(exactly = 1) {
            personActionRepository.generateDraft(
                userId = "user-1",
                actionItemId = "pa-draft-auth",
                draftKind = "reply",
                channel = "email",
                evidenceRefs = listOf(PersonActionDraftEvidenceRef(kind = "source_event", evidenceId = "source-event-1")),
                userInstruction = null,
            )
        }
    }

    @Test
    fun `unsupported person action draft opens unsupported sheet without backend call`() = runTest {
        val personId = "person-1"
        every {
            personActionRepository.observeActiveForPerson("user-1", personId, any())
        } returns flowOf(
            listOf(
                personActionEntity(
                    id = "pa-review-1",
                    personId = personId,
                    commitmentId = null,
                    dueAt = null,
                    reasonCodesCsv = "review_match",
                    actionKind = "review_match",
                ),
            ),
        )

        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        viewModel.onOpenPersonActionDraft("pa-review-1")
        advanceUntilIdle()

        val draftSheet = requireNotNull(viewModel.uiState.value.draftSheet)
        assertEquals(PersonActionDraftSheetStatus.UNSUPPORTED, draftSheet.status)
        assertEquals(R.string.person_action_draft_unsupported, draftSheet.error?.resId)
        coVerify(exactly = 0) {
            personActionRepository.generateDraft(
                userId = any(),
                actionItemId = any(),
                draftKind = any(),
                channel = any(),
                evidenceRefs = any(),
                userInstruction = any(),
            )
        }
    }

    private fun buildViewModel(personId: String): PersonDetailViewModel =
        PersonDetailViewModel(
            personEnrichmentRepository = personEnrichmentRepository,
            personIndexDao = personIndexDao,
            rawIngestionEventDao = rawIngestionEventDao,
            personActionRepository = personActionRepository,
            manualMemoryOutboxDao = manualMemoryOutboxDao,
            workScheduler = workScheduler,
            reminderScheduler = reminderScheduler,
            userPrefsStore = userPrefsStore,
            savedStateHandle = SavedStateHandle(mapOf(ARG_PERSON_ID to personId)),
            logger = logger,
        )

    private fun identity(
        personId: String,
        rawValue: String,
        displayNameHint: String?,
    ): PersonIdentityEntity =
        PersonIdentityEntity(
            id = "identity-$rawValue",
            userId = "user-1",
            personId = personId,
            identityKey = "email:$rawValue",
            identityType = "email",
            rawValue = rawValue,
            displayNameHint = displayNameHint,
            sourceType = SourceType.GMAIL,
            confidence = 1.0,
            verified = true,
            lastSeenAt = Instant.fromEpochMilliseconds(0),
        )

    private fun interaction(
        id: String,
        personId: String,
        sourceType: String,
        sourceRef: String,
        interactionKind: String,
        sourceEventId: String? = sourceRef.removePrefix("raw:").takeIf { sourceRef.startsWith("raw:") },
        commitmentId: String? = sourceRef.removePrefix("commitment:").takeIf { sourceRef.startsWith("commitment:") },
        role: String = "counterparty",
        direction: String? = null,
        status: String? = null,
        title: String,
        snippet: String? = null,
        occurredAt: Instant,
    ): PersonInteractionEntity =
        PersonInteractionEntity(
            id = id,
            userId = "user-1",
            personId = personId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            interactionKind = interactionKind,
            sourceEventId = sourceEventId,
            commitmentId = commitmentId,
            role = role,
            direction = direction,
            status = status,
            occurredAt = occurredAt,
            title = title,
            snippet = snippet,
            confidence = 1.0,
        )

    private fun personActionEntity(
        id: String,
        personId: String,
        commitmentId: String?,
        dueAt: Instant?,
        reasonCodesCsv: String,
        actionKind: String = "follow_up",
        primaryEvidenceKind: String? = null,
        primaryEvidenceId: String? = null,
        primaryEvidenceLabel: String? = null,
    ): PersonActionItemCacheEntity =
        PersonActionItemCacheEntity(
            id = id,
            userId = "user-1",
            personId = personId,
            personDisplayName = "Alice",
            personSortKey = "alice",
            surfacesCsv = "person,commitment",
            actionKind = actionKind,
            status = "active",
            title = "의견서 회신 받기",
            primaryVerb = "리마인드",
            shortReason = "기한: 2030-01-02",
            commitmentId = commitmentId,
            calendarEventId = null,
            sourceEventId = "source-event-1",
            sourceType = SourceType.GMAIL,
            sourceRef = "gmail-message-1",
            dueAt = dueAt,
            dueHint = "2030-01-02",
            dueIsApproximate = false,
            staleAfter = null,
            urgencyScore = 92.0,
            importanceScore = 70.0,
            confidence = 0.9,
            reasonCodesCsv = reasonCodesCsv,
            inputWatermark = Instant.parse("2026-06-03T02:00:00Z"),
            serverWatermark = Instant.parse("2026-06-03T03:00:00Z"),
            computedAt = Instant.parse("2026-06-03T02:00:01Z"),
            updatedAt = Instant.parse("2026-06-03T02:00:02Z"),
            snoozedUntil = null,
            completedAt = null,
            dismissedAt = null,
            primaryEvidenceKind = primaryEvidenceKind,
            primaryEvidenceId = primaryEvidenceId,
            primaryEvidenceLabel = primaryEvidenceLabel,
        )

    private fun draftDto(
        actionItemId: String,
        draftKind: String,
    ): PersonActionDraftDto =
        PersonActionDraftDto(
            draftId = "draft-1",
            actionItemId = actionItemId,
            draftKind = draftKind,
            channel = "email",
            status = "ready",
            subject = "Jane Kim proposal reply",
            body = "Jane님, 제안서 확인했습니다.",
            provenance = listOf(
                PersonActionDraftProvenanceDto(
                    kind = "source_event",
                    evidenceId = "source-event-1",
                    label = "Gmail thread",
                ),
            ),
            safety = PersonActionDraftSafetyDto(
                containsSourceQuote = false,
                requiresUserReview = true,
            ),
            generatedAt = Instant.parse("2026-06-03T04:05:00Z"),
        )

    private fun manualMemoryOutbox(
        personId: String,
        syncStatus: String,
    ): ManualMemoryOutboxEntity =
        ManualMemoryOutboxEntity(
            userId = "user-1",
            clientMemoryId = "client-1",
            personId = personId,
            commitmentId = "commitment-1",
            sourceRef = "manual_memory:client-1",
            personDisplayName = "Alice",
            originChannel = "email",
            memoryKind = "my_action",
            title = "자료 보내기",
            occurredAt = Instant.fromEpochMilliseconds(3_000),
            dueAt = null,
            dueHint = null,
            payloadHash = "hash",
            syncStatus = syncStatus,
            createdAt = Instant.fromEpochMilliseconds(3_000),
            updatedAt = Instant.fromEpochMilliseconds(3_000),
        )
}

package com.becalm.android.unit.ui.persons

import androidx.lifecycle.SavedStateHandle
import com.becalm.android.R
import com.becalm.android.core.util.FakeClock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.ui.persons.ARG_PERSON_ID
import com.becalm.android.ui.persons.PersonDetailViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val userPrefsStore: UserPrefsStore = mockk()
    private val logger: Logger = mockk(relaxed = true)
    private val clock = FakeClock(Instant.parse("2026-04-23T03:00:00Z"))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { personEnrichmentRepository.observeAll() } returns flowOf(emptyList())
        every { personIndexDao.observeIdentitiesForPerson(any(), any()) } returns flowOf(emptyList())
        every { personIndexDao.observeInteractionsForPerson(any(), any(), any()) } returns flowOf(emptyList())
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
                        sourceRef = "raw:raw-mail-1",
                        interactionKind = "commitment",
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
                        sourceRef = "raw:raw-mail-1",
                        interactionKind = "commitment",
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
    fun `calendar history before yesterday is hidden from indexed rows`() = runTest {
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
        assertEquals(listOf("yesterday"), titles)
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
    fun `empty person index does not fall back to legacy person ref reads`() = runTest {
        val personId = "person-1"

        val viewModel = buildViewModel(personId = personId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals(personId, state.displayName)
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

    private fun buildViewModel(personId: String): PersonDetailViewModel =
        PersonDetailViewModel(
            personEnrichmentRepository = personEnrichmentRepository,
            personIndexDao = personIndexDao,
            userPrefsStore = userPrefsStore,
            savedStateHandle = SavedStateHandle(mapOf(ARG_PERSON_ID to personId)),
            logger = logger,
            clock = clock,
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
            role = role,
            direction = direction,
            status = status,
            occurredAt = occurredAt,
            title = title,
            snippet = snippet,
            confidence = 1.0,
        )
}

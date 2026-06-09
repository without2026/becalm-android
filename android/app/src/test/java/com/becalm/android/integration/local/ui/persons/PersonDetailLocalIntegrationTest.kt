package com.becalm.android.integration.local.ui.persons

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceEventAnchorEntity
import com.becalm.android.data.local.db.entity.SourceEventAnchorOrigin
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.CalendarEventRepositoryImpl
import com.becalm.android.data.repository.CommitmentRepositoryImpl
import com.becalm.android.data.repository.EmailBodyRepositoryImpl
import com.becalm.android.data.repository.PersonEnrichmentRepositoryImpl
import com.becalm.android.data.repository.RawIngestionRepositoryImpl
import com.becalm.android.data.repository.SourceArchiveStore
import com.becalm.android.data.repository.SourceArtifactRepositoryImpl
import com.becalm.android.data.repository.SourceOriginalResolver
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.ui.persons.ARG_EVENT_ID
import com.becalm.android.ui.persons.ARG_PERSON_ID
import com.becalm.android.ui.persons.PersonDetailViewModel
import com.becalm.android.ui.persons.RawEventDetailViewModel
import com.becalm.android.ui.persons.RoomBackedRawEventDetailProjectionPort
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PersonDetailLocalIntegrationTest {

    private val dispatcher = StandardTestDispatcher()
    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val logger = RecordingLogger()
    private val api = mockk<RailwayApi>(relaxed = true)
    private val cursorStore = mockk<com.becalm.android.data.local.datastore.SyncCursorStore>(relaxed = true)
    private val userPrefsStore = UserPrefsStoreImpl(
        dataStore = LocalIntegrationSupport.prefsDataStore("person-detail-user-prefs"),
    )
    private val rawIngestionRepository = RawIngestionRepositoryImpl(
        dao = db.rawIngestionEventDao(),
        api = api,
        logger = logger,
    )
    private val commitmentRepository = CommitmentRepositoryImpl(
        dao = db.commitmentDao(),
        api = api,
        cursorStore = cursorStore,
        userPrefsStore = userPrefsStore,
        database = db,
        logger = logger,
        ioDispatcher = UnconfinedTestDispatcher(),
    )
    private val calendarRepository = CalendarEventRepositoryImpl(
        dao = db.calendarEventDao(),
        api = api,
        cursorStore = cursorStore,
        logger = logger,
    )
    private val enrichmentRepository = PersonEnrichmentRepositoryImpl(
        dao = db.personEnrichmentDao(),
        logger = logger,
    )
    private val emailBodyRepository = EmailBodyRepositoryImpl(
        dao = db.emailBodyDao(),
        ioDispatcher = UnconfinedTestDispatcher(),
    )
    private val sourceArtifactRepository = SourceArtifactRepositoryImpl(
        dao = db.sourceArtifactDao(),
        store = SourceArchiveStore(LocalIntegrationSupport.appContext()),
        ioDispatcher = UnconfinedTestDispatcher(),
    )
    private val sourceOriginalResolver = SourceOriginalResolver(
        emailBodyRepository = emailBodyRepository,
        sourceArtifactRepository = sourceArtifactRepository,
    )

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(dispatcher)
        userPrefsStore.setCurrentUserId(USER_ID)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `SRC-002 SRC-004 and SRC-008 person detail projects room source timeline with enrichment`() = runTest {
        enrichmentRepository.upsert(
            PersonEnrichmentEntity(
                personRef = PERSON_REF,
                displayName = "김영희",
                nickname = "영희",
                company = "ABC",
                title = "리드",
                lastSyncedAt = Instant.parse("2026-04-23T00:00:00Z"),
            ),
        )
        db.rawIngestionEventDao().insertAll(
            listOf(
                rawEvent(
                    id = "event-1",
                    sourceType = SourceType.GMAIL,
                    timestamp = Instant.parse("2026-04-23T03:00:00Z"),
                    title = "메일 제목",
                    snippet = "메일 미리보기",
                ),
                rawEvent(
                    id = "event-2",
                    sourceType = SourceType.VOICE,
                    timestamp = Instant.parse("2026-04-23T02:00:00Z"),
                    title = "음성 메모",
                    snippet = "음성 메모 미리보기",
                ),
            ),
        )
        db.commitmentDao().insertAll(
            listOf(
                commitment(
                    id = "c-pending",
                    title = "미완료 약속",
                    actionState = "pending",
                    sourceType = SourceType.GMAIL,
                    sourceEventOccurredAt = Instant.parse("2026-04-23T03:00:00Z"),
                ),
                commitment(
                    id = "c-completed",
                    title = "완료된 약속",
                    actionState = "completed",
                    sourceType = SourceType.VOICE,
                    sourceEventOccurredAt = Instant.parse("2026-04-23T01:00:00Z"),
                ),
            ),
        )
        db.calendarEventDao().insertAll(
            listOf(
                CalendarEventEntity(
                    id = "meeting-1",
                    userId = USER_ID,
                    sourceType = SourceType.GOOGLE_CALENDAR,
                    sourceRef = "meeting-1",
                    title = "캘린더 미팅",
                    startAt = Instant.parse("2026-04-23T02:30:00Z"),
                    endAt = Instant.parse("2026-04-23T03:00:00Z"),
                    attendeesRaw = PERSON_REF,
                    syncStatus = "synced",
                ),
            ),
        )
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, PERSON_REF)).personId
        db.personIndexDao().upsertIdentities(
            listOf(
                PersonIdentityEntity(
                    id = PersonIdentityResolver.stableIdentityId(USER_ID, "email:$PERSON_REF"),
                    userId = USER_ID,
                    personId = personId,
                    identityKey = "email:$PERSON_REF",
                    identityType = "email",
                    rawValue = PERSON_REF,
                    displayNameHint = "김영희",
                    sourceType = SourceType.GMAIL,
                    confidence = 1.0,
                    verified = true,
                    lastSeenAt = Instant.parse("2026-04-23T03:00:00Z"),
                ),
            ),
        )
        db.personIndexDao().upsertInteractions(
            listOf(
                indexedInteraction(personId, SourceType.GMAIL, "raw:event-1", "email", "counterparty", null, null, Instant.parse("2026-04-23T03:00:00Z"), "메일 제목", "메일 미리보기"),
                indexedInteraction(personId, SourceType.GOOGLE_CALENDAR, "calendar:meeting-1", "calendar", "attendee", null, null, Instant.parse("2026-04-23T02:30:00Z"), "캘린더 미팅", PERSON_REF),
                indexedInteraction(personId, SourceType.VOICE, "raw:event-2", "call", "counterparty", null, null, Instant.parse("2026-04-23T02:00:00Z"), "음성 메모", "음성 메모 미리보기"),
                indexedInteraction(personId, SourceType.GMAIL, "raw:event-1", "commitment", CommitmentItemType.ACTION, "give", "pending", Instant.parse("2026-04-23T03:00:00Z"), "미완료 약속", "quote"),
                indexedInteraction(personId, SourceType.VOICE, "raw:event-2", "commitment", CommitmentItemType.ACTION, "give", "completed", Instant.parse("2026-04-23T01:00:00Z"), "완료된 약속", "quote"),
            ),
        )

        val viewModel = PersonDetailViewModel(
            personEnrichmentRepository = enrichmentRepository,
            personIndexDao = db.personIndexDao(),
            rawIngestionEventDao = db.rawIngestionEventDao(),
            commitmentDao = db.commitmentDao(),
            calendarEventDao = db.calendarEventDao(),
            manualMemoryOutboxDao = db.manualMemoryOutboxDao(),
            workScheduler = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            userPrefsStore = userPrefsStore,
            savedStateHandle = SavedStateHandle(mapOf(ARG_PERSON_ID to personId)),
            logger = logger,
        )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.loading || state.eventCount < 2 || state.pendingCommitmentCount != 1) {
                state = awaitItem()
            }

            assertEquals("김영희", state.displayName)
            assertEquals("영희", state.nickname)
            assertEquals("ABC", state.companyName)
            assertEquals("리드", state.jobTitle)
            assertEquals(3, state.eventCount)
            assertEquals(1, state.pendingCommitmentCount)
            assertEquals(setOf(SourceType.GMAIL, SourceType.VOICE, SourceType.GOOGLE_CALENDAR), state.channelSources)
            assertEquals(
                listOf("메일 제목", "캘린더 미팅", "음성 메모"),
                state.sourceEventCards.map { it.title },
            )
            assertEquals(
                listOf("미완료 약속"),
                state.sourceEventCards.single { it.sourceEventKey == "raw:event-1" }.myActions.map { it.title },
            )
            assertEquals(
                listOf("완료된 약속"),
                state.sourceEventCards.single { it.sourceEventKey == "raw:event-2" }.myActions.map { it.title },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `person detail groups email source events by conversation ref`() = runTest {
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, PERSON_REF)).personId
        val firstAt = Instant.parse("2026-04-23T03:00:00Z")
        val secondAt = Instant.parse("2026-04-23T04:00:00Z")
        db.rawIngestionEventDao().insertAll(
            listOf(
                rawEvent(
                    id = "mail-thread-1",
                    sourceType = SourceType.GMAIL,
                    timestamp = firstAt,
                    title = "첫 번째 메일",
                    snippet = "첫 번째 미리보기",
                ).copy(conversationRef = "gmail-thread-1"),
                rawEvent(
                    id = "mail-thread-2",
                    sourceType = SourceType.GMAIL,
                    timestamp = secondAt,
                    title = "두 번째 메일",
                    snippet = "두 번째 미리보기",
                ).copy(conversationRef = "gmail-thread-1"),
            ),
        )
        db.personIndexDao().upsertInteractions(
            listOf(
                indexedInteraction(personId, SourceType.GMAIL, "raw:mail-thread-1", "email", "counterparty", null, null, firstAt, "첫 번째 메일", "첫 번째 미리보기"),
                indexedInteraction(personId, SourceType.GMAIL, "raw:mail-thread-2", "email", "counterparty", null, null, secondAt, "두 번째 메일", "두 번째 미리보기"),
                indexedInteraction(personId, SourceType.GMAIL, "raw:mail-thread-1", "commitment", CommitmentItemType.ACTION, "give", "pending", firstAt, "첫 번째 후속 조치", "quote"),
                indexedInteraction(personId, SourceType.GMAIL, "raw:mail-thread-2", "commitment", CommitmentItemType.ACTION, "give", "pending", secondAt, "두 번째 후속 조치", "quote"),
            ),
        )

        val viewModel = PersonDetailViewModel(
            personEnrichmentRepository = enrichmentRepository,
            personIndexDao = db.personIndexDao(),
            rawIngestionEventDao = db.rawIngestionEventDao(),
            commitmentDao = db.commitmentDao(),
            calendarEventDao = db.calendarEventDao(),
            manualMemoryOutboxDao = db.manualMemoryOutboxDao(),
            workScheduler = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            userPrefsStore = userPrefsStore,
            savedStateHandle = SavedStateHandle(mapOf(ARG_PERSON_ID to personId)),
            logger = logger,
        )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.loading || state.sourceEventCards.singleOrNull()?.myActions?.size != 2) {
                state = awaitItem()
            }

            val card = state.sourceEventCards.single()
            assertEquals("thread:gmail:gmail-thread-1", card.sourceEventKey)
            assertEquals("mail-thread-2", card.rawEventId)
            assertEquals("두 번째 메일", card.title)
            assertTrue(card.isEmailThread)
            assertEquals(2, card.threadMessageCount)
            assertEquals(setOf("raw:mail-thread-1", "raw:mail-thread-2"), card.relatedSourceEventKeys.toSet())
            assertEquals(setOf("첫 번째 후속 조치", "두 번째 후속 조치"), card.myActions.map { it.title }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SRC-004 raw event detail joins email body from room-only store`() = runTest {
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "email-event",
                sourceType = SourceType.GMAIL,
                timestamp = Instant.parse("2026-04-23T04:00:00Z"),
                title = "메일 제목",
                snippet = "본문 미리보기",
            ),
        )
        emailBodyRepository.insert(
            EmailBodyEntity(
                id = "body-1",
                rawEventId = "email-event",
                providerMessageId = "provider-1",
                folder = "INBOX",
                subject = "메일 제목",
                fromAddress = "sender@example.com",
                toAddresses = "[{\"email\":\"user@example.com\"}]",
                bodyPlain = "긴 이메일 본문",
                bodyHtml = "<p>긴 이메일 본문</p>",
                attachmentsMeta = """[{"filename":"spec.pdf","mime":"application/pdf","size_bytes":1234}]""",
                rawHeaders = "Message-Id: 1",
                parseFailed = false,
                groupEmail = false,
                receivedAt = Instant.parse("2026-04-23T04:00:00Z"),
            ),
        )

        val viewModel = RawEventDetailViewModel(
            rawIngestionRepository = rawIngestionRepository,
            sourceOriginalResolver = sourceOriginalResolver,
            projectionPort = RoomBackedRawEventDetailProjectionPort(
                commitmentDao = db.commitmentDao(),
                calendarEventDao = db.calendarEventDao(),
                rawIngestionEventDao = db.rawIngestionEventDao(),
                personIndexDao = db.personIndexDao(),
                personEnrichmentRepository = enrichmentRepository,
            ),
            userPrefsStore = userPrefsStore,
            savedStateHandle = SavedStateHandle(mapOf(ARG_EVENT_ID to "email-event")),
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.loading || state.emailBody == null) {
                state = awaitItem()
            }

            assertEquals(SourceType.GMAIL, state.sourceType)
            assertEquals("메일 제목", state.eventTitle)
            assertEquals("본문 미리보기", state.snippet)
            assertEquals("긴 이메일 본문", state.emailBody?.bodyPlain)
            assertEquals("<p>긴 이메일 본문</p>", state.emailBody?.bodyHtml)
            assertEquals(1, state.attachmentCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SRC-004 raw event detail loads commitment quotes and calendar attendees from local owner`() = runTest {
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "calendar-event",
                sourceType = SourceType.GOOGLE_CALENDAR,
                timestamp = Instant.parse("2026-04-23T05:00:00Z"),
                title = "캘린더 일정",
                snippet = "회의 미리보기",
            ).copy(sourceRef = "calendar-ref-1"),
        )
        db.commitmentDao().insertAll(
            listOf(
                commitment(
                    id = "quote-1",
                    title = "첫 약속",
                    actionState = "pending",
                    sourceType = SourceType.GOOGLE_CALENDAR,
                    sourceEventOccurredAt = Instant.parse("2026-04-23T05:00:00Z"),
                ).copy(sourceRef = "calendar-ref-1", quote = "첫 약속 인용문"),
                commitment(
                    id = "quote-2",
                    title = "두 번째 약속",
                    actionState = "pending",
                    sourceType = SourceType.GOOGLE_CALENDAR,
                    sourceEventOccurredAt = Instant.parse("2026-04-23T05:01:00Z"),
                ).copy(sourceRef = "calendar-ref-1", quote = "두 번째 약속 인용문"),
            ),
        )
        db.calendarEventDao().insertAll(
            listOf(
                CalendarEventEntity(
                    id = "calendar-row-1",
                    userId = USER_ID,
                    sourceType = SourceType.GOOGLE_CALENDAR,
                    sourceRef = "calendar-ref-1",
                    title = "캘린더 일정",
                    startAt = Instant.parse("2026-04-23T05:00:00Z"),
                    endAt = Instant.parse("2026-04-23T06:00:00Z"),
                    attendeesRaw = "alice@example.com,bob@example.com",
                    syncStatus = "synced",
                ),
            ),
        )

        val viewModel = RawEventDetailViewModel(
            rawIngestionRepository = rawIngestionRepository,
            sourceOriginalResolver = sourceOriginalResolver,
            projectionPort = RoomBackedRawEventDetailProjectionPort(
                commitmentDao = db.commitmentDao(),
                calendarEventDao = db.calendarEventDao(),
                rawIngestionEventDao = db.rawIngestionEventDao(),
                personIndexDao = db.personIndexDao(),
                personEnrichmentRepository = enrichmentRepository,
            ),
            userPrefsStore = userPrefsStore,
            savedStateHandle = SavedStateHandle(mapOf(ARG_EVENT_ID to "calendar-event")),
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.loading || state.commitmentQuotes.size < 2 || state.extractedCommitments.size < 2 || state.attendeesRaw == null) {
                state = awaitItem()
            }

            assertEquals(
                listOf("두 번째 약속 인용문", "첫 약속 인용문"),
                state.commitmentQuotes,
            )
            assertEquals(
                listOf("두 번째 약속", "첫 약속"),
                state.extractedCommitments.map { it.title },
            )
            assertEquals("alice@example.com,bob@example.com", state.attendeesRaw)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `raw event detail hides already resolved participant corrections`() = runTest {
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "event-corrections",
                sourceType = SourceType.GMAIL,
                timestamp = Instant.parse("2026-04-23T05:00:00Z"),
                title = "메일 제목",
                snippet = "메일 미리보기",
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-resolved",
                    sourceEventId = "event-corrections",
                    personId = "person-resolved",
                    displayName = "이미 연결된 사람",
                    resolutionStatus = "resolved",
                ),
                sourceParticipant(
                    id = "participant-unresolved",
                    sourceEventId = "event-corrections",
                    personId = null,
                    displayName = "확인할 사람",
                    resolutionStatus = "unresolved",
                ),
                sourceParticipant(
                    id = "participant-suggested-self",
                    sourceEventId = "event-corrections",
                    personId = null,
                    displayName = "내 계정 후보",
                    resolutionStatus = "suggested_self",
                ),
                sourceParticipant(
                    id = "participant-ignored",
                    sourceEventId = "event-corrections",
                    personId = null,
                    displayName = "무시된 후보",
                    resolutionStatus = "ignored",
                ),
            ),
        )

        val viewModel = RawEventDetailViewModel(
            rawIngestionRepository = rawIngestionRepository,
            sourceOriginalResolver = sourceOriginalResolver,
            projectionPort = RoomBackedRawEventDetailProjectionPort(
                commitmentDao = db.commitmentDao(),
                calendarEventDao = db.calendarEventDao(),
                rawIngestionEventDao = db.rawIngestionEventDao(),
                personIndexDao = db.personIndexDao(),
                personEnrichmentRepository = enrichmentRepository,
            ),
            userPrefsStore = userPrefsStore,
            savedStateHandle = SavedStateHandle(mapOf(ARG_EVENT_ID to "event-corrections")),
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.loading) {
                state = awaitItem()
            }

            assertEquals(
                setOf("participant-unresolved", "participant-suggested-self"),
                state.participantCorrections.map { it.participantId }.toSet(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SRC-004 raw event detail resolves source-event anchor to local original and extracted context`() = runTest {
        val localRawEventId = "raw-local-email-1"
        val sourceEventId = "backend-source-email-1"
        val occurredAt = Instant.parse("2026-04-23T06:00:00Z")
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = localRawEventId,
                sourceType = SourceType.GMAIL,
                timestamp = occurredAt,
                title = "자료 확인 메일",
                snippet = "메일 미리보기",
            ).copy(sourceRef = "provider-message-1"),
        )
        emailBodyRepository.insert(
            EmailBodyEntity(
                id = "body-anchor-1",
                rawEventId = localRawEventId,
                providerMessageId = "provider-message-1",
                folder = "INBOX",
                subject = "자료 확인 메일",
                fromAddress = "sender@example.com",
                toAddresses = "[{\"email\":\"user@example.com\"}]",
                bodyPlain = "긴 이메일 원문",
                bodyHtml = "<p>긴 이메일 원문</p>",
                attachmentsMeta = null,
                rawHeaders = null,
                parseFailed = false,
                groupEmail = false,
                receivedAt = occurredAt,
            ),
        )
        db.commitmentDao().insertAll(
            listOf(
                commitment(
                    id = "anchor-quote-1",
                    title = "자료 보내기",
                    actionState = "pending",
                    sourceType = SourceType.GMAIL,
                    sourceEventOccurredAt = occurredAt,
                ).copy(
                    sourceRef = localRawEventId,
                    quote = "자료는 오늘 중으로 보내겠습니다",
                ),
            ),
        )
        db.sourceEventAnchorDao().upsertAll(
            listOf(
                SourceEventAnchorEntity(
                    id = "anchor-email-1",
                    userId = USER_ID,
                    sourceType = SourceType.GMAIL,
                    sourceOrigin = SourceEventAnchorOrigin.BACKEND,
                    sourceEventId = sourceEventId,
                    localRawEventId = localRawEventId,
                    sourceConnectionId = "source-connection-1",
                    sourceAccountKey = "gmail:user@example.com",
                    providerEventId = "provider-message-1",
                    conversationRef = "thread-1",
                    sourceRef = "provider-message-1",
                    title = "자료 확인 메일",
                    snippet = "메일 미리보기",
                    occurredAt = occurredAt,
                    createdAt = occurredAt,
                    updatedAt = occurredAt,
                ),
            ),
        )

        val viewModel = RawEventDetailViewModel(
            rawIngestionRepository = rawIngestionRepository,
            sourceOriginalResolver = sourceOriginalResolver,
            sourceEventAnchorDao = db.sourceEventAnchorDao(),
            projectionPort = RoomBackedRawEventDetailProjectionPort(
                commitmentDao = db.commitmentDao(),
                calendarEventDao = db.calendarEventDao(),
                rawIngestionEventDao = db.rawIngestionEventDao(),
                personIndexDao = db.personIndexDao(),
                personEnrichmentRepository = enrichmentRepository,
            ),
            userPrefsStore = userPrefsStore,
            savedStateHandle = SavedStateHandle(mapOf(ARG_EVENT_ID to sourceEventId)),
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.loading || state.emailBody == null || state.commitmentQuotes.isEmpty() || state.extractedCommitments.isEmpty()) {
                state = awaitItem()
            }

            assertEquals(sourceEventId, state.eventId)
            assertEquals("긴 이메일 원문", state.emailBody?.bodyPlain)
            assertEquals(listOf("자료는 오늘 중으로 보내겠습니다"), state.commitmentQuotes)
            assertEquals(listOf("자료 보내기"), state.extractedCommitments.map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun rawEvent(
        id: String,
        sourceType: String,
        timestamp: Instant,
        title: String,
        snippet: String,
    ): RawIngestionEventEntity = RawIngestionEventEntity(
        id = id,
        userId = USER_ID,
        clientEventId = "client-$id",
        sourceType = sourceType,
        sourceRef = "ref-$id",
        counterpartyRef = PERSON_REF,
        eventTitle = title,
        eventSnippet = snippet,
        commitmentsExtractedCount = 1,
        timestamp = timestamp,
        syncStatus = "synced",
    )

    private fun commitment(
        id: String,
        title: String,
        actionState: String,
        sourceType: String,
        sourceEventOccurredAt: Instant,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = USER_ID,
        direction = "give",
        counterpartyRaw = PERSON_REF,
        counterpartyRef = PERSON_REF,
        title = title,
        description = null,
        quote = "quote-$id",
        sourceEventTitle = "source-$id",
        sourceEventOccurredAt = sourceEventOccurredAt,
        dueAt = null,
        dueHint = null,
        actionState = actionState,
        sourceType = sourceType,
        sourceRef = "source-ref-$id",
        confidence = 0.8,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
        createdAt = sourceEventOccurredAt,
        updatedAt = sourceEventOccurredAt,
    )

    private fun indexedInteraction(
        personId: String,
        sourceType: String,
        sourceRef: String,
        kind: String,
        role: String,
        direction: String?,
        status: String?,
        occurredAt: Instant,
        title: String?,
        snippet: String?,
    ): PersonInteractionEntity = PersonInteractionEntity(
        id = UUID.nameUUIDFromBytes("detail-test:$sourceRef:$kind".toByteArray()).toString(),
        userId = USER_ID,
        personId = personId,
        sourceType = sourceType,
        sourceRef = sourceRef,
        interactionKind = kind,
        role = role,
        direction = direction,
        status = status,
        occurredAt = occurredAt,
        title = title,
        snippet = snippet,
        confidence = 1.0,
    )

    private fun sourceParticipant(
        id: String,
        sourceEventId: String,
        personId: String?,
        displayName: String,
        resolutionStatus: String,
    ): SourceEventParticipantEntity =
        SourceEventParticipantEntity(
            id = id,
            userId = USER_ID,
            sourceEventId = sourceEventId,
            sourceType = SourceType.GMAIL,
            sourceRef = sourceEventId,
            personId = personId,
            role = "counterparty",
            relationToUser = "counterparty",
            identityType = "name",
            normalizedValue = displayName,
            displayNameRaw = displayName,
            emailRaw = null,
            phoneRaw = null,
            organizationRaw = null,
            titleRaw = null,
            evidence = displayName,
            confidence = 0.95,
            resolutionStatus = resolutionStatus,
            createdAt = Instant.parse("2026-04-23T05:00:00Z"),
        )

    private companion object {
        private const val USER_ID = "user-1"
        private const val PERSON_REF = "+821012345678"
    }
}

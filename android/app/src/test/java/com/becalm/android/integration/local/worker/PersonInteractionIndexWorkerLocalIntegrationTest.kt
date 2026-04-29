package com.becalm.android.integration.local.worker

import androidx.work.ListenableWorker
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentScheduleStatus
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourcePersonCandidateEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.worker.PersonInteractionIndexWorker
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PersonInteractionIndexWorkerLocalIntegrationTest {

    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val userPrefsStore = UserPrefsStoreImpl(
        LocalIntegrationSupport.prefsDataStore("person-index-worker-prefs"),
    )
    private val logger = RecordingLogger()
    private val dispatcher = UnconfinedTestDispatcher()

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `AI and deterministic source rows build person based index across mail calendar voice and call`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        seedSourceRows()

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)

        val emailPersonId = requireNotNull(
            PersonIdentityResolver.resolve(USER_ID, CUSTOMER_EMAIL),
        ).personId
        val phonePersonId = requireNotNull(
            PersonIdentityResolver.resolve(USER_ID, CUSTOMER_PHONE),
        ).personId

        val aggregates = db.personIndexDao().observeAggregates(USER_ID, limit = 20).first()
        val emailAggregate = requireNotNull(aggregates.firstOrNull { it.personId == emailPersonId })
        val phoneAggregate = requireNotNull(aggregates.firstOrNull { it.personId == phonePersonId })

        assertEquals(1, emailAggregate.pendingCommitmentCount)
        assertTrue(emailAggregate.channelSources.orEmpty().contains(SourceType.GMAIL))
        assertTrue(emailAggregate.channelSources.orEmpty().contains(SourceType.OUTLOOK_MAIL))
        assertTrue(emailAggregate.channelSources.orEmpty().contains(SourceType.NAVER_IMAP))
        assertTrue(emailAggregate.channelSources.orEmpty().contains(SourceType.DAUM_IMAP))
        assertTrue(emailAggregate.channelSources.orEmpty().contains(SourceType.GOOGLE_CALENDAR))
        assertTrue(emailAggregate.channelSources.orEmpty().contains(SourceType.OUTLOOK_CALENDAR))
        assertTrue(emailAggregate.channelSources.orEmpty().contains(SourceType.VOICE))
        assertTrue(phoneAggregate.channelSources.orEmpty().contains(SourceType.CALL_RECORDING))

        val emailInteractions = db.personIndexDao()
            .observeInteractionsForPerson(USER_ID, emailPersonId, limit = 20)
            .first()
        val emailSources = emailInteractions.map { it.sourceType }.toSet()
        val emailKinds = emailInteractions.map { it.interactionKind }.toSet()

        assertTrue(SourceType.GMAIL in emailSources)
        assertTrue(SourceType.OUTLOOK_MAIL in emailSources)
        assertTrue(SourceType.NAVER_IMAP in emailSources)
        assertTrue(SourceType.DAUM_IMAP in emailSources)
        assertTrue(SourceType.GOOGLE_CALENDAR in emailSources)
        assertTrue(SourceType.OUTLOOK_CALENDAR in emailSources)
        assertTrue(SourceType.VOICE in emailSources)
        assertTrue("email" in emailKinds)
        assertTrue("calendar" in emailKinds)
        assertTrue("call" in emailKinds)
        assertTrue("commitment" in emailKinds)
        assertTrue(
            emailInteractions.any {
                it.interactionKind == "commitment" &&
                    it.role == CommitmentItemType.SCHEDULE &&
                    it.status == CommitmentScheduleStatus.CONFIRMED
            },
        )

        val phoneInteractions = db.personIndexDao()
            .observeInteractionsForPerson(USER_ID, phonePersonId, limit = 20)
            .first()
        assertTrue(phoneInteractions.any { it.sourceType == SourceType.CALL_RECORDING && it.interactionKind == "call" })
        assertTrue(phoneInteractions.any { it.sourceType == SourceType.VOICE && it.interactionKind == "call" })
    }

    private suspend fun seedSourceRows() {
        db.rawIngestionEventDao().insertAll(
            listOf(
                rawEvent(
                    id = "raw-gmail-1",
                    clientEventId = "client-gmail-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-1",
                    personRef = CUSTOMER_EMAIL,
                    title = "Gmail 데모 미팅",
                    snippet = "목요일 오전 10시에 데모 미팅 가능하실까요?",
                    folder = "INBOX",
                    at = "2026-04-29T00:00:00Z",
                ),
                rawEvent(
                    id = "raw-outlook-mail-1",
                    clientEventId = "client-outlook-mail-1",
                    sourceType = SourceType.OUTLOOK_MAIL,
                    sourceRef = "outlook-message-1",
                    personRef = CUSTOMER_EMAIL,
                    title = "Outlook 후속 메일",
                    snippet = "미팅 후 액션 아이템 공유드립니다.",
                    folder = "INBOX",
                    at = "2026-04-29T00:30:00Z",
                ),
                rawEvent(
                    id = "raw-naver-1",
                    clientEventId = "client-naver-1",
                    sourceType = SourceType.NAVER_IMAP,
                    sourceRef = "naver-message-1",
                    personRef = CUSTOMER_EMAIL,
                    title = "Naver 자료 요청",
                    snippet = "자료 확인 부탁드립니다.",
                    folder = "SENT",
                    at = "2026-04-29T01:00:00Z",
                ),
                rawEvent(
                    id = "raw-daum-1",
                    clientEventId = "client-daum-1",
                    sourceType = SourceType.DAUM_IMAP,
                    sourceRef = "daum-message-1",
                    personRef = CUSTOMER_EMAIL,
                    title = "Daum 회신",
                    snippet = "확인했습니다.",
                    folder = "INBOX",
                    at = "2026-04-29T02:00:00Z",
                ),
                rawEvent(
                    id = "raw-call-1",
                    clientEventId = "client-call-1",
                    sourceType = SourceType.CALL_RECORDING,
                    sourceRef = "content://call/1",
                    personRef = CUSTOMER_PHONE,
                    title = "전화 녹음",
                    snippet = "견적서는 제가 오늘 보내드릴게요.",
                    folder = null,
                    at = "2026-04-29T03:00:00Z",
                ),
                rawEvent(
                    id = "raw-voice-1",
                    clientEventId = "client-voice-1",
                    sourceType = SourceType.VOICE,
                    sourceRef = "content://voice/1",
                    personRef = CUSTOMER_PHONE,
                    title = "고객 통화 메모",
                    snippet = "고객 이메일은 customer@example.com 입니다.",
                    folder = null,
                    at = "2026-04-29T04:00:00Z",
                ),
            ),
        )

        db.calendarEventDao().insertAll(
            listOf(
                CalendarEventEntity(
                    id = "calendar-1",
                    userId = USER_ID,
                    sourceType = SourceType.GOOGLE_CALENDAR,
                    sourceRef = "calendar-event-1",
                    title = "데모 미팅",
                    startAt = Instant.parse("2026-04-30T01:00:00Z"),
                    endAt = Instant.parse("2026-04-30T02:00:00Z"),
                    attendeesRaw = "$CUSTOMER_EMAIL, teammate@example.com",
                    syncStatus = "synced",
                ),
                CalendarEventEntity(
                    id = "calendar-2",
                    userId = USER_ID,
                    sourceType = SourceType.OUTLOOK_CALENDAR,
                    sourceRef = "outlook-calendar-event-1",
                    title = "후속 미팅",
                    startAt = Instant.parse("2026-05-01T01:00:00Z"),
                    endAt = Instant.parse("2026-05-01T02:00:00Z"),
                    attendeesRaw = CUSTOMER_EMAIL,
                    syncStatus = "synced",
                ),
            ),
        )

        db.commitmentDao().insertAll(
            listOf(
                CommitmentEntity(
                    id = "commitment-1",
                    userId = USER_ID,
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                    decisionStatus = null,
                    counterpartyRaw = CUSTOMER_EMAIL,
                    personRef = CUSTOMER_EMAIL,
                    title = "목요일 오전 10시 데모 미팅",
                    description = null,
                    quote = "목요일 오전 10시에 데모 미팅 가능하실까요?",
                    sourceEventTitle = "Gmail 데모 미팅",
                    sourceEventOccurredAt = Instant.parse("2026-04-29T00:00:00Z"),
                    dueAt = Instant.parse("2026-04-30T01:00:00Z"),
                    dueHint = "목요일 오전 10시",
                    dueIsApproximate = false,
                    actionState = "pending",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-1",
                    confidence = 0.93,
                    syncStatus = "synced",
                    createdAt = Instant.parse("2026-04-29T00:00:10Z"),
                    updatedAt = Instant.parse("2026-04-29T00:00:10Z"),
                ),
            ),
        )

        db.personIndexDao().upsertCandidates(
            listOf(
                SourcePersonCandidateEntity(
                    id = "candidate-voice-email-1",
                    userId = USER_ID,
                    sourceType = SourceType.VOICE,
                    sourceRef = "raw:raw-voice-1",
                    candidateRef = "counterparty:$CUSTOMER_EMAIL",
                    role = "counterparty",
                    name = "김고객",
                    email = CUSTOMER_EMAIL,
                    phone = null,
                    organization = "Acme",
                    evidence = "고객 이메일은 customer@example.com 입니다.",
                    confidence = 0.91,
                    createdAt = Instant.parse("2026-04-29T04:00:05Z"),
                ),
            ),
        )
    }

    private fun newWorker(): PersonInteractionIndexWorker =
        PersonInteractionIndexWorker(
            appContext = LocalIntegrationSupport.appContext(),
            workerParams = LocalIntegrationSupport.workerParams(),
            databaseProvider = Provider { db },
            rawDaoProvider = Provider { db.rawIngestionEventDao() },
            commitmentDaoProvider = Provider { db.commitmentDao() },
            calendarDaoProvider = Provider { db.calendarEventDao() },
            personIndexDaoProvider = Provider { db.personIndexDao() },
            userPrefsStore = userPrefsStore,
            logger = logger,
            ioDispatcher = dispatcher,
        )

    private fun rawEvent(
        id: String,
        clientEventId: String,
        sourceType: String,
        sourceRef: String,
        personRef: String,
        title: String,
        snippet: String,
        folder: String?,
        at: String,
    ): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = id,
            userId = USER_ID,
            clientEventId = clientEventId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            personRef = personRef,
            eventTitle = title,
            eventSnippet = snippet,
            folder = folder,
            commitmentsExtractedCount = if (sourceType == SourceType.GMAIL) 1 else 0,
            timestamp = Instant.parse(at),
            syncStatus = "synced",
        )

    private companion object {
        private const val USER_ID = "user-1"
        private const val CUSTOMER_EMAIL = "customer@example.com"
        private const val CUSTOMER_PHONE = "+821012345678"
    }
}

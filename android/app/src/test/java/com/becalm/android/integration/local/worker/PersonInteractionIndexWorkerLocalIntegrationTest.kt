package com.becalm.android.integration.local.worker

import androidx.work.ListenableWorker
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentDecisionStatus
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
        assertTrue("email" in emailKinds)
        assertTrue("calendar" in emailKinds)
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

    @Test
    fun `person index filters automated senders and uses AI name email pair for calendar title mentions`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-noreply-1",
                clientEventId = "client-noreply-1",
                sourceType = SourceType.GMAIL,
                sourceRef = "gmail-noreply-1",
                personRef = "noreply@example.com",
                title = "자동 알림",
                snippet = "이 메일은 발신 전용입니다.",
                folder = "INBOX",
                at = "2026-04-29T00:00:00Z",
            ),
        )
        db.personIndexDao().upsertCandidates(
            listOf(
                SourcePersonCandidateEntity(
                    id = "candidate-starloss-1",
                    userId = USER_ID,
                    sourceType = SourceType.GMAIL,
                    sourceRef = "raw:raw-human-1",
                    candidateRef = "sender:starloss28@gmail.com",
                    role = "sender",
                    name = "강지훈",
                    email = "starloss28@gmail.com",
                    phone = null,
                    organization = null,
                    evidence = "강지훈 <starloss28@gmail.com>",
                    confidence = 0.95,
                    createdAt = Instant.parse("2026-04-29T00:01:00Z"),
                ),
            ),
        )
        db.calendarEventDao().insertAll(
            listOf(
                CalendarEventEntity(
                    id = "calendar-title-1",
                    userId = USER_ID,
                    sourceType = SourceType.GOOGLE_CALENDAR,
                    sourceRef = "calendar-title-1",
                    title = "강지훈 미팅",
                    startAt = Instant.parse("2026-04-30T01:00:00Z"),
                    endAt = Instant.parse("2026-04-30T02:00:00Z"),
                    attendeesRaw = null,
                    syncStatus = "synced",
                ),
            ),
        )

        assertEquals(ListenableWorker.Result.success().javaClass, newWorker().doWork().javaClass)

        val automatedPerson = requireNotNull(PersonIdentityResolver.resolve(USER_ID, "noreply@example.com")).personId
        val humanPerson = requireNotNull(PersonIdentityResolver.resolve(USER_ID, "starloss28@gmail.com")).personId
        val aggregates = db.personIndexDao().observeAggregates(USER_ID, limit = 20).first()
        assertTrue(aggregates.none { it.personId == automatedPerson })
        assertTrue(aggregates.any { it.personId == humanPerson })

        val humanInteractions = db.personIndexDao()
            .observeInteractionsForPerson(USER_ID, humanPerson, limit = 20)
            .first()
        assertTrue(humanInteractions.any { it.interactionKind == "calendar" && it.title == "강지훈 미팅" })
    }

    @Test
    fun `same raw source can produce separate give and take commitments matched to different people`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.commitmentDao().insertAll(
            listOf(
                actionCommitment(
                    id = "commitment-give-shared",
                    personRef = "jihun@example.com",
                    direction = "give",
                    title = "지훈에게 견적서 보내기",
                ),
                actionCommitment(
                    id = "commitment-take-shared",
                    personRef = "minji@example.com",
                    direction = "take",
                    title = "민지에게 계약서 받기",
                ),
            ),
        )

        assertEquals(ListenableWorker.Result.success().javaClass, newWorker().doWork().javaClass)

        val jihunPersonId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, "jihun@example.com")).personId
        val minjiPersonId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, "minji@example.com")).personId
        val jihunInteractions = db.personIndexDao()
            .observeInteractionsForPerson(USER_ID, jihunPersonId, limit = 10)
            .first()
        val minjiInteractions = db.personIndexDao()
            .observeInteractionsForPerson(USER_ID, minjiPersonId, limit = 10)
            .first()

        assertTrue(
            jihunInteractions.any {
                it.interactionKind == "commitment" &&
                    it.sourceRef == "commitment:commitment-give-shared" &&
                    it.direction == "give" &&
                    it.title == "지훈에게 견적서 보내기"
            },
        )
        assertTrue(
            minjiInteractions.any {
                it.interactionKind == "commitment" &&
                    it.sourceRef == "commitment:commitment-take-shared" &&
                    it.direction == "take" &&
                    it.title == "민지에게 계약서 받기"
            },
        )
    }

    @Test
    fun `decision commitments are not assigned from candidate reference alone`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.personIndexDao().upsertCandidates(
            listOf(
                SourcePersonCandidateEntity(
                    id = "candidate-decision-1",
                    userId = USER_ID,
                    sourceType = SourceType.GMAIL,
                    sourceRef = "raw:gmail-decision-message-1",
                    candidateRef = "sender:decision@example.com",
                    role = "sender",
                    name = "Decision Sender",
                    email = "decision@example.com",
                    phone = null,
                    organization = null,
                    evidence = "Decision Sender <decision@example.com>",
                    confidence = 0.95,
                    createdAt = Instant.parse("2026-04-29T06:00:00Z"),
                ),
            ),
        )
        db.commitmentDao().insertAll(
            listOf(
                CommitmentEntity(
                    id = "commitment-decision-candidate-only",
                    userId = USER_ID,
                    itemType = CommitmentItemType.DECISION,
                    direction = null,
                    scheduleStatus = null,
                    decisionStatus = CommitmentDecisionStatus.CHOSEN,
                    counterpartyRaw = null,
                    personRef = null,
                    title = "A안을 선택하기로 결정",
                    description = null,
                    quote = "A안을 선택하기로 결정했습니다.",
                    sourceEventTitle = "회의록",
                    sourceEventOccurredAt = Instant.parse("2026-04-29T06:00:00Z"),
                    dueAt = null,
                    dueHint = null,
                    dueIsApproximate = false,
                    actionState = "pending",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-decision-message-1",
                    confidence = 0.9,
                    syncStatus = "synced",
                    createdAt = Instant.parse("2026-04-29T06:00:10Z"),
                    updatedAt = Instant.parse("2026-04-29T06:00:10Z"),
                ),
            ),
        )

        assertEquals(ListenableWorker.Result.success().javaClass, newWorker().doWork().javaClass)

        val candidatePersonId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, "decision@example.com")).personId
        val interactions = db.personIndexDao()
            .observeInteractionsForPerson(USER_ID, candidatePersonId, limit = 10)
            .first()
        assertTrue(interactions.none { it.sourceRef == "commitment:commitment-decision-candidate-only" })
    }

    @Test
    fun `user blocked person refs are removed on next index rebuild`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-blocked-1",
                clientEventId = "client-blocked-1",
                sourceType = SourceType.GMAIL,
                sourceRef = "gmail-blocked-1",
                personRef = "merchant@example.com",
                title = "결제 알림",
                snippet = "구매 확인",
                folder = "INBOX",
                at = "2026-04-29T07:00:00Z",
            ),
        )

        assertEquals(ListenableWorker.Result.success().javaClass, newWorker().doWork().javaClass)
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, "merchant@example.com")).personId
        assertTrue(
            db.personIndexDao().observeInteractionsForPerson(USER_ID, personId, limit = 10).first().isNotEmpty(),
        )

        userPrefsStore.blockPersonRef("merchant@example.com")
        assertEquals(ListenableWorker.Result.success().javaClass, newWorker().doWork().javaClass)

        assertTrue(
            db.personIndexDao().observeInteractionsForPerson(USER_ID, personId, limit = 10).first().isEmpty(),
        )
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

    private fun actionCommitment(
        id: String,
        personRef: String,
        direction: String,
        title: String,
    ): CommitmentEntity =
        CommitmentEntity(
            id = id,
            userId = USER_ID,
            itemType = CommitmentItemType.ACTION,
            direction = direction,
            scheduleStatus = null,
            decisionStatus = null,
            counterpartyRaw = personRef,
            personRef = personRef,
            title = title,
            description = null,
            quote = title,
            sourceEventTitle = "공유 원본 이메일",
            sourceEventOccurredAt = Instant.parse("2026-04-29T05:00:00Z"),
            dueAt = null,
            dueHint = null,
            dueIsApproximate = false,
            actionState = "pending",
            sourceType = SourceType.GMAIL,
            sourceRef = "gmail-shared-message-1",
            confidence = 0.91,
            syncStatus = "synced",
            createdAt = Instant.parse("2026-04-29T05:00:10Z"),
            updatedAt = Instant.parse("2026-04-29T05:00:10Z"),
        )

    private companion object {
        private const val USER_ID = "user-1"
        private const val CUSTOMER_EMAIL = "customer@example.com"
        private const val CUSTOMER_PHONE = "+821012345678"
    }
}

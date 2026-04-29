package com.becalm.android.integration.local.worker

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ListenableWorker
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentScheduleStatus
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.VoiceApi
import com.becalm.android.data.remote.dto.PersonCandidateDto
import com.becalm.android.data.remote.dto.ScheduleStatus
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.dto.TranscribeExtractResponse
import com.becalm.android.data.remote.dto.VoiceExtractItemDto
import com.becalm.android.data.remote.dto.VoiceItemType
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.worker.PersonInteractionIndexWorker
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.VoiceFailureNotifier
import com.becalm.android.worker.VoiceUploadWorker
import com.becalm.android.worker.WorkScheduler
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayInputStream
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AiPersonPipelineLocalIntegrationTest {

    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val appContext: Context = mockk(relaxed = true)
    private val contentResolver: ContentResolver = mockk(relaxed = true)
    private val parsedUri: Uri = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk()
    private val voiceApi: VoiceApi = mockk()
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val processingStatusRepository: ProcessingStatusRepository = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val processingPauseGate: ProcessingPauseGate = mockk(relaxed = true)
    private val voiceFailureNotifier: VoiceFailureNotifier = mockk(relaxed = true)
    private val logger = RecordingLogger()
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        every { appContext.applicationContext } returns appContext
        every { appContext.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(any()) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(USER_ID)
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        every { userPrefsStore.observeNotificationsEnabled() } returns flowOf(false)
        coEvery { processingPauseGate.shouldSkip(any()) } returns false

        mockkStatic(ContextCompat::class)
        mockkStatic(Uri::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns android.content.pm.PackageManager.PERMISSION_GRANTED
        every { Uri.parse(any()) } returns parsedUri
    }

    @After
    fun tearDown() {
        unmockkStatic(ContextCompat::class)
        unmockkStatic(Uri::class)
        db.close()
    }

    @Test
    fun `Vertex voice response is persisted and indexed into the same person`() = runTest {
        db.rawIngestionEventDao().insert(aiVoiceRawEvent())
        coEvery {
            voiceApi.transcribeExtract(
                audio = any(),
                clientEventId = any(),
                rawEventId = any(),
                durationSeconds = any(),
                timestamp = any(),
                personRef = any(),
                eventTitle = any(),
            )
        } returns Response.success(aiVoiceResponse())

        val uploadResult = newVoiceWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, uploadResult.javaClass)
        val storedRaw = requireNotNull(db.rawIngestionEventDao().findById(RAW_ID, USER_ID))
        assertEquals(2, storedRaw.commitmentsExtractedCount)
        assertEquals("내일 오전 10시 데모 미팅 확정입니다.", storedRaw.eventSnippet)

        val commitments = db.commitmentDao().findLiveForPersonIndex(USER_ID)
        assertEquals(2, commitments.size)
        assertTrue(commitments.any { it.itemType == CommitmentItemType.SCHEDULE && it.scheduleStatus == ScheduleStatus.CONFIRMED })
        assertTrue(commitments.any { it.itemType == CommitmentItemType.ACTION && it.direction == "give" })
        assertTrue(commitments.all { it.personRef == CUSTOMER_EMAIL })

        val candidates = db.personIndexDao().findCandidatesForUser(USER_ID)
        assertEquals(1, candidates.size)
        assertEquals(CUSTOMER_EMAIL, candidates.single().email)

        val indexResult = newPersonIndexWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, indexResult.javaClass)
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, CUSTOMER_EMAIL)).personId
        val aggregate = db.personIndexDao().observeAggregates(USER_ID, limit = 10).first()
            .single { it.personId == personId }
        assertEquals(2, aggregate.pendingCommitmentCount)
        assertTrue(aggregate.channelSources.orEmpty().contains(SourceType.VOICE))

        val interactions = db.personIndexDao()
            .observeInteractionsForPerson(USER_ID, personId, limit = 10)
            .first()
        assertEquals(3, interactions.size)
        assertTrue(interactions.any { it.interactionKind == "call" && it.sourceRef == "raw:$RAW_ID" })
        assertTrue(
            interactions.any {
                it.interactionKind == "commitment" &&
                    it.role == CommitmentItemType.SCHEDULE &&
                    it.status == CommitmentScheduleStatus.CONFIRMED
            },
        )
        assertTrue(
            interactions.any {
                it.interactionKind == "commitment" &&
                    it.role == CommitmentItemType.ACTION &&
                    it.direction == "give"
            },
        )
        coVerify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex() }
    }

    private fun newVoiceWorker(): VoiceUploadWorker =
        VoiceUploadWorker(
            appContext = appContext,
            workerParams = LocalIntegrationSupport.workerParams(
                inputData = Data.Builder()
                    .putString(VoiceUploadWorker.KEY_RAW_EVENT_ID, RAW_ID)
                    .putString(VoiceUploadWorker.KEY_AUDIO_URI, "content://voice/$RAW_ID")
                    .build(),
            ),
            rawIngestionEventDao = db.rawIngestionEventDao(),
            commitmentDao = db.commitmentDao(),
            personIndexDao = db.personIndexDao(),
            voiceApi = voiceApi,
            userPrefsStore = userPrefsStore,
            sourceStatusRepository = sourceStatusRepository,
            processingStatusRepository = processingStatusRepository,
            workScheduler = workScheduler,
            processingPauseGate = processingPauseGate,
            voiceFailureNotifier = voiceFailureNotifier,
            moshi = Moshi.Builder().build(),
            logger = logger,
            ioDispatcher = dispatcher,
        )

    private fun newPersonIndexWorker(): PersonInteractionIndexWorker =
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

    private fun aiVoiceRawEvent(): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = RAW_ID,
            userId = USER_ID,
            clientEventId = "client-$RAW_ID",
            sourceType = SourceType.VOICE,
            sourceRef = "content://voice/$RAW_ID",
            personRef = null,
            eventTitle = "고객 통화",
            durationSeconds = 60,
            timestamp = Instant.parse("2026-04-29T00:00:00Z"),
            syncStatus = "pending",
        )

    private fun aiVoiceResponse(): TranscribeExtractResponse =
        TranscribeExtractResponse(
            rawEventId = RAW_ID,
            items = listOf(
                VoiceExtractItemDto(
                    type = VoiceItemType.SCHEDULE,
                    text = "내일 오전 10시 데모 미팅을 확정한다",
                    quote = "내일 오전 10시 데모 미팅 확정입니다.",
                    personRef = CUSTOMER_EMAIL,
                    dueAt = Instant.parse("2026-04-30T01:00:00Z"),
                    dueHint = "내일 오전 10시",
                    dueIsApproximate = false,
                    confidence = 0.94f,
                    scheduleStatus = ScheduleStatus.CONFIRMED,
                ),
                VoiceExtractItemDto(
                    type = VoiceItemType.ACTION,
                    text = "오늘 견적서를 보낸다",
                    quote = "견적서는 오늘 보내드리겠습니다.",
                    personRef = CUSTOMER_EMAIL,
                    dueAt = Instant.parse("2026-04-29T14:59:59Z"),
                    dueHint = "오늘",
                    dueIsApproximate = true,
                    confidence = 0.91f,
                    direction = "give",
                ),
            ),
            personCandidates = listOf(
                PersonCandidateDto(
                    role = "counterparty",
                    name = "김고객",
                    email = CUSTOMER_EMAIL,
                    organization = "Acme",
                    evidence = "김고객 customer@example.com",
                    confidence = 0.96,
                ),
            ),
            model = "gemini-2.5-flash",
            region = "us-central1",
            rawModelText = """{"items":[],"person_candidates":[]}""",
        )

    private companion object {
        private const val USER_ID = "user-1"
        private const val RAW_ID = "raw-ai-voice-1"
        private const val CUSTOMER_EMAIL = "customer@example.com"
    }
}

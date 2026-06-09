package com.becalm.android.unit.ui.sources

import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.entity.SourceConnectionEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceSyncJobResponse
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonActionRefreshStats
import com.becalm.android.data.repository.PersonActionRepository
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.ProcessingStatusMessages
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SelfIdentityRepository
import com.becalm.android.data.repository.SourceConnectionRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.NoopUserCorrectionRepository
import com.becalm.android.ui.sources.DefaultSourceSyncPort
import com.becalm.android.worker.WorkScheduler
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class SourceSyncPortSpecTest {

    private val authRepository: AuthRepository = mockk()
    private val api: RailwayApi = mockk()
    private val calendarEventRepository: CalendarEventRepository = mockk(relaxed = true)
    private val commitmentRepository: CommitmentRepository = mockk()
    private val commitmentParticipantRepository: CommitmentParticipantRepository = mockk()
    private val personActionRepository: PersonActionRepository = mockk(relaxed = true)
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val sourceEventParticipantRepository: SourceEventParticipantRepository = mockk()
    private val sourceConnectionRepository: SourceConnectionRepository = mockk(relaxed = true)
    private val syncCursorStore: SyncCursorStore = mockk(relaxed = true)
    private val selfIdentityRepository: SelfIdentityRepository = mockk(relaxed = true)
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val processingStatusRepository: ProcessingStatusRepository = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val productAnalytics = RecordingProductAnalyticsClient()

    @Before
    fun setUp() {
        coEvery { personActionRepository.refresh(any(), any()) } returns
            BecalmResult.Success(
                PersonActionRefreshStats(
                    fetched = 0,
                    deleted = 0,
                    serverWatermark = null,
                    recomputeState = null,
                ),
            )
    }

    private val subject = DefaultSourceSyncPort(
        authRepository = authRepository,
        apiProvider = Provider { api },
        calendarEventRepository = calendarEventRepository,
        commitmentRepository = commitmentRepository,
        commitmentParticipantRepository = commitmentParticipantRepository,
        rawIngestionRepository = rawIngestionRepository,
        personActionRepository = personActionRepository,
        sourceEventParticipantRepository = sourceEventParticipantRepository,
        sourceConnectionRepository = sourceConnectionRepository,
        syncCursorStore = syncCursorStore,
        selfIdentityRepository = selfIdentityRepository,
        sourceStatusRepository = sourceStatusRepository,
        processingStatusRepository = processingStatusRepository,
        userCorrectionRepository = NoopUserCorrectionRepository,
        workScheduler = workScheduler,
        logger = logger,
        moshi = Moshi.Builder().build(),
        productAnalytics = productAnalytics,
    )

    @Test
    // spec: ING-006
    fun `manual gmail sync refreshes commitments after backend sync succeeds`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        coEvery { sourceConnectionRepository.refresh("user-1") } returns BecalmResult.Success(
            listOf(sourceConnection("conn-gmail", provider = "google", capability = "mail")),
        )
        coEvery { api.syncSourceConnection("conn-gmail") } returns Response.success(
            SourceSyncJobResponse(
                jobId = "job-mail-1",
                status = "succeeded",
                accepted = false,
                synced = 1,
                provider = "gmail",
                capability = "mail",
                sourceConnectionId = "conn-gmail",
            ),
        )
        coEvery { rawIngestionRepository.refreshSince(userId = "user-1", sourceType = SourceType.GMAIL, since = null) } returns
            BecalmResult.Success(
                RawIngestionRepository.RefreshStats(
                    fetched = 1,
                    upserted = 1,
                    hasMore = false,
                    nextCursor = "raw-cursor-1",
                ),
            )
        coEvery { sourceEventParticipantRepository.refreshSince(userId = "user-1", sourceType = SourceType.GMAIL, since = null) } returns
            BecalmResult.Success(
                SourceEventParticipantRepository.RefreshStats(
                    fetched = 1,
                    upserted = 1,
                    hasMore = false,
                    nextCursor = "candidate-cursor-1",
                ),
            )
        coEvery { commitmentRepository.refreshSince(userId = "user-1", since = null) } returns
            BecalmResult.Success(
                CommitmentRepository.RefreshStats(
                    fetched = 1,
                    upserted = 1,
                    hasMore = false,
                    nextCursor = "cursor-1",
                ),
            )
        coEvery { commitmentParticipantRepository.refreshSince(userId = "user-1", since = null) } returns
            BecalmResult.Success(
                CommitmentParticipantRepository.RefreshStats(
                    fetched = 1,
                    upserted = 1,
                    hasMore = false,
                    nextCursor = "commitment-participant-cursor-1",
                ),
            )
        coEvery { selfIdentityRepository.refresh("user-1") } returns BecalmResult.Success(emptyList())
        coEvery { sourceStatusRepository.refreshFromServer() } returns BecalmResult.Success(Unit)

        val result = subject.requestManualSync(SourceType.GMAIL)

        assertTrue(result is BecalmResult.Success)
        assertEquals(
            listOf(
                ProductAnalyticsEvents.SOURCE_SYNC_STARTED,
                ProductAnalyticsEvents.SOURCE_SYNC_COMPLETED,
            ),
            productAnalytics.events.map { it.eventName },
        )
        assertEquals("gmail", productAnalytics.events.last().properties["source_type"])
        assertEquals("backend", productAnalytics.events.last().properties["owner"])
        assertEquals("success", productAnalytics.events.last().properties["result"])
        coVerify(exactly = 1) { api.syncSourceConnection("conn-gmail") }
        coVerify(exactly = 1) { rawIngestionRepository.refreshSince(userId = "user-1", sourceType = SourceType.GMAIL, since = null) }
        coVerify(exactly = 1) { sourceEventParticipantRepository.refreshSince(userId = "user-1", sourceType = SourceType.GMAIL, since = null) }
        coVerify(exactly = 1) { commitmentRepository.refreshSince(userId = "user-1", since = null) }
        coVerify(exactly = 1) { commitmentParticipantRepository.refreshSince(userId = "user-1", since = null) }
        coVerify(exactly = 2) { sourceConnectionRepository.refresh("user-1") }
        coVerify(exactly = 1) { selfIdentityRepository.refresh("user-1") }
        coVerify(exactly = 1) { sourceStatusRepository.refreshFromServer() }
        coVerify(exactly = 1) { sourceStatusRepository.recordSyncSuccess(SourceType.GMAIL, any()) }
        coVerify(exactly = 1) { processingStatusRepository.recordScanning(SourceType.GMAIL, null) }
        coVerify(exactly = 1) { processingStatusRepository.recordUploading(SourceType.GMAIL, null) }
        coVerify(exactly = 1) { processingStatusRepository.recordSynced(SourceType.GMAIL, any(), null) }
        coVerify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex() }
    }

    @Test
    fun `manual gmail sync polls accepted backend job before mirror refresh`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        coEvery { sourceConnectionRepository.refresh("user-1") } returns BecalmResult.Success(
            listOf(sourceConnection("conn-gmail", provider = "google", capability = "mail")),
        )
        coEvery { api.syncSourceConnection("conn-gmail") } returns Response.success(
            SourceSyncJobResponse(
                jobId = "job-mail-1",
                status = "pending",
                accepted = true,
                retryAfterSeconds = 0,
                provider = "gmail",
                capability = "mail",
                sourceConnectionId = "conn-gmail",
            ),
        )
        coEvery { api.getSourceSyncJob("job-mail-1") } returns Response.success(
            SourceSyncJobResponse(
                jobId = "job-mail-1",
                status = "succeeded",
                accepted = false,
                synced = 2,
                provider = "gmail",
                capability = "mail",
                sourceConnectionId = "conn-mail",
            ),
        )
        coEvery { rawIngestionRepository.refreshSince(userId = "user-1", sourceType = SourceType.GMAIL, since = null) } returns
            BecalmResult.Success(
                RawIngestionRepository.RefreshStats(
                    fetched = 2,
                    upserted = 2,
                    hasMore = false,
                    nextCursor = "raw-cursor-1",
                ),
            )
        coEvery { sourceEventParticipantRepository.refreshSince(userId = "user-1", sourceType = SourceType.GMAIL, since = null) } returns
            BecalmResult.Success(
                SourceEventParticipantRepository.RefreshStats(
                    fetched = 1,
                    upserted = 1,
                    hasMore = false,
                    nextCursor = "candidate-cursor-1",
                ),
            )
        coEvery { commitmentRepository.refreshSince(userId = "user-1", since = null) } returns
            BecalmResult.Success(
                CommitmentRepository.RefreshStats(
                    fetched = 1,
                    upserted = 1,
                    hasMore = false,
                    nextCursor = "cursor-1",
                ),
            )
        coEvery { commitmentParticipantRepository.refreshSince(userId = "user-1", since = null) } returns
            BecalmResult.Success(
                CommitmentParticipantRepository.RefreshStats(
                    fetched = 1,
                    upserted = 1,
                    hasMore = false,
                    nextCursor = "commitment-participant-cursor-1",
                ),
            )
        coEvery { selfIdentityRepository.refresh("user-1") } returns BecalmResult.Success(emptyList())
        coEvery { sourceStatusRepository.refreshFromServer() } returns BecalmResult.Success(Unit)

        val result = subject.requestManualSync(SourceType.GMAIL)

        assertTrue(result is BecalmResult.Success)
        coVerify(exactly = 1) { api.getSourceSyncJob("job-mail-1") }
        coVerify(exactly = 1) { rawIngestionRepository.refreshSince(userId = "user-1", sourceType = SourceType.GMAIL, since = null) }
        coVerify(exactly = 1) { sourceStatusRepository.recordSyncSuccess(SourceType.GMAIL, any()) }
    }

    @Test
    fun `manual gmail sync surfaces backend backpressure as delayed processing status`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        coEvery { sourceConnectionRepository.refresh("user-1") } returns BecalmResult.Success(
            listOf(sourceConnection("conn-gmail", provider = "google", capability = "mail")),
        )
        coEvery { api.syncSourceConnection("conn-gmail") } returns Response.success(
            SourceSyncJobResponse(
                jobId = "job-mail-delayed",
                status = "retry",
                accepted = true,
                retryAfterSeconds = 10,
                provider = "gmail",
                capability = "mail",
                sourceConnectionId = "conn-gmail",
                errorCode = "backpressure_delayed",
                errorMessage = "Source sync accepted but delayed because worker backlog is high",
            ),
        )

        val result = subject.requestManualSync(SourceType.GMAIL)

        assertTrue(result is BecalmResult.Success)
        coVerify(exactly = 0) { api.getSourceSyncJob(any()) }
        coVerify(exactly = 0) { rawIngestionRepository.refreshSince(any(), any(), any()) }
        coVerify(exactly = 0) { sourceStatusRepository.recordSyncSuccess(any(), any()) }
        coVerify(exactly = 1) { processingStatusRepository.recordScanning(SourceType.GMAIL, null) }
        coVerify(exactly = 1) {
            processingStatusRepository.recordScanning(
                SourceType.GMAIL,
                ProcessingStatusMessages.SOURCE_SYNC_BACKPRESSURE_DELAYED,
            )
        }
        verify(exactly = 1) { workScheduler.enqueueSourceRelationRefresh(SourceType.GMAIL, 45L, true) }
    }

    @Test
    fun `manual gmail sync surfaces backend llm rate limit as automatic retry wait`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        coEvery { sourceConnectionRepository.refresh("user-1") } returns BecalmResult.Success(
            listOf(sourceConnection("conn-gmail", provider = "google", capability = "mail")),
        )
        coEvery { api.syncSourceConnection("conn-gmail") } returns Response.success(
            SourceSyncJobResponse(
                jobId = "job-mail-rate-limited",
                status = "retry",
                accepted = true,
                retryAfterSeconds = 60,
                provider = "gmail",
                capability = "mail",
                sourceConnectionId = "conn-gmail",
                errorCode = "llm_rate_limited_retrying",
                errorMessage = "LLM quota is temporarily limited; retrying automatically",
                stage = "retry_waiting",
            ),
        )

        val result = subject.requestManualSync(SourceType.GMAIL)

        assertTrue(result is BecalmResult.Success)
        coVerify(exactly = 0) { api.getSourceSyncJob(any()) }
        coVerify(exactly = 0) { rawIngestionRepository.refreshSince(any(), any(), any()) }
        coVerify(exactly = 0) { sourceStatusRepository.recordSyncSuccess(any(), any()) }
        coVerify(exactly = 1) {
            processingStatusRepository.recordScanning(
                SourceType.GMAIL,
                ProcessingStatusMessages.LLM_RATE_LIMITED_RETRYING,
            )
        }
        verify(exactly = 1) { workScheduler.enqueueSourceRelationRefresh(SourceType.GMAIL, 60L, true) }
    }

    @Test
    fun `manual gmail sync surfaces backend llm processing retry as automatic retry wait`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        coEvery { sourceConnectionRepository.refresh("user-1") } returns BecalmResult.Success(
            listOf(sourceConnection("conn-gmail", provider = "google", capability = "mail")),
        )
        coEvery { api.syncSourceConnection("conn-gmail") } returns Response.success(
            SourceSyncJobResponse(
                jobId = "job-mail-processing-retry",
                status = "retry",
                accepted = true,
                retryAfterSeconds = 20,
                provider = "gmail",
                capability = "mail",
                sourceConnectionId = "conn-gmail",
                errorCode = "llm_processing_retrying",
                errorMessage = "Vertex AI returned 403: permission denied",
                message = "LLM processing is temporarily unavailable; retrying automatically",
                stage = "retry_waiting",
            ),
        )

        val result = subject.requestManualSync(SourceType.GMAIL)

        assertTrue(result is BecalmResult.Success)
        coVerify(exactly = 0) { api.getSourceSyncJob(any()) }
        coVerify(exactly = 0) { rawIngestionRepository.refreshSince(any(), any(), any()) }
        coVerify(exactly = 0) { sourceStatusRepository.recordSyncSuccess(any(), any()) }
        coVerify(exactly = 1) {
            processingStatusRepository.recordScanning(
                SourceType.GMAIL,
                ProcessingStatusMessages.LLM_PROCESSING_RETRYING,
            )
        }
        verify(exactly = 1) { workScheduler.enqueueSourceRelationRefresh(SourceType.GMAIL, 45L, true) }
    }

    @Test
    fun `manual gmail sync records safe processing code for terminal llm infra failure`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        coEvery { sourceConnectionRepository.refresh("user-1") } returns BecalmResult.Success(
            listOf(sourceConnection("conn-gmail", provider = "google", capability = "mail")),
        )
        coEvery { api.syncSourceConnection("conn-gmail") } returns Response.success(
            SourceSyncJobResponse(
                jobId = "job-mail-processing-failed",
                status = "failed",
                accepted = false,
                provider = "gmail",
                capability = "mail",
                sourceConnectionId = "conn-gmail",
                errorCode = "llm_processing_failed",
                errorMessage = "Vertex AI returned 403: permission denied for internal project",
            ),
        )

        val result = subject.requestManualSync(SourceType.GMAIL)

        assertTrue(result is BecalmResult.Failure)
        coVerify(exactly = 0) { api.getSourceSyncJob(any()) }
        coVerify(exactly = 0) { rawIngestionRepository.refreshSince(any(), any(), any()) }
        coVerify(exactly = 1) {
            sourceStatusRepository.recordSyncError(
                SourceType.GMAIL,
                ProcessingStatusMessages.LLM_PROCESSING_FAILED,
                any(),
            )
        }
        coVerify(exactly = 1) {
            processingStatusRepository.recordError(
                SourceType.GMAIL,
                ProcessingStatusMessages.LLM_PROCESSING_FAILED,
            )
        }
    }

    @Test
    fun `manual gmail sync stops locally when source connection needs reauth`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        coEvery { sourceConnectionRepository.refresh("user-1") } returns BecalmResult.Success(
            listOf(sourceConnection("conn-gmail", provider = "google", capability = "mail", status = "needs_reauth")),
        )

        val result = subject.requestManualSync(SourceType.GMAIL)

        assertTrue(result is BecalmResult.Failure)
        coVerify(exactly = 0) { api.syncSourceConnection(any()) }
        coVerify(exactly = 1) {
            sourceStatusRepository.recordSyncError(
                SourceType.GMAIL,
                "needs_reauth",
                any(),
            )
        }
        coVerify(exactly = 1) {
            processingStatusRepository.recordError(
                SourceType.GMAIL,
                "needs_reauth",
            )
        }
    }

    @Test
    fun `manual gmail sync maps backend immediate reauth envelope to reconnect status`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        coEvery { sourceConnectionRepository.refresh("user-1") } returns BecalmResult.Success(
            listOf(sourceConnection("conn-gmail", provider = "google", capability = "mail", status = "connected")),
        )
        coEvery { sourceStatusRepository.refreshFromServer() } returns BecalmResult.Success(Unit)
        coEvery { api.syncSourceConnection("conn-gmail") } returns Response.error(
            409,
            """
            {
              "error": "source_connection_needs_reauth",
              "message": "Reconnect this source before syncing.",
              "retryable": false,
              "client_action": "reconnect_source"
            }
            """.trimIndent().toResponseBody("application/json".toMediaType()),
        )

        val result = subject.requestManualSync(SourceType.GMAIL)

        assertTrue(result is BecalmResult.Failure)
        coVerify(exactly = 1) { api.syncSourceConnection("conn-gmail") }
        coVerify(exactly = 0) { api.getSourceSyncJob(any()) }
        coVerify(exactly = 0) { rawIngestionRepository.refreshSince(any(), any(), any()) }
        coVerify(exactly = 2) { sourceConnectionRepository.refresh("user-1") }
        coVerify(exactly = 1) { sourceStatusRepository.refreshFromServer() }
        coVerify(exactly = 1) {
            sourceStatusRepository.recordSyncError(
                SourceType.GMAIL,
                "needs_reauth",
                any(),
            )
        }
        coVerify(exactly = 1) {
            processingStatusRepository.recordError(
                SourceType.GMAIL,
                "needs_reauth",
            )
        }
        assertEquals("validation", productAnalytics.events.last().properties["result"])
        assertEquals(false, productAnalytics.events.last().properties["retryable"])
    }

    @Test
    fun `manual backend sync tracks failed source sync without throwing`() = runTest {
        coEvery { authRepository.currentSession() } returns null

        val result = subject.requestManualSync(SourceType.GMAIL)

        assertTrue(result is BecalmResult.Failure)
        assertEquals(
            listOf(
                ProductAnalyticsEvents.SOURCE_SYNC_STARTED,
                ProductAnalyticsEvents.SOURCE_SYNC_FAILED,
            ),
            productAnalytics.events.map { it.eventName },
        )
        assertEquals("gmail", productAnalytics.events.last().properties["source_type"])
        assertEquals("backend", productAnalytics.events.last().properties["owner"])
        assertEquals("unauthorized", productAnalytics.events.last().properties["result"])
        assertEquals(false, productAnalytics.events.last().properties["retryable"])
    }

    @Test
    // spec: ING-009
    fun `manual calendar sync refreshes calendar events and generated schedule commitments`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        coEvery { sourceConnectionRepository.refresh("user-1") } returns BecalmResult.Success(
            listOf(sourceConnection("conn-calendar", provider = "google", capability = "calendar")),
        )
        coEvery { api.syncSourceConnection("conn-calendar") } returns Response.success(
            SourceSyncJobResponse(
                jobId = "job-calendar-1",
                status = "succeeded",
                accepted = false,
                synced = 1,
                provider = "google_calendar",
                capability = "calendar",
                sourceConnectionId = "conn-calendar",
            ),
        )
        coEvery { calendarEventRepository.refreshSince(userId = "user-1", since = null) } returns
            BecalmResult.Success(
                CalendarEventRepository.RefreshStats(
                    fetched = 1,
                    upserted = 1,
                    hasMore = false,
                    nextCursor = "calendar-cursor-1",
                ),
            )
        coEvery { commitmentRepository.refreshSince(userId = "user-1", since = null) } returns
            BecalmResult.Success(
                CommitmentRepository.RefreshStats(
                    fetched = 1,
                    upserted = 1,
                    hasMore = false,
                    nextCursor = "commitment-cursor-1",
                ),
            )
        coEvery { sourceEventParticipantRepository.refreshSince(userId = "user-1", sourceType = SourceType.GOOGLE_CALENDAR, since = null) } returns
            BecalmResult.Success(
                SourceEventParticipantRepository.RefreshStats(
                    fetched = 1,
                    upserted = 1,
                    hasMore = false,
                    nextCursor = "participant-cursor-1",
                ),
            )
        coEvery { commitmentParticipantRepository.refreshSince(userId = "user-1", since = null) } returns
            BecalmResult.Success(
                CommitmentParticipantRepository.RefreshStats(
                    fetched = 1,
                    upserted = 1,
                    hasMore = false,
                    nextCursor = "commitment-participant-cursor-1",
                ),
            )
        coEvery { selfIdentityRepository.refresh("user-1") } returns BecalmResult.Success(emptyList())
        coEvery { sourceStatusRepository.refreshFromServer() } returns BecalmResult.Success(Unit)

        val result = subject.requestManualSync(SourceType.GOOGLE_CALENDAR)

        assertTrue(result is BecalmResult.Success)
        coVerify(exactly = 1) { api.syncSourceConnection("conn-calendar") }
        coVerify(exactly = 1) { calendarEventRepository.refreshSince(userId = "user-1", since = null) }
        coVerify(exactly = 1) { sourceEventParticipantRepository.refreshSince(userId = "user-1", sourceType = SourceType.GOOGLE_CALENDAR, since = null) }
        coVerify(exactly = 1) { commitmentRepository.refreshSince(userId = "user-1", since = null) }
        coVerify(exactly = 1) { commitmentParticipantRepository.refreshSince(userId = "user-1", since = null) }
        coVerify(exactly = 2) { sourceConnectionRepository.refresh("user-1") }
        coVerify(exactly = 1) { selfIdentityRepository.refresh("user-1") }
        coVerify(exactly = 1) { sourceStatusRepository.refreshFromServer() }
        coVerify(exactly = 1) { sourceStatusRepository.recordSyncSuccess(SourceType.GOOGLE_CALENDAR, any()) }
        coVerify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex() }
    }

    @Test
    fun `manual calendar sync surfaces backend backpressure and schedules delayed mirror refresh`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        coEvery { sourceConnectionRepository.refresh("user-1") } returns BecalmResult.Success(
            listOf(sourceConnection("conn-calendar", provider = "google", capability = "calendar")),
        )
        coEvery { api.syncSourceConnection("conn-calendar") } returns Response.success(
            SourceSyncJobResponse(
                jobId = "job-calendar-delayed",
                status = "retry",
                accepted = true,
                retryAfterSeconds = 10,
                provider = "google_calendar",
                capability = "calendar",
                sourceConnectionId = "conn-calendar",
                errorCode = "backpressure_delayed",
                errorMessage = "Source sync accepted but delayed because worker backlog is high",
            ),
        )

        val result = subject.requestManualSync(SourceType.GOOGLE_CALENDAR)

        assertTrue(result is BecalmResult.Success)
        coVerify(exactly = 0) { calendarEventRepository.refreshSince(any(), any()) }
        coVerify(exactly = 0) { sourceStatusRepository.recordSyncSuccess(any(), any()) }
        coVerify(exactly = 1) { processingStatusRepository.recordScanning(SourceType.GOOGLE_CALENDAR, null) }
        coVerify(exactly = 1) {
            processingStatusRepository.recordScanning(
                SourceType.GOOGLE_CALENDAR,
                ProcessingStatusMessages.SOURCE_SYNC_BACKPRESSURE_DELAYED,
            )
        }
        verify(exactly = 1) { workScheduler.enqueueSourceRelationRefresh(SourceType.GOOGLE_CALENDAR, 45L, true) }
    }

    @Test
    fun `manual local sync tracks enqueued source sync`() = runTest {
        val result = subject.requestManualSync(SourceType.NAVER_IMAP)

        assertTrue(result is BecalmResult.Success)
        assertEquals(
            listOf(
                ProductAnalyticsEvents.SOURCE_SYNC_STARTED,
                ProductAnalyticsEvents.SOURCE_SYNC_COMPLETED,
            ),
            productAnalytics.events.map { it.eventName },
        )
        assertEquals("naver_imap", productAnalytics.events.last().properties["source_type"])
        assertEquals("local", productAnalytics.events.last().properties["owner"])
        assertEquals("mail", productAnalytics.events.last().properties["provider_family"])
        assertEquals("enqueued", productAnalytics.events.last().properties["result"])
        coVerify(exactly = 1) { workScheduler.enqueueExpedited(SourceType.NAVER_IMAP) }
    }

    private fun sourceConnection(
        id: String,
        provider: String,
        capability: String,
        status: String = "connected",
    ): SourceConnectionEntity = SourceConnectionEntity(
        id = id,
        userId = "user-1",
        provider = provider,
        capability = capability,
        accountIdentifier = "$id@example.com",
        accountDisplayName = id,
        ownership = "user",
        status = status,
        linkedSelfAnchorId = null,
        lastSyncAt = null,
        lastError = null,
    )

    private fun session(): SupabaseSession = SupabaseSession(
        accessToken = "access",
        refreshToken = "refresh",
        userId = "user-1",
        email = "user@example.com",
        expiresAt = Instant.parse("2026-04-28T12:00:00Z"),
    )

    private class RecordingProductAnalyticsClient : ProductAnalyticsClient {
        val events = mutableListOf<ProductAnalyticsEvent>()

        override fun track(event: ProductAnalyticsEvent) {
            events += event
        }

        override fun setUserScope(userId: String?) = Unit

        override fun resetUserScope() = Unit
    }
}

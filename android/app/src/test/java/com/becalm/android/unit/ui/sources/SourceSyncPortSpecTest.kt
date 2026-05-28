package com.becalm.android.unit.ui.sources

import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CalendarSyncResponse
import com.becalm.android.data.remote.dto.MailSyncResponse
import com.becalm.android.data.remote.dto.SourceSyncJobResponse
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.ProcessingStatusMessages
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SelfIdentityRepository
import com.becalm.android.data.repository.SourceConnectionRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.ui.sources.DefaultSourceSyncPort
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class SourceSyncPortSpecTest {

    private val authRepository: AuthRepository = mockk()
    private val api: RailwayApi = mockk()
    private val calendarEventRepository: CalendarEventRepository = mockk(relaxed = true)
    private val commitmentRepository: CommitmentRepository = mockk()
    private val commitmentParticipantRepository: CommitmentParticipantRepository = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val sourceEventParticipantRepository: SourceEventParticipantRepository = mockk()
    private val sourceConnectionRepository: SourceConnectionRepository = mockk(relaxed = true)
    private val selfIdentityRepository: SelfIdentityRepository = mockk(relaxed = true)
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val processingStatusRepository: ProcessingStatusRepository = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val productAnalytics = RecordingProductAnalyticsClient()

    private val subject = DefaultSourceSyncPort(
        authRepository = authRepository,
        apiProvider = Provider { api },
        calendarEventRepository = calendarEventRepository,
        commitmentRepository = commitmentRepository,
        commitmentParticipantRepository = commitmentParticipantRepository,
        rawIngestionRepository = rawIngestionRepository,
        sourceEventParticipantRepository = sourceEventParticipantRepository,
        sourceConnectionRepository = sourceConnectionRepository,
        selfIdentityRepository = selfIdentityRepository,
        sourceStatusRepository = sourceStatusRepository,
        processingStatusRepository = processingStatusRepository,
        workScheduler = workScheduler,
        logger = logger,
        productAnalytics = productAnalytics,
    )

    @Test
    // spec: ING-006
    fun `manual gmail sync refreshes commitments after backend sync succeeds`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        coEvery { api.syncMailSource(provider = SourceType.GMAIL) } returns Response.success(
            MailSyncResponse(synced = 1),
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
        coEvery { sourceConnectionRepository.refresh("user-1") } returns BecalmResult.Success(emptyList())
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
        coVerify(exactly = 1) { api.syncMailSource(provider = SourceType.GMAIL) }
        coVerify(exactly = 1) { rawIngestionRepository.refreshSince(userId = "user-1", sourceType = SourceType.GMAIL, since = null) }
        coVerify(exactly = 1) { sourceEventParticipantRepository.refreshSince(userId = "user-1", sourceType = SourceType.GMAIL, since = null) }
        coVerify(exactly = 1) { commitmentRepository.refreshSince(userId = "user-1", since = null) }
        coVerify(exactly = 1) { commitmentParticipantRepository.refreshSince(userId = "user-1", since = null) }
        coVerify(exactly = 1) { sourceConnectionRepository.refresh("user-1") }
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
        coEvery { api.syncMailSource(provider = SourceType.GMAIL) } returns Response.success(
            MailSyncResponse(
                synced = 0,
                jobId = "job-mail-1",
                status = "pending",
                accepted = true,
                retryAfterSeconds = 0,
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
        coEvery { sourceConnectionRepository.refresh("user-1") } returns BecalmResult.Success(emptyList())
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
        coEvery { api.syncMailSource(provider = SourceType.GMAIL) } returns Response.success(
            MailSyncResponse(
                synced = 0,
                jobId = "job-mail-delayed",
                status = "retry",
                accepted = true,
                retryAfterSeconds = 10,
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
        verify(exactly = 1) { workScheduler.enqueueSourceRelationRefresh(SourceType.GMAIL, 45L) }
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
        coEvery { calendarEventRepository.triggerServerSync() } returns BecalmResult.Success(
            CalendarSyncResponse(synced = 1),
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
        coEvery { sourceConnectionRepository.refresh("user-1") } returns BecalmResult.Success(emptyList())
        coEvery { selfIdentityRepository.refresh("user-1") } returns BecalmResult.Success(emptyList())
        coEvery { sourceStatusRepository.refreshFromServer() } returns BecalmResult.Success(Unit)

        val result = subject.requestManualSync(SourceType.GOOGLE_CALENDAR)

        assertTrue(result is BecalmResult.Success)
        coVerify(exactly = 1) { calendarEventRepository.triggerServerSync() }
        coVerify(exactly = 1) { calendarEventRepository.refreshSince(userId = "user-1", since = null) }
        coVerify(exactly = 1) { sourceEventParticipantRepository.refreshSince(userId = "user-1", sourceType = SourceType.GOOGLE_CALENDAR, since = null) }
        coVerify(exactly = 1) { commitmentRepository.refreshSince(userId = "user-1", since = null) }
        coVerify(exactly = 1) { commitmentParticipantRepository.refreshSince(userId = "user-1", since = null) }
        coVerify(exactly = 1) { sourceConnectionRepository.refresh("user-1") }
        coVerify(exactly = 1) { selfIdentityRepository.refresh("user-1") }
        coVerify(exactly = 1) { sourceStatusRepository.refreshFromServer() }
        coVerify(exactly = 1) { sourceStatusRepository.recordSyncSuccess(SourceType.GOOGLE_CALENDAR, any()) }
        coVerify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex() }
    }

    @Test
    fun `manual calendar sync surfaces backend backpressure and schedules delayed mirror refresh`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        coEvery { calendarEventRepository.triggerServerSync() } returns BecalmResult.Success(
            CalendarSyncResponse(
                synced = 0,
                jobId = "job-calendar-delayed",
                status = "retry",
                accepted = true,
                retryAfterSeconds = 10,
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
        verify(exactly = 1) { workScheduler.enqueueSourceRelationRefresh(SourceType.GOOGLE_CALENDAR, 45L) }
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

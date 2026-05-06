package com.becalm.android.integration.local.worker

import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.datastore.SyncCursorStoreImpl
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.RawIngestionRepositoryImpl
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatusRepositoryImpl
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.UploadWorker
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
class UploadWorkerLocalIntegrationTest {

    private lateinit var server: MockWebServer
    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val logger = RecordingLogger()
    private val authRepository = mockk<AuthRepository>()
    private val commitmentRepository = mockk<CommitmentRepository>()
    private val sourceEventParticipantRepository = mockk<SourceEventParticipantRepository>()
    private val commitmentParticipantRepository = mockk<CommitmentParticipantRepository>()
    private lateinit var rawRepository: RawIngestionRepositoryImpl
    private lateinit var sourceStatusRepository: SourceStatusRepositoryImpl
    private lateinit var processingPauseGate: ProcessingPauseGate
    private val session = LocalIntegrationSupport.authenticatedSession()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        rawRepository = RawIngestionRepositoryImpl(
            dao = db.rawIngestionEventDao(),
            api = LocalIntegrationSupport.railwayApi(server),
            logger = logger,
        )
        sourceStatusRepository = SourceStatusRepositoryImpl(
            cursorStore = SyncCursorStoreImpl(LocalIntegrationSupport.prefsDataStore("upload-worker-cursors")),
            userPrefs = LocalIntegrationSupport.prefsDataStore("upload-worker-user-prefs"),
            api = LocalIntegrationSupport.railwayApi(server),
            ioDispatcher = UnconfinedTestDispatcher(),
            logger = logger,
        )
        processingPauseGate = ProcessingPauseGate(
            userPrefsStore = UserPrefsStoreImpl(LocalIntegrationSupport.prefsDataStore("upload-worker-pause")),
            logger = logger,
            applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        coEvery { authRepository.currentSession() } returns session
        coEvery { commitmentRepository.findPendingSync(any(), any()) } returns emptyList()
        coEvery { commitmentRepository.refreshSince(any(), any(), any(), any(), any()) } returns
            com.becalm.android.core.result.BecalmResult.Success(
                CommitmentRepository.RefreshStats(
                    fetched = 0,
                    upserted = 0,
                    hasMore = false,
                    nextCursor = null,
                ),
            )
        coEvery { sourceEventParticipantRepository.refreshSince(any(), any(), any()) } returns
            com.becalm.android.core.result.BecalmResult.Success(
                SourceEventParticipantRepository.RefreshStats(
                    fetched = 0,
                    upserted = 0,
                    hasMore = false,
                    nextCursor = null,
                ),
            )
        coEvery { commitmentParticipantRepository.refreshSince(any(), any(), any(), any()) } returns
            com.becalm.android.core.result.BecalmResult.Success(
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
        server.shutdown()
        db.close()
    }

    @Test
    fun `SYNC-001 SYNC-003 and SYNC-004 local worker path drains raw rows and records sync success`() = runTest {
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-1",
                userId = session.userId,
                clientEventId = "client-raw-1",
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"acknowledged":1,"failed":[]}"""),
        )

        val result = newWorker().doWork()
        val stored = db.rawIngestionEventDao().findById("raw-1", session.userId)
        val status = sourceStatusRepository.observeFor(UploadWorker.SOURCE_TYPE).first {
            it.lastSyncedAt != null
        }

        assertEquals(androidx.work.ListenableWorker.Result.success().javaClass, result.javaClass)
        assertEquals("synced", stored?.syncStatus)
        assertEquals(SourceConnectionStatus.CONNECTED, status.status)
        assertEquals(null, status.errorMessage)
        assertEquals("/v1/raw_ingestion_events:batch", server.takeRequest().path)
    }

    @Test
    fun `SYNC-004 local worker path leaves row pending and records sync error on retryable server failure`() = runTest {
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-1",
                userId = session.userId,
                clientEventId = "client-raw-1",
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("""{"error":"temporary"}"""),
        )

        val result = newWorker().doWork()
        val stored = db.rawIngestionEventDao().findById("raw-1", session.userId)
        val status = sourceStatusRepository.observeFor(UploadWorker.SOURCE_TYPE).first {
            it.status == SourceConnectionStatus.ERROR
        }

        assertEquals(androidx.work.ListenableWorker.Result.retry().javaClass, result.javaClass)
        assertEquals("pending", stored?.syncStatus)
        assertEquals(0, stored?.retryCount)
        assertEquals(SourceConnectionStatus.ERROR, status.status)
        assertTrue(status.errorMessage?.contains("raw upload retry") == true)
    }

    private fun newWorker(): UploadWorker = UploadWorker(
        appContext = LocalIntegrationSupport.appContext(),
        workerParams = LocalIntegrationSupport.workerParams(),
        authRepository = authRepository,
        rawIngestionRepository = rawRepository,
        commitmentRepository = commitmentRepository,
        sourceEventParticipantRepository = sourceEventParticipantRepository,
        commitmentParticipantRepository = commitmentParticipantRepository,
        sourceStatusRepository = sourceStatusRepository,
        workScheduler = mockk<WorkScheduler>(relaxed = true),
        processingPauseGate = processingPauseGate,
        logger = logger,
    )

    private fun rawEvent(
        id: String,
        userId: String,
        clientEventId: String,
    ): RawIngestionEventEntity = RawIngestionEventEntity(
        id = id,
        userId = userId,
        clientEventId = clientEventId,
        sourceType = SourceType.GMAIL,
        timestamp = Instant.parse("2026-04-23T00:00:00Z"),
        syncStatus = "pending",
    )
}

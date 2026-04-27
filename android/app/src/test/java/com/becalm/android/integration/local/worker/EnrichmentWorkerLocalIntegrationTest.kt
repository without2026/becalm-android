package com.becalm.android.integration.local.worker

import android.Manifest
import android.app.Application
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepositoryImpl
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.RawIngestionRepositoryImpl
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.worker.EnrichmentWorker
import com.becalm.android.worker.ProcessingPauseGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import javax.inject.Provider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EnrichmentWorkerLocalIntegrationTest {

    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val logger = RecordingLogger()
    private val api = mockk<RailwayApi>(relaxed = true)
    private val rawIngestionRepository: RawIngestionRepository = RawIngestionRepositoryImpl(
        dao = db.rawIngestionEventDao(),
        api = api,
        logger = logger,
    )
    private val enrichmentRepository: PersonEnrichmentRepository = PersonEnrichmentRepositoryImpl(
        dao = db.personEnrichmentDao(),
        logger = logger,
    )
    private val authRepository = mockk<AuthRepository>()
    private val sourceStatusRepository = mockk<SourceStatusRepository>()
    private val processingPauseGate = ProcessingPauseGate(
        userPrefsStore = UserPrefsStoreImpl(LocalIntegrationSupport.prefsDataStore("enrichment-worker-pause")),
        logger = logger,
    )
    private val rawIngestionRepositoryProvider = Provider { rawIngestionRepository }
    private val enrichmentRepositoryProvider = Provider { enrichmentRepository }
    private val sourceStatusRepositoryProvider = Provider { sourceStatusRepository }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `ENR-003 worker fails closed when no authenticated session exists`() = runTest {
        coEvery { authRepository.currentSession() } returns null

        val worker = EnrichmentWorker(
            appContext = LocalIntegrationSupport.appContext(),
            workerParams = LocalIntegrationSupport.workerParams(),
            authRepository = authRepository,
            rawIngestionRepositoryProvider = rawIngestionRepositoryProvider,
            personEnrichmentRepositoryProvider = enrichmentRepositoryProvider,
            sourceStatusRepositoryProvider = sourceStatusRepositoryProvider,
            processingPauseGate = processingPauseGate,
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        assertEquals(androidx.work.ListenableWorker.Result.failure(), worker.doWork())
    }

    @Test
    fun `ENR-005 and ENR-006 worker skips fresh rows and still records local sync success`() = runTest {
        val context = LocalIntegrationSupport.appContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)
        coEvery { authRepository.currentSession() } returns LocalIntegrationSupport.authenticatedSession(userId = USER_ID)
        coEvery {
            sourceStatusRepository.recordSyncSuccess(EnrichmentWorker.SOURCE_TYPE_ENRICHMENT, any())
        } returns BecalmResult.Success(Unit)

        db.rawIngestionEventDao().insert(
            RawIngestionEventEntity(
                id = "raw-1",
                userId = USER_ID,
                clientEventId = "client-1",
                sourceType = SourceType.GMAIL,
                sourceRef = "ref-1",
                personRef = "friend@example.com",
                eventTitle = "hello",
                eventSnippet = "hi",
                timestamp = Instant.parse("2026-04-23T01:00:00Z"),
                syncStatus = "synced",
            ),
        )
        val freshSyncedAt = Instant.parse("2026-04-22T10:00:00Z")
        enrichmentRepository.upsert(
            PersonEnrichmentEntity(
                personRef = "friend@example.com",
                displayName = "Friend",
                lastSyncedAt = freshSyncedAt,
            ),
        )

        val worker = EnrichmentWorker(
            appContext = context,
            workerParams = LocalIntegrationSupport.workerParams(),
            authRepository = authRepository,
            rawIngestionRepositoryProvider = rawIngestionRepositoryProvider,
            personEnrichmentRepositoryProvider = enrichmentRepositoryProvider,
            sourceStatusRepositoryProvider = sourceStatusRepositoryProvider,
            processingPauseGate = processingPauseGate,
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
        assertEquals(freshSyncedAt, enrichmentRepository.findByPersonRef("friend@example.com")?.lastSyncedAt)
        coVerify(exactly = 1) {
            sourceStatusRepository.recordSyncSuccess(EnrichmentWorker.SOURCE_TYPE_ENRICHMENT, any())
        }
        assertNull(enrichmentRepository.findByPersonRef("missing@example.com"))
    }

    @Test
    fun `ENR-004 worker fails closed when contacts permission is absent`() = runTest {
        val context = LocalIntegrationSupport.appContext()
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CONTACTS)
        coEvery { authRepository.currentSession() } returns LocalIntegrationSupport.authenticatedSession(userId = USER_ID)

        val worker = EnrichmentWorker(
            appContext = context,
            workerParams = LocalIntegrationSupport.workerParams(),
            authRepository = authRepository,
            rawIngestionRepositoryProvider = rawIngestionRepositoryProvider,
            personEnrichmentRepositoryProvider = enrichmentRepositoryProvider,
            sourceStatusRepositoryProvider = sourceStatusRepositoryProvider,
            processingPauseGate = processingPauseGate,
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        assertEquals(androidx.work.ListenableWorker.Result.failure(), worker.doWork())
    }

    private companion object {
        private const val USER_ID = "user-1"
    }
}

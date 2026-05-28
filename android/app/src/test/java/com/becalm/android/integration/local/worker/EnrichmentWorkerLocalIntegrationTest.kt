package com.becalm.android.integration.local.worker

import android.Manifest
import android.app.Application
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepositoryImpl
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.worker.ContactBaselineScanRow
import com.becalm.android.worker.EnrichmentWorker
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.WorkScheduler
import com.becalm.android.worker.buildContactBaselineEntities
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import javax.inject.Provider
import kotlin.time.Duration.Companion.days
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
    private val enrichmentRepository: PersonEnrichmentRepository = PersonEnrichmentRepositoryImpl(
        dao = db.personEnrichmentDao(),
        logger = logger,
    )
    private val authRepository = mockk<AuthRepository>()
    private val sourceStatusRepository = mockk<SourceStatusRepository>()
    private val workScheduler = mockk<WorkScheduler>(relaxed = true)
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processingPauseGate = ProcessingPauseGate(
        userPrefsStore = UserPrefsStoreImpl(LocalIntegrationSupport.prefsDataStore("enrichment-worker-pause")),
        logger = logger,
        applicationScope = applicationScope,
    )
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
            personEnrichmentRepositoryProvider = enrichmentRepositoryProvider,
            sourceStatusRepositoryProvider = sourceStatusRepositoryProvider,
            processingPauseGate = processingPauseGate,
            workScheduler = workScheduler,
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        assertEquals(androidx.work.ListenableWorker.Result.failure(), worker.doWork())
    }

    @Test
    fun `ENR-005 baseline builder registers contact phone email and display-name rows`() {
        val now = Instant.parse("2026-04-24T00:00:00Z")

        val rows = buildContactBaselineEntities(
            rows = listOf(
                ContactBaselineScanRow(
                    contactId = "contact-42",
                    displayName = "김민홍",
                    email = "MINHONG+work@example.com",
                    phone = "010-1234-5678",
                ),
            ),
            now = now,
        )

        assertEquals(
            setOf(
                "+821012345678",
                "minhong@example.com",
                "김민홍",
            ),
            rows.map { it.personRef }.toSet(),
        )
        assertTrue(rows.all { it.displayName == "김민홍" && it.sourceContactId == "contact-42" && it.lastSyncedAt == now })
    }

    @Test
    fun `ENR-006 worker replaces app-generated enrichment cache and records local sync success`() = runTest {
        val context = LocalIntegrationSupport.appContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)
        coEvery { authRepository.currentSession() } returns LocalIntegrationSupport.authenticatedSession(userId = USER_ID)
        coEvery {
            sourceStatusRepository.recordSyncSuccess(EnrichmentWorker.SOURCE_TYPE_ENRICHMENT, any())
        } returns BecalmResult.Success(Unit)

        val freshSyncedAt = Instant.fromEpochMilliseconds(
            Clock.System.now().minus(1.days).toEpochMilliseconds(),
        )
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
            personEnrichmentRepositoryProvider = enrichmentRepositoryProvider,
            sourceStatusRepositoryProvider = sourceStatusRepositoryProvider,
            processingPauseGate = processingPauseGate,
            workScheduler = workScheduler,
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, db.personEnrichmentDao().countAll())
        coVerify(exactly = 1) {
            sourceStatusRepository.recordSyncSuccess(EnrichmentWorker.SOURCE_TYPE_ENRICHMENT, any())
        }
        verify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex(initialDelaySeconds = 0L) }
        assertNull(enrichmentRepository.findByPersonRef("friend@example.com"))
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
            personEnrichmentRepositoryProvider = enrichmentRepositoryProvider,
            sourceStatusRepositoryProvider = sourceStatusRepositoryProvider,
            processingPauseGate = processingPauseGate,
            workScheduler = workScheduler,
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        assertEquals(androidx.work.ListenableWorker.Result.failure(), worker.doWork())
    }

    private companion object {
        private const val USER_ID = "user-1"
    }
}

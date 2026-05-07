package com.becalm.android.integration.local.ui.today

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.RawIngestionRepositoryImpl
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.ui.today.ColdSyncRouteResolver
import com.becalm.android.ui.today.ColdSyncStartupRoute
import com.becalm.android.ui.today.ColdSyncStartupSnapshot
import com.becalm.android.ui.today.DefaultColdSyncLifecycleCoordinator
import com.becalm.android.ui.today.DefaultColdSyncStage2ProgressPort
import com.becalm.android.worker.WorkScheduler
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ColdSyncLocalIntegrationTest {

    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val logger = RecordingLogger()
    private val api = mockk<RailwayApi>(relaxed = true)

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `COLD-003 COLD-005 COLD-007 and COLD-008 complete stage1 persists state and exposes stage2 resume banner`() = runTest {
        val userPrefsStore = UserPrefsStoreImpl(
            dataStore = LocalIntegrationSupport.prefsDataStore("cold-sync-complete"),
        )
        userPrefsStore.setCurrentUserId("user-1")
        val workScheduler = RecordingWorkScheduler()
        val sourceStatusRepository = FakeSourceStatusRepository()
        val rawIngestionRepository = RawIngestionRepositoryImpl(
            dao = db.rawIngestionEventDao(),
            api = api,
            logger = logger,
        )
        val coordinator = DefaultColdSyncLifecycleCoordinator(userPrefsStore, workScheduler)
        val progressPort = DefaultColdSyncStage2ProgressPort(
            userPrefsStore = userPrefsStore,
            sourceStatusRepository = sourceStatusRepository,
            rawIngestionRepository = rawIngestionRepository,
            workScheduler = workScheduler,
        )
        val resolver = ColdSyncRouteResolver()
        val completedAt = Instant.parse("2026-04-23T01:00:00Z")

        val result = coordinator.completeStage1(completedAt)
        assertTrue(result is BecalmResult.Success)
        assertTrue(userPrefsStore.observeOnboardingCompleted().first())
        assertEquals(completedAt.toEpochMilliseconds(), userPrefsStore.observeColdSyncStage1CompletedAt().first())
        assertFalse(userPrefsStore.observeColdSyncStage1Deferred().first())
        assertEquals(1, workScheduler.stage2EnqueueCount)
        db.rawIngestionEventDao().insertAll(
            listOf(
                rawEvent("gmail-1", SourceType.GMAIL, "2026-04-20T01:00:00Z"),
                rawEvent("gmail-2", SourceType.GMAIL, "2026-04-21T01:00:00Z"),
                rawEvent("outlook-1", SourceType.OUTLOOK_MAIL, "2026-04-22T01:00:00Z"),
                rawEvent("voice-1", SourceType.VOICE, "2026-04-23T01:00:00Z"),
                rawEvent("call-1", SourceType.CALL_RECORDING, "2026-04-23T02:00:00Z"),
            ),
        )
        sourceStatusRepository.emit(
            status(SourceType.GMAIL, SourceConnectionStatus.CONNECTED),
            status(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.ERROR),
            status(SourceType.VOICE, SourceConnectionStatus.CONNECTED),
        )

        val stage2State = progressPort.observeState().first()
        assertTrue(stage2State.bannerVisible)
        assertTrue(stage2State.canDefer)
        assertFalse(stage2State.deferred)
        assertEquals(60, stage2State.progressPercent)
        assertEquals(3, stage2State.emailBackfillProcessed)
        assertEquals(3, stage2State.emailBackfillTotal)
        assertEquals(2, stage2State.voiceScanProcessed)
        assertEquals(2, stage2State.voiceScanTotal)
        assertEquals(
            ColdSyncStartupRoute.SHOW_TODAY_WITH_STAGE2_RESUME_BANNER,
            resolver.resolve(
                ColdSyncStartupSnapshot(
                    onboardingCompleted = userPrefsStore.observeOnboardingCompleted().first(),
                    stage1CompletedAt = userPrefsStore.observeColdSyncStage1CompletedAt().first(),
                    stage2CompletedAt = userPrefsStore.observeColdSyncStage2CompletedAt().first(),
                    stage2Deferred = userPrefsStore.observeColdSyncStage2Deferred().first(),
                ),
            ),
        )

        val deferred = progressPort.deferStage2(Instant.parse("2026-04-23T02:00:00Z"))
        assertTrue(deferred is BecalmResult.Success)
        assertEquals(1, workScheduler.stage2CancelCount)

        val deferredState = progressPort.observeState().first()
        assertTrue(deferredState.bannerVisible)
        assertTrue(deferredState.deferred)
        assertFalse(deferredState.canDefer)
        assertEquals(
            ColdSyncStartupRoute.SHOW_TODAY_IDLE,
            resolver.resolve(
                ColdSyncStartupSnapshot(
                    onboardingCompleted = userPrefsStore.observeOnboardingCompleted().first(),
                    stage1CompletedAt = userPrefsStore.observeColdSyncStage1CompletedAt().first(),
                    stage2CompletedAt = userPrefsStore.observeColdSyncStage2CompletedAt().first(),
                    stage2Deferred = userPrefsStore.observeColdSyncStage2Deferred().first(),
                ),
            ),
        )
    }

    @Test
    fun `COLD-006 defer stage1 persists onboarding completion and schedules deferred work`() = runTest {
        val userPrefsStore = UserPrefsStoreImpl(
            dataStore = LocalIntegrationSupport.prefsDataStore("cold-sync-defer"),
        )
        userPrefsStore.setCurrentUserId("user-1")
        val workScheduler = RecordingWorkScheduler()
        val coordinator = DefaultColdSyncLifecycleCoordinator(userPrefsStore, workScheduler)
        val deferredAt = Instant.parse("2026-04-23T03:00:00Z")

        val result = coordinator.deferStage1(deferredAt)
        assertTrue(result is BecalmResult.Success)
        assertTrue(userPrefsStore.observeOnboardingCompleted().first())
        assertTrue(userPrefsStore.observeColdSyncStage1Deferred().first())
        assertEquals(deferredAt.toEpochMilliseconds(), userPrefsStore.observeColdSyncDeferredAt().first())
        assertEquals(1, workScheduler.deferredStage1EnqueueCount)
    }

    private fun status(
        sourceType: String,
        state: SourceConnectionStatus,
    ): SourceStatus = SourceStatus(
        sourceType = sourceType,
        status = state,
        lastSyncedAt = null,
        errorMessage = null,
    )

    private class FakeSourceStatusRepository : SourceStatusRepository {
        private val statuses = MutableStateFlow<Map<String, SourceStatus>>(emptyMap())

        fun emit(vararg values: SourceStatus) {
            statuses.value = values.associateBy { it.sourceType }
        }

        override fun observeAll(): Flow<List<SourceStatus>> = statuses.map { it.values.toList() }
        override fun observeSources(): Flow<Map<String, SourceStatus>> = statuses
        override fun observeFor(sourceType: String): Flow<SourceStatus> = statuses.map { it.getValue(sourceType) }
        override suspend fun refreshFromServer(): BecalmResult<Unit> = BecalmResult.Success(Unit)
        override suspend fun recordSyncSuccess(sourceType: String, at: Instant): BecalmResult<Unit> = BecalmResult.Success(Unit)
        override suspend fun recordSyncError(sourceType: String, error: String, at: Instant): BecalmResult<Unit> = BecalmResult.Success(Unit)
        override suspend fun recordSyncStart(sourceType: String): BecalmResult<Unit> = BecalmResult.Success(Unit)
        override suspend fun clear(sourceType: String): BecalmResult<Unit> = BecalmResult.Success(Unit)
        override suspend fun clearAll(): BecalmResult<Unit> = BecalmResult.Success(Unit)
    }

    private class RecordingWorkScheduler : WorkScheduler {
        var stage2EnqueueCount: Int = 0
        var stage2CancelCount: Int = 0
        var deferredStage1EnqueueCount: Int = 0

        override fun enqueueExpedited(sourceKey: String) = Unit
        override fun enqueuePeriodic(sourceKey: String) = Unit
        override fun enqueueUpload(attempt: Int) = Unit
        override fun scheduleUploadRedundancy() = Unit
        override fun scheduleBackendMailSync() = Unit
        override fun enqueueEnrichment() = Unit
        override fun enqueuePersonInteractionIndex(initialDelaySeconds: Long) = Unit
        override fun enqueueProfileMemory(personId: String, initialDelaySeconds: Long) = Unit
        override fun scheduleEnrichmentSweep() = Unit
        override fun cancelEnrichmentSweep() = Unit
        override fun enqueueVoiceUpload(rawEventId: String, audioUri: String) = Unit
        override fun enqueueMeetingTranscriptUpload(rawEventId: String) = Unit
        override fun enqueueMessageScreenshotUpload(rawEventId: String) = Unit
        override fun enqueueManualTextUpload(rawEventId: String) = Unit
        override fun enqueueVoiceUploadWithDelay(
            rawEventId: String,
            audioUri: String,
            initialDelaySec: Long,
            rateLimitedAttempt: Int,
        ) = Unit

        override fun scheduleRetentionSweep() = Unit
        override fun scheduleOverdueSweep() = Unit

        override fun enqueueDeferredColdSyncStage1() {
            deferredStage1EnqueueCount += 1
        }

        override fun enqueueColdSyncStage2() {
            stage2EnqueueCount += 1
        }

        override fun cancelColdSyncStage2() {
            stage2CancelCount += 1
        }

        override fun cancelVoiceUpload(rawEventId: String) = Unit
        override fun cancelMeetingTranscriptUpload(rawEventId: String) = Unit
        override fun cancelMessageScreenshotUpload(rawEventId: String) = Unit
        override fun cancelManualTextUpload(rawEventId: String) = Unit
        override fun cancelAll() = Unit
        override fun cleanupLegacyWorkNames() = Unit
    }

    private fun rawEvent(
        id: String,
        sourceType: String,
        timestamp: String,
    ): RawIngestionEventEntity = RawIngestionEventEntity(
        id = id,
        userId = "user-1",
        sourceType = sourceType,
        sourceRef = "source-ref:$id",
        clientEventId = "client:$id",
        eventTitle = id,
        eventSnippet = null,
        timestamp = Instant.parse(timestamp),
        syncStatus = "pending",
    )
}

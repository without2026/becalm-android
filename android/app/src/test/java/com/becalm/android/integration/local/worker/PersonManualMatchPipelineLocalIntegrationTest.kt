package com.becalm.android.integration.local.worker

import androidx.work.ListenableWorker
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.PersonManualMatchRepositoryImpl
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.worker.PersonInteractionIndexWorker
import com.becalm.android.worker.WorkScheduler
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
class PersonManualMatchPipelineLocalIntegrationTest {

    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val userPrefsStore = UserPrefsStoreImpl(
        LocalIntegrationSupport.prefsDataStore("person-manual-match-prefs"),
    )
    private val logger = RecordingLogger()
    private val scheduler = RecordingWorkScheduler()
    private val dispatcher = UnconfinedTestDispatcher()

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `manual match turns unmatched row into interaction and nickname resolves future rows`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(rawEvent(id = "raw-unmatched-1", snippet = "예약 시간이 확정되었습니다."))

        assertEquals(ListenableWorker.Result.success().javaClass, newWorker().doWork().javaClass)
        assertEquals(
            listOf("raw:raw-unmatched-1"),
            db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 10).map { it.sourceRef },
        )
        logger.clear()
        assertEquals(ListenableWorker.Result.success().javaClass, newWorker().doWork().javaClass)
        assertTrue(logger.entries.any { "person index unchanged sources=1" in it.message })

        val repository = PersonManualMatchRepositoryImpl(
            personIndexDao = db.personIndexDao(),
            workScheduler = scheduler,
            logger = logger,
            ioDispatcher = dispatcher,
        )
        val result = repository.matchInteraction(
            userId = USER_ID,
            sourceType = SourceType.GMAIL,
            sourceRef = "raw:raw-unmatched-1",
            interactionKind = "email",
            personAnchor = NAVER_EMAIL,
            nickname = NAVER_NICKNAME,
        )
        assertTrue(result is BecalmResult.Success)
        assertEquals(1, scheduler.personIndexEnqueueCount)

        assertEquals(ListenableWorker.Result.success().javaClass, newWorker().doWork().javaClass)
        assertTrue(db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 10).isEmpty())

        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, NAVER_EMAIL)).personId
        val firstInteractions = db.personIndexDao()
            .observeInteractionsForPerson(USER_ID, personId, limit = 10)
            .first()
        assertEquals(listOf("raw:raw-unmatched-1"), firstInteractions.map { it.sourceRef })

        db.rawIngestionEventDao().insert(rawEvent(id = "raw-future-1", snippet = "$NAVER_NICKNAME 확인 메일입니다."))
        assertEquals(ListenableWorker.Result.success().javaClass, newWorker().doWork().javaClass)

        val futureInteractions = db.personIndexDao()
            .observeInteractionsForPerson(USER_ID, personId, limit = 10)
            .first()
        assertEquals(
            setOf("raw:raw-unmatched-1", "raw:raw-future-1"),
            futureInteractions.map { it.sourceRef }.toSet(),
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

    private fun rawEvent(id: String, snippet: String): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = id,
            userId = USER_ID,
            clientEventId = "client-$id",
            sourceType = SourceType.GMAIL,
            sourceRef = "gmail-$id",
            personRef = null,
            eventTitle = "예약 알림",
            eventSnippet = snippet,
            folder = "INBOX",
            timestamp = Instant.parse("2026-04-29T00:00:00Z"),
            syncStatus = "synced",
        )

    private class RecordingWorkScheduler : WorkScheduler {
        var personIndexEnqueueCount: Int = 0
            private set

        override fun enqueuePersonInteractionIndex(initialDelaySeconds: Long) {
            personIndexEnqueueCount += 1
        }

        override fun enqueueExpedited(sourceKey: String) = Unit
        override fun enqueuePeriodic(sourceKey: String) = Unit
        override fun enqueueUpload(attempt: Int) = Unit
        override fun scheduleUploadRedundancy() = Unit
        override fun scheduleBackendMailSync() = Unit
        override fun enqueueEnrichment() = Unit
        override fun scheduleEnrichmentSweep() = Unit
        override fun cancelEnrichmentSweep() = Unit
        override fun enqueueVoiceUpload(rawEventId: String, audioUri: String) = Unit
        override fun enqueueVoiceUploadWithDelay(
            rawEventId: String,
            audioUri: String,
            initialDelaySec: Long,
            rateLimitedAttempt: Int,
        ) = Unit
        override fun scheduleRetentionSweep() = Unit
        override fun scheduleOverdueSweep() = Unit
        override fun enqueueDeferredColdSyncStage1() = Unit
        override fun enqueueColdSyncStage2() = Unit
        override fun cancelColdSyncStage2() = Unit
        override fun cancelVoiceUpload(rawEventId: String) = Unit
        override fun cancelAll() = Unit
        override fun cleanupLegacyWorkNames() = Unit
    }

    private companion object {
        private const val USER_ID = "user-1"
        private const val NAVER_EMAIL = "noreply@navercorp.com"
        private const val NAVER_NICKNAME = "네이버 예약팀"
    }
}

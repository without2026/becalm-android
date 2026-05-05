package com.becalm.android.integration.local.worker

import androidx.work.ListenableWorker
import com.becalm.android.core.util.FakeClock
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.RetentionSweepWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RetentionSweepWorkerLocalIntegrationTest {

    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val clock = FakeClock(Instant.parse("2026-04-22T00:00:00Z"))
    private val logger = RecordingLogger()
    private val userPrefsStore = UserPrefsStoreImpl(
        LocalIntegrationSupport.prefsDataStore("retention-sweep-user-prefs"),
    )
    private val processingPauseGate = ProcessingPauseGate(
        userPrefsStore = userPrefsStore,
        logger = logger,
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `EMAIL-006 retention sweep no longer prunes local source originals`() = runTest {
        val userId = "user-a"
        userPrefsStore.setCurrentUserId(userId)
        val expiredRaw = rawEvent("raw-expired", userId, Instant.parse("2026-03-20T00:00:00Z"), "synced", SourceType.GMAIL)
        val retainedPending = rawEvent("raw-pending", userId, Instant.parse("2026-03-20T00:00:00Z"), "pending", SourceType.GMAIL)
        val retainedAwaiting = rawEvent("raw-awaiting", userId, Instant.parse("2026-03-20T00:00:00Z"), "awaiting_consent", SourceType.VOICE)
        val retainedRecent = rawEvent("raw-recent", userId, Instant.parse("2026-04-10T00:00:00Z"), "synced", SourceType.GMAIL)
        db.rawIngestionEventDao().insertAll(listOf(expiredRaw, retainedPending, retainedAwaiting, retainedRecent))
        db.emailBodyDao().insert(emailBody("email-expired", expiredRaw.id, expiredRaw.timestamp))
        db.emailBodyDao().insert(emailBody("email-pending", retainedPending.id, retainedPending.timestamp))
        db.commitmentDao().insert(manualCommitment(userId))
        db.calendarEventDao().insertAll(listOf(calendarEvent(userId)))

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertEquals(0, result.outputData.getInt(RetentionSweepWorker.KEY_EMAIL_DELETED, -1))
        assertEquals(0, result.outputData.getInt(RetentionSweepWorker.KEY_RAW_DELETED, -1))
        assertNotNull(db.rawIngestionEventDao().findById(expiredRaw.id, userId))
        assertNotNull(db.emailBodyDao().getByRawEventId(expiredRaw.id))
        assertNotNull(db.rawIngestionEventDao().findById(retainedPending.id, userId))
        assertNotNull(db.rawIngestionEventDao().findById(retainedAwaiting.id, userId))
        assertNotNull(db.rawIngestionEventDao().findById(retainedRecent.id, userId))
        assertNotNull(db.emailBodyDao().getByRawEventId(retainedPending.id))
        assertNotNull(db.commitmentDao().findById("manual-commitment"))
        assertEquals(
            1,
            db.calendarEventDao().observeInRange(
                userId = userId,
                rangeStart = Instant.parse("2026-01-01T00:00:00Z"),
                rangeEnd = Instant.parse("2026-12-31T00:00:00Z"),
            ).first().size,
        )
    }

    private fun newWorker(): RetentionSweepWorker = RetentionSweepWorker(
        appContext = LocalIntegrationSupport.appContext(),
        workerParams = LocalIntegrationSupport.workerParams(),
        rawIngestionEventDao = db.rawIngestionEventDao(),
        emailBodyDao = db.emailBodyDao(),
        clock = clock,
        db = db,
        userPrefsStore = userPrefsStore,
        processingPauseGate = processingPauseGate,
        logger = logger,
    )

    private fun rawEvent(
        id: String,
        userId: String,
        timestamp: Instant,
        syncStatus: String,
        sourceType: String,
    ): RawIngestionEventEntity = RawIngestionEventEntity(
        id = id,
        userId = userId,
        clientEventId = "client-$id",
        sourceType = sourceType,
        timestamp = timestamp,
        syncStatus = syncStatus,
    )

    private fun emailBody(
        id: String,
        rawEventId: String,
        receivedAt: Instant,
    ): EmailBodyEntity = EmailBodyEntity(
        id = id,
        rawEventId = rawEventId,
        providerMessageId = "provider-$id",
        folder = "INBOX",
        receivedAt = receivedAt,
    )

    private fun manualCommitment(userId: String): CommitmentEntity = CommitmentEntity(
        id = "manual-commitment",
        userId = userId,
        direction = "give",
        counterpartyRaw = null,
        counterpartyRef = null,
        title = "Call back",
        description = null,
        quote = "Please call me back.",
        sourceEventTitle = null,
        sourceEventOccurredAt = Instant.parse("2026-03-01T00:00:00Z"),
        dueAt = null,
        dueHint = null,
        sourceType = SourceType.MANUAL,
        sourceRef = null,
        createdAt = Instant.parse("2026-03-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-03-01T00:00:00Z"),
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
    )

    private fun calendarEvent(userId: String): CalendarEventEntity = CalendarEventEntity(
        id = "calendar-1",
        userId = userId,
        sourceType = SourceType.GOOGLE_CALENDAR,
        sourceRef = "google-1",
        title = "Meeting",
        startAt = Instant.parse("2026-03-01T01:00:00Z"),
        endAt = Instant.parse("2026-03-01T02:00:00Z"),
        attendeesRaw = null,
        syncStatus = "synced",
    )
}

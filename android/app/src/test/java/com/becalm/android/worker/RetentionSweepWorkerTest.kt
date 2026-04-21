package com.becalm.android.worker

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.FOLDER_INBOX
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.days

/**
 * Unit tests for [RetentionSweepWorker] — the 30-day rolling-window pruning
 * worker mandated by EMAIL-006 (`.spec/email-pipeline.spec.yml:58-64`) and the
 * cross-module invariant at `.spec/data-ingestion.spec.yml:160`.
 *
 * The worker uses `@AssistedInject` (Hilt) in production. Rather than wire a
 * `HiltWorkerFactory` for unit tests — which would require `androidx.work:work-testing`
 * in `testImplementation` (currently only declared in `androidTestImplementation`,
 * see `android/app/build.gradle.kts`) — we construct the worker directly with
 * relaxed MockK [WorkerParameters] and invoke [RetentionSweepWorker.doWork] as a
 * suspend function. This mirrors the existing [UploadWorkerTest] and
 * [EnrichmentWorkerTest] patterns and keeps the sweep's invariants under a
 * fast in-memory Room integration (real DB, fake clock).
 *
 * Coverage (plan §5.2 / §6):
 * - [sweepDeletesOnlySyncedRowsOlderThan30Days] — 4-row matrix: synced/old,
 *   synced/new, pending/old, synced/old-with-body.
 * - [sweepIsIdempotent] — a second run against the already-pruned state records
 *   zero deletions.
 * - [sweepPreservesCommitmentsAndCalendarEvents] — the sweep must never touch
 *   these two tables (separate lifecycle per spec invariant 160).
 * - [sweepHandlesEmptyDb] — empty DB returns [Result.success] with zero counts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RetentionSweepWorkerTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private lateinit var db: BeCalmDatabase
    private lateinit var worker: RetentionSweepWorker

    private val logger = RecordingLogger()

    /**
     * Wall clock fixed at `now` — every row's `timestamp` is expressed relative to
     * this snapshot so the assertion is independent of real wall-time drift.
     */
    private val now: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val fakeClock: Clock = object : Clock {
        override fun nowInstant(): Instant = now
    }

    private val userId = "user-retention-0001"

    @Before
    fun setUp() {
        val context: Application = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, BeCalmDatabase::class.java)
            .allowMainThreadQueries()
            // Route Room's internal coroutine dispatchers through the test scheduler
            // so every suspend DAO call is controlled by [dispatcher] — matches the
            // pattern used by [EmailBodyDaoTest].
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .build()

        val workerParams: WorkerParameters = mockk(relaxed = true)
        every { workerParams.runAttemptCount } returns 0

        worker = RetentionSweepWorker(
            appContext = context as Context,
            workerParams = workerParams,
            rawIngestionEventDao = db.rawIngestionEventDao(),
            emailBodyDao = db.emailBodyDao(),
            clock = fakeClock,
            db = db,
            logger = logger,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ─── Test 1 — core invariant ───────────────────────────────────────────────

    @Test
    fun sweepDeletesOnlySyncedRowsOlderThan30Days() = scope.runTest {
        // A: synced + 31 days old → eligible for deletion.
        insertRawEvent(id = "A", timestamp = now - 31.days, syncStatus = "synced")
        // B: synced + 29 days old → inside window, kept.
        insertRawEvent(id = "B", timestamp = now - 29.days, syncStatus = "synced")
        // C: pending + 40 days old → pending rows are NEVER pruned per spec 151.
        insertRawEvent(id = "C", timestamp = now - 40.days, syncStatus = "pending")
        // D: synced + 31 days old with an attached email_body row → both must go.
        insertRawEvent(id = "D", timestamp = now - 31.days, syncStatus = "synced")
        db.emailBodyDao().insert(emailBody(id = "eb-D", rawEventId = "D"))

        val result = worker.doWork()

        // Result + counts
        assertTrue("expected success result, got $result", result is Result.Success)
        val outputData = (result as Result.Success).outputData
        assertEquals(
            "email_deleted must count the one body row co-pruned with raw A/D",
            1,
            outputData.getInt(RetentionSweepWorker.KEY_EMAIL_DELETED, -1),
        )
        assertEquals(
            "raw_deleted must count the two synced-old rows (A and D)",
            2,
            outputData.getInt(RetentionSweepWorker.KEY_RAW_DELETED, -1),
        )

        // Surviving rows
        val rawDao = db.rawIngestionEventDao()
        assertNull("A (synced + old) must be deleted", rawDao.findById("A", userId))
        assertNotNull("B (synced + recent) must survive", rawDao.findById("B", userId))
        assertNotNull("C (pending + old) must survive — spec 151", rawDao.findById("C", userId))
        assertNull("D (synced + old) must be deleted", rawDao.findById("D", userId))

        // Body rows
        assertEquals(
            "email_body count must match raw_event count for surviving synced rows",
            0,
            countRows("email_body"),
        )
        assertEquals(
            "raw_ingestion_events count must reflect the two survivors (B, C)",
            2,
            countRows("raw_ingestion_events"),
        )
    }

    // ─── Test 2 — idempotency ──────────────────────────────────────────────────

    @Test
    fun sweepIsIdempotent() = scope.runTest {
        insertRawEvent(id = "A", timestamp = now - 31.days, syncStatus = "synced")
        insertRawEvent(id = "D", timestamp = now - 31.days, syncStatus = "synced")
        db.emailBodyDao().insert(emailBody(id = "eb-D", rawEventId = "D"))

        val first = worker.doWork()
        assertTrue(first is Result.Success)
        val firstOutput = (first as Result.Success).outputData
        assertEquals(1, firstOutput.getInt(RetentionSweepWorker.KEY_EMAIL_DELETED, -1))
        assertEquals(2, firstOutput.getInt(RetentionSweepWorker.KEY_RAW_DELETED, -1))

        // Second run — nothing left to prune.
        val second = worker.doWork()
        assertTrue(second is Result.Success)
        val secondOutput = (second as Result.Success).outputData
        assertEquals(
            "second sweep must find no remaining bodies",
            0,
            secondOutput.getInt(RetentionSweepWorker.KEY_EMAIL_DELETED, -1),
        )
        assertEquals(
            "second sweep must find no remaining eligible raw rows",
            0,
            secondOutput.getInt(RetentionSweepWorker.KEY_RAW_DELETED, -1),
        )
    }

    // ─── Test 3 — commitments / calendar events must never be swept ────────────

    @Test
    fun sweepPreservesCommitmentsAndCalendarEvents() = scope.runTest {
        // Seed one eligible raw row so the sweep has work to do, proving the transaction
        // runs but stops exactly at the two retention-target tables.
        insertRawEvent(id = "A", timestamp = now - 31.days, syncStatus = "synced")

        // A commitment whose source_event_occurred_at is 40 days old — still must survive
        // because commitments follow EDIT-006 soft-delete / user-driven lifecycle per
        // data-ingestion invariant line 160.
        db.commitmentDao().insert(
            makeCommitment(
                id = "c-1",
                occurredAt = now - 40.days,
            ),
        )

        // A calendar event whose start_at is 40 days in the past — again, distinct
        // lifecycle.
        db.calendarEventDao().insertAll(
            listOf(
                makeCalendarEvent(
                    id = "cal-1",
                    startAt = now - 40.days,
                    endAt = now - 40.days + 1.days,
                ),
            ),
        )

        val result = worker.doWork()
        assertTrue(result is Result.Success)

        assertEquals(
            "commitments must never be swept — separate lifecycle per spec invariant 160",
            1,
            countRows("commitments"),
        )
        assertEquals(
            "calendar_events must never be swept — separate lifecycle per spec invariant 160",
            1,
            countRows("calendar_events"),
        )
        // The eligible raw row itself was removed, proving the sweep still ran.
        assertEquals(0, countRows("raw_ingestion_events"))
    }

    // ─── Test 4 — empty DB ──────────────────────────────────────────────────────

    @Test
    fun sweepHandlesEmptyDb() = scope.runTest {
        val result = worker.doWork()

        assertTrue("empty DB must still yield success, got $result", result is Result.Success)
        val outputData = (result as Result.Success).outputData
        assertEquals(0, outputData.getInt(RetentionSweepWorker.KEY_EMAIL_DELETED, -1))
        assertEquals(0, outputData.getInt(RetentionSweepWorker.KEY_RAW_DELETED, -1))
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private suspend fun insertRawEvent(
        id: String,
        timestamp: Instant,
        syncStatus: String,
    ) {
        db.rawIngestionEventDao().insert(
            RawIngestionEventEntity(
                id = id,
                userId = userId,
                clientEventId = "client-$id",
                sourceType = "gmail",
                timestamp = timestamp,
                syncStatus = syncStatus,
            ),
        )
    }

    private fun emailBody(
        id: String,
        rawEventId: String,
    ): EmailBodyEntity = EmailBodyEntity(
        id = id,
        rawEventId = rawEventId,
        providerMessageId = "gmail:msg-$id",
        folder = FOLDER_INBOX,
        subject = "subject-$id",
        fromAddress = "sender@example.com",
        toAddresses = null,
        bodyPlain = "body-$id",
        bodyHtml = null,
        attachmentsMeta = null,
        rawHeaders = null,
        parseFailed = false,
        groupEmail = false,
        receivedAt = now,
    )

    private fun makeCommitment(
        id: String,
        occurredAt: Instant,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = userId,
        direction = "give",
        counterpartyRaw = "alice@example.com",
        personRef = "alice@example.com",
        title = "pay invoice",
        description = null,
        quote = "I will pay the invoice by Friday",
        sourceEventTitle = "email subject",
        sourceEventOccurredAt = occurredAt,
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        actionState = "pending",
        sourceType = "gmail",
        sourceRef = null,
        confidence = 0.9,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
        createdAt = occurredAt,
        updatedAt = occurredAt,
    )

    private fun makeCalendarEvent(
        id: String,
        startAt: Instant,
        endAt: Instant,
    ): CalendarEventEntity = CalendarEventEntity(
        id = id,
        userId = userId,
        sourceType = "google_calendar",
        sourceRef = "event-$id",
        title = "meeting-$id",
        startAt = startAt,
        endAt = endAt,
        attendeesRaw = null,
        syncStatus = "synced",
    )

    private fun countRows(table: String): Int =
        db.query("SELECT COUNT(*) FROM $table", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}

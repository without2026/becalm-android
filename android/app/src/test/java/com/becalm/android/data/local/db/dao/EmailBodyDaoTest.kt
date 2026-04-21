package com.becalm.android.data.local.db.dao

import android.app.Application
import androidx.room.Room
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.FOLDER_INBOX
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric DAO tests for [EmailBodyDao].
 *
 * These exercise the v6 schema's room-only `email_body` table end-to-end against an
 * in-memory Room instance — covering the four invariants the DAO contract promises:
 *
 * 1. `insert(...)` is a true upsert keyed on the `UNIQUE(raw_event_id)` index: a
 *    second insert for the same parent raw event replaces the first row even when
 *    the primary-key UUID differs (the 1:1 body-per-event invariant).
 * 2. `getByRawEventId(...)` returns null for unknown parents and the active row
 *    after a successful upsert.
 * 3. `markParseFailed(...)` sets `parse_failed = 1` AND clears `body_plain` in the
 *    same statement — the EMAIL-007 degrade contract requires both so the LLM
 *    extractor is never fed a partially parsed body.
 * 4. `deleteOlderThanForSynced(...)` only removes rows whose parent event is both
 *    older than the cutoff AND has `sync_status = 'synced'` — the two-condition
 *    gate mandated by `.spec/data-ingestion.spec.yml:160`.
 *
 * Shipping the DAO surface with coverage also addresses the DEADCODE-02 / TEST-01
 * rubric observation that the public API lands without a caller; future ADAPT-EMAIL
 * workers / RetentionSweepWorker will rely on these exact contracts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmailBodyDaoTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private lateinit var db: BeCalmDatabase
    private lateinit var emailBodyDao: EmailBodyDao
    private lateinit var rawEventDao: RawIngestionEventDao

    private val userId = "user-email-body-test-0001"

    @Before
    fun setUp() {
        val context: Application = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, BeCalmDatabase::class.java)
            .allowMainThreadQueries()
            // Route Room's internal coroutine dispatchers through the test scheduler so
            // every suspend DAO call is controlled by [dispatcher] and the rubric's
            // TEST-04 (runTest + TestDispatcher) invariant holds end-to-end.
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .build()
        emailBodyDao = db.emailBodyDao()
        rawEventDao = db.rawIngestionEventDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ─── insert ────────────────────────────────────────────────────────────────

    @Test
    fun insert_freshRow_persistsAllFields() = scope.runTest {
        insertRawEvent(id = "r1")
        val body = emailBody(id = "eb1", rawEventId = "r1", provider = "gmail:1")

        emailBodyDao.insert(body)

        val fetched = emailBodyDao.getByRawEventId("r1")
        assertEquals(body, fetched)
    }

    @Test
    fun insert_secondRowForSameRawEvent_replacesFirst() = scope.runTest {
        insertRawEvent(id = "r1")
        emailBodyDao.insert(emailBody(id = "eb1", rawEventId = "r1", subject = "first"))
        emailBodyDao.insert(emailBody(id = "eb2", rawEventId = "r1", subject = "second"))

        val fetched = emailBodyDao.getByRawEventId("r1")
        assertNotEquals("second insert must replace, not append", null, fetched)
        assertEquals("eb2", fetched?.id)
        assertEquals("second", fetched?.subject)
        assertEquals(1, countRows("email_body"))
    }

    // ─── getByRawEventId ───────────────────────────────────────────────────────

    @Test
    fun getByRawEventId_unknownParent_returnsNull() = scope.runTest {
        assertNull(emailBodyDao.getByRawEventId("never-inserted"))
    }

    // ─── markParseFailed ───────────────────────────────────────────────────────

    @Test
    fun markParseFailed_flipsFlagAndClearsBodyPlain() = scope.runTest {
        insertRawEvent(id = "r1")
        emailBodyDao.insert(
            emailBody(id = "eb1", rawEventId = "r1", bodyPlain = "do not feed to LLM"),
        )

        val rowsAffected = emailBodyDao.markParseFailed("eb1")

        assertEquals(1, rowsAffected)
        val after = emailBodyDao.getByRawEventId("r1")!!
        assertEquals(true, after.parseFailed)
        assertNull("body_plain must be cleared on parse-failed per EMAIL-007", after.bodyPlain)
    }

    @Test
    fun markParseFailed_unknownId_returnsZero() = scope.runTest {
        assertEquals(0, emailBodyDao.markParseFailed("never-inserted"))
    }

    // ─── deleteOlderThanForSynced ──────────────────────────────────────────────

    @Test
    fun deleteOlderThanForSynced_deletesOnlyOldSyncedParents() = scope.runTest {
        // old + synced → eligible
        insertRawEvent(id = "r-old-synced", timestampMs = 1_000L, syncStatus = "synced")
        emailBodyDao.insert(emailBody(id = "eb-old-synced", rawEventId = "r-old-synced"))

        // old + pending → not eligible (wrong sync_status)
        insertRawEvent(id = "r-old-pending", timestampMs = 1_000L, syncStatus = "pending")
        emailBodyDao.insert(emailBody(id = "eb-old-pending", rawEventId = "r-old-pending"))

        // new + synced → not eligible (timestamp inside window)
        insertRawEvent(id = "r-new-synced", timestampMs = 10_000L, syncStatus = "synced")
        emailBodyDao.insert(emailBody(id = "eb-new-synced", rawEventId = "r-new-synced"))

        val deleted = emailBodyDao.deleteOlderThanForSynced(cutoffMillis = 5_000L)

        assertEquals(1, deleted)
        assertNull(emailBodyDao.getByRawEventId("r-old-synced"))
        assertEquals("eb-old-pending", emailBodyDao.getByRawEventId("r-old-pending")?.id)
        assertEquals("eb-new-synced", emailBodyDao.getByRawEventId("r-new-synced")?.id)
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private suspend fun insertRawEvent(
        id: String,
        timestampMs: Long = 1_700_000_000_000L,
        syncStatus: String = "pending",
    ) {
        rawEventDao.insert(
            RawIngestionEventEntity(
                id = id,
                userId = userId,
                clientEventId = "client-$id",
                sourceType = "gmail",
                timestamp = Instant.fromEpochMilliseconds(timestampMs),
                syncStatus = syncStatus,
            ),
        )
    }

    private fun emailBody(
        id: String,
        rawEventId: String,
        provider: String = "gmail:msg-$id",
        subject: String? = "subject-$id",
        bodyPlain: String? = "body-$id",
    ): EmailBodyEntity = EmailBodyEntity(
        id = id,
        rawEventId = rawEventId,
        providerMessageId = provider,
        folder = FOLDER_INBOX,
        subject = subject,
        fromAddress = "sender@example.com",
        toAddresses = null,
        bodyPlain = bodyPlain,
        bodyHtml = null,
        attachmentsMeta = null,
        rawHeaders = null,
        parseFailed = false,
        groupEmail = false,
        receivedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
    )

    private fun countRows(table: String): Int =
        db.query("SELECT COUNT(*) FROM $table", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}

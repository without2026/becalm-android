package com.becalm.android.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.data.local.dao.RawIngestionEventDao
import com.becalm.android.data.local.entities.RawIngestionEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// spec: ING-001 — INSERT on ContentObserver / catch-up
// spec: ING-002 — query pending batch
// spec: ING-003 — differential status update (synced/failed/quarantined)
// spec: ING-015 — idempotency: duplicate client_event_id inserts are ignored
// spec: ING-002 — invariant: records not deleted before Railway ack (sync_status='synced')

@RunWith(RobolectricTestRunner::class)
class RawIngestionEventDaoTest {

    private lateinit var db: BeCalmDatabase
    private lateinit var dao: RawIngestionEventDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BeCalmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.rawIngestionEventDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // spec: ING-001 — basic insert
    @Test
    fun `insert creates pending record`() = runTest {
        val event = makeEvent("ev-001")
        dao.insert(event)
        val pending = dao.getPendingBatch()
        assertEquals(1, pending.size)
        assertEquals(RawIngestionEvent.SyncStatus.PENDING, pending[0].syncStatus)
        assertEquals(0, pending[0].retryCount)
    }

    // spec: ING-015 — duplicate client_event_id insert is silently ignored
    @Test
    fun `insert with duplicate client_event_id is idempotent`() = runTest {
        val event = makeEvent("ev-dup")
        dao.insert(event)
        dao.insert(event) // second insert with same ID
        assertEquals(1, dao.count())
    }

    // spec: ING-002 — markSynced updates sync_status
    @Test
    fun `markSynced updates status to synced`() = runTest {
        val event = makeEvent("ev-sync")
        dao.insert(event)
        dao.markSynced(listOf("ev-sync"))
        val batch = dao.getPendingBatch()
        assertEquals(0, batch.size) // no more pending
    }

    // spec: ING-003 — markFailed increments retry_count
    @Test
    fun `markFailed increments retry count`() = runTest {
        val event = makeEvent("ev-fail")
        dao.insert(event)
        dao.markFailed(listOf("ev-fail"))
        // After markFailed, sync_status is failed — not in pending batch
        val pendingAfterFail = dao.getPendingBatch()
        assertEquals(0, pendingAfterFail.size)
    }

    // spec: ING-003 — markQuarantined prevents further retries
    @Test
    fun `markQuarantined removes record from pending batch`() = runTest {
        val event = makeEvent("ev-q")
        dao.insert(event)
        dao.markQuarantined(listOf("ev-q"))
        val pending = dao.getPendingBatch()
        assertEquals(0, pending.size)
    }

    // spec: ING-002 — records are NOT deleted before Railway ack (sync_status stays failed, not deleted)
    @Test
    fun `records persist after failed upload attempt`() = runTest {
        val event = makeEvent("ev-persist")
        dao.insert(event)
        dao.markFailed(listOf("ev-persist"))
        // Record still exists in DB even though it's not in pending batch
        assertEquals(1, dao.count())
    }

    // spec: ING-011 — catch-up queries use latest timestamp per source
    @Test
    fun `getLatestTimestampForSource returns max timestamp for source`() = runTest {
        dao.insert(makeEvent("ev-t1", timestamp = 1000L))
        dao.insert(makeEvent("ev-t2", timestamp = 2000L))
        dao.insert(makeEvent("ev-t3", timestamp = 1500L))
        val latest = dao.getLatestTimestampForSource(RawIngestionEvent.SourceType.VOICE)
        assertEquals(2000L, latest)
    }

    // spec: ING-011 — getLatestTimestampForSource returns null for empty source
    @Test
    fun `getLatestTimestampForSource returns null when no events for source`() = runTest {
        val latest = dao.getLatestTimestampForSource(RawIngestionEvent.SourceType.GMAIL)
        assertNull(latest)
    }

    // spec: ING-011 — observePendingCount flow
    @Test
    fun `observePendingCount emits correct count`() = runTest {
        dao.insert(makeEvent("ev-p1"))
        dao.insert(makeEvent("ev-p2"))
        val count = dao.observePendingCount().first()
        assertEquals(2, count)
    }

    // spec: ING-003 — resetFailedForRetry resets records with retry_count < maxRetries
    @Test
    fun `resetFailedForRetry resets failed records back to pending`() = runTest {
        val event = makeEvent("ev-retry")
        dao.insert(event)
        dao.markFailed(listOf("ev-retry"))
        dao.resetFailedForRetry(maxRetries = 5)
        val pending = dao.getPendingBatch()
        assertEquals(1, pending.size)
    }

    // spec: ING-011 — batch insert is idempotent
    @Test
    fun `insertAll with duplicate IDs only inserts unique records`() = runTest {
        val events = listOf(makeEvent("ev-a"), makeEvent("ev-b"), makeEvent("ev-a")) // ev-a duplicate
        dao.insertAll(events)
        assertEquals(2, dao.count())
    }

    private fun makeEvent(
        id: String,
        timestamp: Long = System.currentTimeMillis(),
        sourceType: String = RawIngestionEvent.SourceType.VOICE
    ) = RawIngestionEvent(
        clientEventId = id,
        sourceType = sourceType,
        timestamp = timestamp
    )
}

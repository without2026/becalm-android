package com.becalm.android.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.data.local.dao.CommitmentDao
import com.becalm.android.data.local.entities.Commitment
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

// spec: CMT-001..CMT-010 — commitment lifecycle
// spec: CMT-005..CMT-007 — action_state transitions
// spec: SYNC-001 — pending batch for upload

@RunWith(RobolectricTestRunner::class)
class CommitmentDaoTest {

    private lateinit var db: BeCalmDatabase
    private lateinit var dao: CommitmentDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BeCalmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.commitmentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // spec: CMT-001 — insert give commitment
    @Test
    fun `insert give commitment with pending action_state`() = runTest {
        val commitment = makeCommitment("cmt-001", Commitment.Direction.GIVE)
        dao.insert(commitment)
        val result = dao.getById("cmt-001")
        assertNotNull(result)
        assertEquals(Commitment.Direction.GIVE, result!!.direction)
        assertEquals(Commitment.ActionState.PENDING, result.actionState)
    }

    // spec: CMT-002 — insert take commitment
    @Test
    fun `insert take commitment`() = runTest {
        val commitment = makeCommitment("cmt-002", Commitment.Direction.TAKE)
        dao.insert(commitment)
        val result = dao.getById("cmt-002")
        assertNotNull(result)
        assertEquals(Commitment.Direction.TAKE, result!!.direction)
    }

    // spec: CMT-003 — getById returns null for nonexistent
    @Test
    fun `getById returns null for nonexistent commitment`() = runTest {
        val result = dao.getById("nonexistent")
        assertNull(result)
    }

    // spec: CMT-005 — update action_state to reminded
    @Test
    fun `updateActionState transitions to reminded`() = runTest {
        dao.insert(makeCommitment("cmt-reminded"))
        dao.updateActionState("cmt-reminded", Commitment.ActionState.REMINDED)
        val result = dao.getById("cmt-reminded")
        assertEquals(Commitment.ActionState.REMINDED, result!!.actionState)
    }

    // spec: CMT-006 — update action_state to followed_up
    @Test
    fun `updateActionState transitions to followed_up`() = runTest {
        dao.insert(makeCommitment("cmt-fu"))
        dao.updateActionState("cmt-fu", Commitment.ActionState.FOLLOWED_UP)
        val result = dao.getById("cmt-fu")
        assertEquals(Commitment.ActionState.FOLLOWED_UP, result!!.actionState)
    }

    // spec: CMT-007 — update action_state to completed
    @Test
    fun `updateActionState transitions to completed`() = runTest {
        dao.insert(makeCommitment("cmt-done"))
        dao.updateActionState("cmt-done", Commitment.ActionState.COMPLETED)
        val result = dao.getById("cmt-done")
        assertEquals(Commitment.ActionState.COMPLETED, result!!.actionState)
    }

    // spec: CMT-010 — filter by direction
    @Test
    fun `getFiltered by direction returns only matching commitments`() = runTest {
        dao.insert(makeCommitment("cmt-g1", Commitment.Direction.GIVE))
        dao.insert(makeCommitment("cmt-g2", Commitment.Direction.GIVE))
        dao.insert(makeCommitment("cmt-t1", Commitment.Direction.TAKE))
        val gives = dao.getFiltered(direction = Commitment.Direction.GIVE)
        assertEquals(2, gives.size)
        val takes = dao.getFiltered(direction = Commitment.Direction.TAKE)
        assertEquals(1, takes.size)
    }

    // spec: CMT-010 — filter by action_state
    @Test
    fun `getFiltered by action_state returns only matching`() = runTest {
        dao.insert(makeCommitment("cmt-pending"))
        dao.insert(makeCommitment("cmt-done2").copy(actionState = Commitment.ActionState.COMPLETED))
        val pending = dao.getFiltered(actionState = Commitment.ActionState.PENDING)
        assertEquals(1, pending.size)
    }

    // spec: TDY-004 — today due commitments flow
    @Test
    fun `observeTodayDueCommitments emits today's pending commitments`() = runTest {
        val today = "2026-04-16"
        dao.insert(makeCommitment("cmt-today", dueDate = today))
        dao.insert(makeCommitment("cmt-other", dueDate = "2026-04-17"))
        val result = dao.observeTodayDueCommitments(today).first()
        assertEquals(1, result.size)
        assertEquals("cmt-today", result[0].id)
    }

    // spec: SYNC-001 — pending batch for upload
    @Test
    fun `getPendingBatch returns only pending sync_status`() = runTest {
        dao.insert(makeCommitment("cmt-s1"))
        dao.insert(makeCommitment("cmt-s2"))
        dao.markSynced(listOf("cmt-s1"))
        val batch = dao.getPendingBatch()
        assertEquals(1, batch.size)
        assertEquals("cmt-s2", batch[0].id)
    }

    // spec: data-model — quote field is preserved exactly (legally sensitive)
    @Test
    fun `commitment quote is stored and retrieved verbatim`() = runTest {
        val quote = "나는 내일까지 보고서를 제출하겠습니다."
        val cmt = makeCommitment("cmt-quote").copy(quote = quote)
        dao.insert(cmt)
        val result = dao.getById("cmt-quote")
        assertEquals(quote, result!!.quote)
    }

    private fun makeCommitment(
        id: String,
        direction: String = Commitment.Direction.GIVE,
        dueDate: String? = null
    ) = Commitment(
        id = id,
        direction = direction,
        title = "Test commitment",
        quote = "verbatim text",
        sourceEventOccurredAt = System.currentTimeMillis(),
        sourceType = "voice",
        dueDate = dueDate
    )
}

package com.becalm.android.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.data.local.dao.TranscriptDao
import com.becalm.android.data.local.entities.RawIngestionEvent
import com.becalm.android.data.local.entities.Transcript
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// spec: VOI-001 — STT transcript insert (local only)
// Invariant: Transcript is NEVER uploaded to Railway or Supabase

@RunWith(RobolectricTestRunner::class)
class TranscriptDaoTest {

    private lateinit var db: BeCalmDatabase
    private lateinit var dao: TranscriptDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BeCalmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.transcriptDao()

        // Insert parent RawIngestionEvent for FK constraint
        val event = RawIngestionEvent(
            clientEventId = "ev-voice-001",
            sourceType = "voice",
            timestamp = System.currentTimeMillis()
        )
        db.rawIngestionEventDao().let { evtDao ->
            kotlinx.coroutines.runBlocking { evtDao.insert(event) }
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // spec: VOI-001 — transcript stored with non-empty text
    @Test
    fun `insert transcript with text`() = runTest {
        val transcript = Transcript(
            id = "tr-001",
            rawIngestionId = "ev-voice-001",
            text = "안녕하세요 내일까지 보고서를 제출하겠습니다."
        )
        dao.insert(transcript)
        val result = dao.getByRawIngestionId("ev-voice-001")
        assertNotNull(result)
        assertEquals("안녕하세요 내일까지 보고서를 제출하겠습니다.", result!!.text)
    }

    // spec: VOI-001 — getByRawIngestionId returns null when no transcript
    @Test
    fun `getByRawIngestionId returns null when transcript not found`() = runTest {
        val result = dao.getByRawIngestionId("nonexistent")
        assertNull(result)
    }

    // spec: VOI-001 — idempotent insert (duplicate ignored)
    @Test
    fun `duplicate transcript insert is ignored`() = runTest {
        val transcript = Transcript(id = "tr-dup", rawIngestionId = "ev-voice-001", text = "text")
        dao.insert(transcript)
        dao.insert(transcript) // second insert should be ignored
        assertEquals(1, dao.count())
    }
}

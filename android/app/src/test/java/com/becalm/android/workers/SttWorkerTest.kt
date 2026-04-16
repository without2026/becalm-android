package com.becalm.android.workers

import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// spec: VOI-005 — SttWorker chunking: audio > 60s split into 30s segments

class SttWorkerTest {

    private val sttEngine: SttEngine = mockk()

    // spec: VOI-005 — 120s audio splits into 4 chunks of 30s each; transcripts concatenated
    @Test
    fun `sttWorker_120sAudio_splitsIntoFourChunks`() = runTest {
        val uri = mockk<Uri>()
        // 120s at 30s/chunk = 4 invocations
        coEvery { sttEngine.transcribeSegment(uri, 0L, 30_000L) } returns "chunk0"
        coEvery { sttEngine.transcribeSegment(uri, 30_000L, 30_000L) } returns "chunk1"
        coEvery { sttEngine.transcribeSegment(uri, 60_000L, 30_000L) } returns "chunk2"
        coEvery { sttEngine.transcribeSegment(uri, 90_000L, 30_000L) } returns "chunk3"

        val worker = makeWorker()
        val result = worker.transcribeInChunks(uri, durationSeconds = 120)

        assertEquals("chunk0 chunk1 chunk2 chunk3", result)
        coVerify(exactly = 4) { sttEngine.transcribeSegment(any(), any(), any()) }
    }

    // spec: VOI-005 — 50s audio is below threshold → single invocation
    @Test
    fun `sttWorker_50sAudio_singleChunk`() = runTest {
        val uri = mockk<Uri>()
        coEvery { sttEngine.transcribeSegment(uri, 0L, 50_000L) } returns "single transcript"

        val worker = makeWorker()
        val result = worker.transcribeSingle(uri, durationSeconds = 50)

        assertEquals("single transcript", result)
        coVerify(exactly = 1) { sttEngine.transcribeSegment(uri, 0L, 50_000L) }
    }

    // spec: VOI-005 — 75s audio (over threshold) splits into 30s+30s+15s = 3 invocations
    @Test
    fun `sttWorker_75sAudio_splitsIntoThreeChunks`() = runTest {
        val uri = mockk<Uri>()
        coEvery { sttEngine.transcribeSegment(uri, 0L, 30_000L) } returns "a"
        coEvery { sttEngine.transcribeSegment(uri, 30_000L, 30_000L) } returns "b"
        coEvery { sttEngine.transcribeSegment(uri, 60_000L, 15_000L) } returns "c"

        val worker = makeWorker()
        val result = worker.transcribeInChunks(uri, durationSeconds = 75)

        assertEquals("a b c", result)
        coVerify(exactly = 3) { sttEngine.transcribeSegment(any(), any(), any()) }
    }

    // spec: VOI-005 — exactly 60s audio (threshold boundary): chunk path returns 2 chunks
    @Test
    fun `sttWorker_60sAudio_atThreshold_twoChunks`() = runTest {
        val uri = mockk<Uri>()
        coEvery { sttEngine.transcribeSegment(uri, 0L, 30_000L) } returns "first"
        coEvery { sttEngine.transcribeSegment(uri, 30_000L, 30_000L) } returns "second"

        val worker = makeWorker()
        val result = worker.transcribeInChunks(uri, durationSeconds = 60)

        assertEquals("first second", result)
    }

    // spec: VOI-005 — any chunk returning null aborts transcription
    @Test
    fun `sttWorker_chunkReturnsNull_abortsAndReturnsNull`() = runTest {
        val uri = mockk<Uri>()
        coEvery { sttEngine.transcribeSegment(uri, 0L, 30_000L) } returns "ok"
        coEvery { sttEngine.transcribeSegment(uri, 30_000L, 30_000L) } returns null

        val worker = makeWorker()
        val result = worker.transcribeInChunks(uri, durationSeconds = 90)

        assertNull(result)
        // Third chunk is never called — aborted on null
        coVerify(exactly = 2) { sttEngine.transcribeSegment(any(), any(), any()) }
    }

    // Constants are correct per spec
    @Test
    fun `chunk threshold is 60s and chunk size is 30s`() {
        assertEquals(60, SttWorker.CHUNK_THRESHOLD_SECONDS)
        assertEquals(30, SttWorker.CHUNK_SIZE_SECONDS)
    }

    private fun makeWorker(): SttWorker {
        val context = mockk<android.content.Context>(relaxed = true)
        val workerParams = mockk<androidx.work.WorkerParameters>(relaxed = true)
        val rawDao = mockk<com.becalm.android.data.local.dao.RawIngestionEventDao>(relaxed = true)
        val transcriptDao = mockk<com.becalm.android.data.local.dao.TranscriptDao>(relaxed = true)
        return SttWorker(context, workerParams, rawDao, transcriptDao).also { it.sttEngine = sttEngine }
    }
}

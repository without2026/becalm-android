package com.becalm.android.unit.worker

import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.worker.extraction.CommitmentExtractionWorker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentExtractionWorkerManualSourceSpecTest {

    @Test
    fun `MAN-006 raw event extraction helper skips manual commitments`() {
        assertFalse(SourceType.ALL.contains("manual"))
        assertFalse(CommitmentExtractionWorker.Companion.supportsRawEventSource("manual"))
    }

    @Test
    fun `MAN-006 raw event extraction helper still accepts supported ingestion sources`() {
        assertTrue(CommitmentExtractionWorker.Companion.supportsRawEventSource(SourceType.VOICE))
        assertTrue(CommitmentExtractionWorker.Companion.supportsRawEventSource(SourceType.GMAIL))
        assertTrue(CommitmentExtractionWorker.Companion.supportsRawEventSource(SourceType.GOOGLE_CALENDAR))
    }
}

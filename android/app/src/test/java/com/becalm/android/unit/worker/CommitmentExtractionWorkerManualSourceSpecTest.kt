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

    @Test
    fun `EMAIL-001 AICORE_ERROR retries once then stops retry storm`() {
        assertTrue(
            CommitmentExtractionWorker.Companion.shouldRetryExtractorFailure(
                reason = "AICORE_ERROR",
                runAttemptCount = 0,
            ),
        )
        assertFalse(
            CommitmentExtractionWorker.Companion.shouldRetryExtractorFailure(
                reason = "AICORE_ERROR",
                runAttemptCount = 1,
            ),
        )
    }

    @Test
    fun `EMAIL-001 non AICORE_ERROR extractor failures keep WorkManager retry semantics`() {
        assertTrue(
            CommitmentExtractionWorker.Companion.shouldRetryExtractorFailure(
                reason = "LLM_JSON_PARSE_FAILED",
                runAttemptCount = 3,
            ),
        )
    }
}

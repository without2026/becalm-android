package com.becalm.android.worker

import androidx.work.ListenableWorker
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.meeting.MeetingImportFileKind
import com.becalm.android.domain.meeting.MeetingImportFilePolicy
import com.becalm.android.worker.ingestion.MediaStoreWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Checkpoint3WorkerContractSpecTest {

    @Test
    // spec: ING-003
    fun e2e_026_backend_sync_timeout_during_raw_upload_maps_to_retry() {
        val outcome = mapErrorToOutcome(
            logger = NoopLogger,
            error = BecalmError.Network(code = 0, message = "timeout"),
            attempt = 0,
            domain = "rawIngestion",
        )

        assertTrue(outcome is FlushOutcome.TransportRetry)
        assertEquals(ListenableWorker.Result.retry().javaClass, (outcome as FlushOutcome.TransportRetry).result.javaClass)
    }

    @Test
    fun e2e_027_foreground_refresh_uses_unique_source_keys_for_coalescing() {
        val specs = listOf(
            SourceType.VOICE,
            SourceType.MEETING,
            SourceType.NAVER_IMAP,
            SourceType.DAUM_IMAP,
            SourceType.GOOGLE_CALENDAR,
            SourceType.OUTLOOK_CALENDAR,
        ).mapNotNull(WorkSchedulerRequests::resolveSource)

        assertEquals(6, specs.size)
        assertEquals(5, specs.map { it.uniqueKey }.toSet().size)
        assertEquals(UniqueWorkKeys.MEDIA_STORE, WorkSchedulerRequests.resolveSource(SourceType.VOICE)?.uniqueKey)
        assertEquals(UniqueWorkKeys.MEDIA_STORE, WorkSchedulerRequests.resolveSource(SourceType.MEETING)?.uniqueKey)
    }

    @Test
    // spec: ING-014
    fun e2e_028_batch_upload_respects_railway_batch_contract_cap() {
        assertEquals(100, UploadWorker.BATCH_SIZE)
        assertTrue(UploadWorker.BATCH_SIZE <= 100)
    }

    @Test
    fun e2e_029_single_extraction_uses_shared_normalized_worker_requests() {
        val screenshot = WorkSchedulerRequests.messageScreenshotUploadRequest("raw-shot")

        assertEquals(MessageScreenshotUploadWorker::class.java.name, screenshot.workSpec.workerClassName)
        assertEquals("raw-shot", screenshot.workSpec.input.getString(MessageScreenshotUploadWorker.KEY_RAW_EVENT_ID))
    }

    @Test
    fun e2e_032_unsupported_meeting_file_type_is_rejected_before_raw_event_creation() {
        assertEquals(
            MeetingImportFileKind.Rejected,
            MeetingImportFilePolicy.classify("application/octet-stream", "meeting.exe"),
        )
    }

    @Test
    fun e2e_037_supported_call_recording_file_is_detected_by_mediastore_pipeline_owner() {
        assertEquals(MediaStoreWorker::class.java, WorkSchedulerRequests.resolveSource(SourceType.VOICE)?.workerClass)
        assertTrue(SourceType.CALL_RECORDING in SourceType.ALL)
    }

    @Test
    fun e2e_038_unsupported_recording_file_is_rejected_by_import_policy() {
        assertEquals(
            MeetingImportFileKind.Rejected,
            MeetingImportFilePolicy.classify("audio/ogg", "corrupt.ogg"),
        )
    }

    private object NoopLogger : Logger {
        override fun d(tag: String, message: String, throwable: Throwable?) = Unit
        override fun i(tag: String, message: String, throwable: Throwable?) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }
}

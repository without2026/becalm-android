package com.becalm.android.unit.data.remote.dto

import com.becalm.android.core.util.addBecalmAdapters
import com.becalm.android.data.remote.dto.CommitmentExtractionJobCreateRequest
import com.becalm.android.data.remote.dto.ExtractionStorageRefDto
import com.becalm.android.data.remote.dto.ExtractionUploadPrepareResponse
import com.becalm.android.data.remote.dto.MeetingSpeakerPreviewResponse
import com.becalm.android.data.remote.dto.SourceExtractionResponse
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceExtractionDtosSpecTest {

    private val moshi = Moshi.Builder()
        .addBecalmAdapters()
        .build()

    @Test
    fun `source extraction response parses one ten hundred extracted items`() {
        listOf(1, 10, 100).forEach { scale ->
            val items = (0 until scale).joinToString(",") { index ->
                """
                    {
                      "type": "action",
                      "text": "Send follow-up $index",
                      "quote": "Please send follow-up $index",
                      "person_ref": "customer@example.com",
                      "due_at": null,
                      "due_hint": "tomorrow",
                      "due_is_approximate": true,
                      "confidence": 0.8,
                      "direction": "give"
                    }
                """.trimIndent()
            }
            val json = """
                {
                  "raw_event_id": "raw-scale-$scale",
                  "items": [$items],
                  "completion_signals": [],
                  "source_event_participants": [],
                  "model": "gemini-2.5-flash",
                  "region": "us-central1",
                  "raw_model_text": "{\"items\":[]}"
                }
            """.trimIndent()

            val response = requireNotNull(moshi.adapter(SourceExtractionResponse::class.java).fromJson(json))

            assertEquals("raw-scale-$scale", response.rawEventId)
            assertEquals(scale, response.items.size)
            assertEquals("action", response.items.first().type)
            assertEquals("tomorrow", response.items.first().dueHint)
            assertEquals(0.8f, response.items.first().confidence)
            assertNull(response.status)
        }
    }

    @Test
    fun `source extraction response parses async job polling fields`() {
        val json = """
            {
              "raw_event_id": "raw-job-1",
              "items": [],
              "completion_signals": [],
              "source_event_participants": [],
              "model": "pending",
              "region": "pending",
              "raw_model_text": null,
              "job_id": "job-1",
              "status": "processing",
              "retry_after_seconds": 10
            }
        """.trimIndent()

        val response = requireNotNull(moshi.adapter(SourceExtractionResponse::class.java).fromJson(json))

        assertEquals("job-1", response.jobId)
        assertEquals("processing", response.status)
        assertEquals(10L, response.retryAfterSeconds)
    }

    @Test
    fun `direct upload prepare response parses signed upload contract`() {
        val json = """
            {
              "raw_event_id": "raw-1",
              "job_id": "job-1",
              "bucket": "extraction-jobs",
              "path": "user-1/job-1/image.jpg",
              "content_type": "image/jpeg",
              "media_kind": "image",
              "signed_upload_url": "https://storage.example/upload/sign/path?token=signed-token",
              "upload_token": "signed-token",
              "upload_content_type": "application/octet-stream",
              "storage_ref": {
                "bucket": "extraction-jobs",
                "path": "user-1/job-1/image.jpg",
                "content_type": "image/jpeg",
                "media_kind": "image",
                "raw_event_id": "raw-1"
              }
            }
        """.trimIndent()

        val response = requireNotNull(moshi.adapter(ExtractionUploadPrepareResponse::class.java).fromJson(json))

        assertEquals("job-1", response.jobId)
        assertEquals("application/octet-stream", response.uploadContentType)
        assertEquals("image", response.storageRef.mediaKind)
    }

    @Test
    fun `direct upload job create request writes backend field names`() {
        val adapter = moshi.adapter(CommitmentExtractionJobCreateRequest::class.java)
        val json = adapter.toJson(
            CommitmentExtractionJobCreateRequest(
                inputModality = "image",
                sourceType = "message_screenshot",
                clientEventId = "client-1",
                rawEventId = "raw-1",
                timestamp = "2026-06-05T00:00:00Z",
                storageRef = ExtractionStorageRefDto(
                    bucket = "extraction-jobs",
                    path = "user-1/job-1/image.jpg",
                    contentType = "image/jpeg",
                    rawEventId = "raw-1",
                    mediaKind = "image",
                ),
                eventTitle = "thread.jpg",
                processingConfirmed = false,
            ),
        )

        check("\"input_modality\":\"image\"" in json)
        check("\"storage_ref\"" in json)
        check("\"raw_event_id\":\"raw-1\"" in json)
        check("\"processing_confirmed\":false" in json)
    }

    @Test
    fun `meeting speaker preview response parses one ten hundred transcript segments`() {
        listOf(1, 10, 100).forEach { scale ->
            val segments = (0 until scale).joinToString(",") { index ->
                """
                    {
                      "speaker_id": "SPEAKER_${(index % 3) + 1}",
                      "start_seconds": $index.0,
                      "end_seconds": ${index + 1}.0,
                      "text": "segment text $index"
                    }
                """.trimIndent()
            }
            val json = """
                {
                  "raw_event_id": "raw-preview-$scale",
                  "speaker_preview_id": "preview-$scale",
                  "speakers": [
                    {
                      "speaker_id": "SPEAKER_1",
                      "sample_texts": ["segment text 0"],
                      "first_start": 0.0,
                      "total_seconds": 1.0
                    }
                  ],
                  "model": "clova",
                  "billable_seconds": $scale,
                  "transcript_segments": [$segments]
                }
            """.trimIndent()

            val response = requireNotNull(moshi.adapter(MeetingSpeakerPreviewResponse::class.java).fromJson(json))

            assertEquals("raw-preview-$scale", response.rawEventId)
            assertEquals("preview-$scale", response.speakerPreviewId)
            assertEquals(scale, response.billableSeconds)
            assertEquals(scale, response.transcriptSegments.size)
        }
    }
}

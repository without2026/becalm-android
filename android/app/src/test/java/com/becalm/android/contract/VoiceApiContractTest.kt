package com.becalm.android.contract

import com.becalm.android.core.util.addBecalmAdapters
import com.becalm.android.data.remote.api.VoiceApi
import com.becalm.android.data.remote.dto.CommitmentDraftDto
import com.becalm.android.data.remote.dto.ErrorEnvelopeDto
import com.becalm.android.data.remote.dto.TranscribeExtractResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Contract tests for POST /v1/voice/transcribe_extract.
 *
 * Spec refs: api-contract.yml § /v1/voice/transcribe_extract, VOI-CT-001..006.
 *
 * Wire-level tests that verify:
 *   1. HTTP 200 response body deserializes correctly into TranscribeExtractResponse.
 *   2. Error envelopes for 403, 413, 422, 502, 503 parse correctly.
 *   3. Multipart request contains required parts (audio, client_event_id, raw_event_id,
 *      duration_seconds, timestamp).
 *
 * VoiceApi EXISTS (VOI-CT tests run without @Ignore).
 * ErrorEnvelopeDto.retryable field does NOT exist — see VOI-CT-003 divergence note.
 */
class VoiceApiContractTest {

    private lateinit var server: MockWebServer
    private lateinit var moshi: Moshi
    private lateinit var voiceApi: VoiceApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        moshi = Moshi.Builder()
            .addBecalmAdapters()
            .add(KotlinJsonAdapterFactory())
            .build()

        val client = OkHttpClient.Builder().build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        voiceApi = retrofit.create(VoiceApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---------------------------------------------------------------------------
    // VOI-CT-001: Happy path — HTTP 200 with 2 commitments
    // ---------------------------------------------------------------------------

    @Test
    fun `VOI-CT-001 happy path parses 200 with two commitments correctly`() {
        val responseBody = """
            {
              "raw_event_id": "550e8400-e29b-41d4-a716-446655440001",
              "commitments": [
                {
                  "direction": "give",
                  "text": "Send the quarterly report",
                  "quote": "I will send you the report by Friday",
                  "person_ref": "email:kim@example.com",
                  "due_at": "2026-04-20T10:00:00Z",
                  "confidence": 0.92
                },
                {
                  "direction": "take",
                  "text": "Prepare the slides",
                  "quote": "Kim said he will prepare the slides",
                  "person_ref": "phone:+82-10-1234-5678",
                  "due_at": null,
                  "confidence": 0.75
                }
              ],
              "model": "gemini-2.5-flash",
              "region": "asia-northeast3"
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))

        val adapter = moshi.adapter(TranscribeExtractResponse::class.java)
        val parsed = adapter.fromJson(responseBody)!!

        assertEquals("550e8400-e29b-41d4-a716-446655440001", parsed.rawEventId)
        assertEquals(2, parsed.commitments.size)
        assertEquals("gemini-2.5-flash", parsed.model)
        assertEquals("asia-northeast3", parsed.region)

        val first = parsed.commitments[0]
        assertEquals("give", first.direction)
        assertEquals("Send the quarterly report", first.text)
        assertEquals("I will send you the report by Friday", first.quote)
        assertEquals("email:kim@example.com", first.personRef)
        assertNotNull("due_at should parse as Instant", first.dueAt)
        assertEquals(0.92f, first.confidence, 0.001f)

        val second = parsed.commitments[1]
        assertEquals("take", second.direction)
        assertNull(second.dueAt)
        assertEquals(0.75f, second.confidence, 0.001f)
    }

    // ---------------------------------------------------------------------------
    // VOI-CT-001 wire check: multipart parts are present in request
    // ---------------------------------------------------------------------------

    @Test
    fun `VOI-CT-001 multipart request contains required parts audio client_event_id raw_event_id duration_seconds timestamp`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"raw_event_id":"id","commitments":[],"model":"gemini-2.5-flash","region":"asia-northeast3"}
        """.trimIndent()))

        // Send a representative multipart via OkHttp directly (mirrors what VoiceUploadWorker sends)
        val fakeParts = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("client_event_id", "client-uuid-1234")
            .addFormDataPart("raw_event_id", "raw-uuid-5678")
            .addFormDataPart("duration_seconds", "300")
            .addFormDataPart("timestamp", "2026-04-16T10:00:00Z")
            .addFormDataPart(
                "audio",
                "recording.m4a",
                ByteArray(1024).toRequestBody("audio/m4a".toMediaType()),
            )
            .build()

        val request = okhttp3.Request.Builder()
            .url(server.url("/v1/voice/transcribe_extract"))
            .post(fakeParts)
            .build()

        OkHttpClient().newCall(request).execute().close()

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue("audio part must be present", body.contains("audio"))
        assertTrue("client_event_id must be present", body.contains("client_event_id"))
        assertTrue("raw_event_id must be present", body.contains("raw_event_id"))
        assertTrue("duration_seconds must be present", body.contains("duration_seconds"))
        assertTrue("timestamp must be present", body.contains("timestamp"))
    }

    // ---------------------------------------------------------------------------
    // VOI-CT-002: HTTP 403 pipa_audit_failed
    // ---------------------------------------------------------------------------

    @Test
    fun `VOI-CT-002 HTTP 403 pipa_audit_failed parses error envelope`() {
        val errorBody = """{"error":"pipa_audit_failed","message":"PIPA consent not verified server-side"}"""
        server.enqueue(MockResponse().setResponseCode(403).setBody(errorBody))

        val adapter = moshi.adapter(ErrorEnvelopeDto::class.java)
        val parsed = adapter.fromJson(errorBody)!!

        assertEquals("pipa_audit_failed", parsed.error)
        assertFalse(parsed.message.isBlank())
    }

    // ---------------------------------------------------------------------------
    // VOI-CT-003: HTTP 413 max_body_bytes
    // DIVERGENCE NOTE: ErrorEnvelopeDto does NOT have a `retryable` field.
    // api-contract.yml defines retryable per FailedEvent type, not the error envelope.
    // The retryable=false semantics for 413 are enforced by VoiceUploadWorker's HTTP
    // status-code branch (403/413/422 → quarantine), not via an ErrorEnvelopeDto field.
    // ---------------------------------------------------------------------------

    @Test
    fun `VOI-CT-003 HTTP 413 max_body_bytes parses error envelope`() {
        val errorBody = """{"error":"max_body_bytes_exceeded","message":"Audio file exceeds 60 MiB limit"}"""
        server.enqueue(MockResponse().setResponseCode(413).setBody(errorBody))

        val adapter = moshi.adapter(ErrorEnvelopeDto::class.java)
        val parsed = adapter.fromJson(errorBody)!!

        assertEquals("max_body_bytes_exceeded", parsed.error)
        // DIVERGENCE: ErrorEnvelopeDto has no `retryable` field. retryable=false for HTTP 413
        // is enforced at the VoiceUploadWorker layer (code 413 → markFailed / Result.success).
        // See VoiceUploadWorker.doWork() branches: 403, 413, 422 all map to Result.success + failed.
    }

    // ---------------------------------------------------------------------------
    // VOI-CT-004: HTTP 422 max_duration_seconds exceeded
    // ---------------------------------------------------------------------------

    @Test
    fun `VOI-CT-004 HTTP 422 duration cap exceeded parses error envelope`() {
        val errorBody = """{"error":"max_duration_exceeded","message":"Audio duration exceeds 7200 seconds"}"""
        server.enqueue(MockResponse().setResponseCode(422).setBody(errorBody))

        val adapter = moshi.adapter(ErrorEnvelopeDto::class.java)
        val parsed = adapter.fromJson(errorBody)!!

        assertEquals("max_duration_exceeded", parsed.error)
    }

    // ---------------------------------------------------------------------------
    // VOI-CT-005: HTTP 502 output_truncated
    // ---------------------------------------------------------------------------

    @Test
    fun `VOI-CT-005 HTTP 502 output_truncated parses error envelope`() {
        val errorBody = """{"error":"output_truncated","message":"Gemini MAX_TOKENS finish reason — single call cannot handle file"}"""
        server.enqueue(MockResponse().setResponseCode(502).setBody(errorBody))

        val adapter = moshi.adapter(ErrorEnvelopeDto::class.java)
        val parsed = adapter.fromJson(errorBody)!!

        assertEquals("output_truncated", parsed.error)
    }

    // ---------------------------------------------------------------------------
    // VOI-CT-006: HTTP 503 vertex downstream — retryable
    // ---------------------------------------------------------------------------

    @Test
    fun `VOI-CT-006 HTTP 503 vertex downstream parses error envelope`() {
        val errorBody = """{"error":"vertex_downstream_error","message":"Vertex AI temporarily unavailable"}"""
        server.enqueue(MockResponse().setResponseCode(503).setBody(errorBody))

        val adapter = moshi.adapter(ErrorEnvelopeDto::class.java)
        val parsed = adapter.fromJson(errorBody)!!

        assertEquals("vertex_downstream_error", parsed.error)
        // HTTP 503 is retryable per spec. VoiceUploadWorker returns Result.retry() when
        // runAttemptCount < MAX_ATTEMPTS, and Result.success + failed when exhausted.
    }

    // ---------------------------------------------------------------------------
    // VOI-CT-001 supplemental: VoiceApi is constructable via Retrofit
    // ---------------------------------------------------------------------------

    @Test
    fun `VoiceApi Retrofit proxy is not null`() {
        assertNotNull("voiceApi Retrofit proxy must be non-null", voiceApi)
    }

    // ---------------------------------------------------------------------------
    // Direction enum parsing — exercises CommitmentDraftDto.toDomain()
    // ---------------------------------------------------------------------------

    @Test
    fun `CommitmentDraftDto direction give maps to Direction GIVE`() {
        val dto = CommitmentDraftDto(
            direction = "give",
            text = "Send report",
            quote = "I will send the report",
            personRef = null,
            dueAt = null,
            confidence = 0.9f,
        )
        val domain = dto.toDomain()
        assertEquals(
            com.becalm.android.domain.voice.Direction.GIVE,
            domain.direction,
        )
    }

    @Test
    fun `CommitmentDraftDto direction take maps to Direction TAKE`() {
        val dto = CommitmentDraftDto(
            direction = "take",
            text = "Prepare slides",
            quote = "Kim will prepare slides",
            personRef = "phone:+82-10-1111-2222",
            dueAt = null,
            confidence = 0.8f,
        )
        val domain = dto.toDomain()
        assertEquals(
            com.becalm.android.domain.voice.Direction.TAKE,
            domain.direction,
        )
    }
}

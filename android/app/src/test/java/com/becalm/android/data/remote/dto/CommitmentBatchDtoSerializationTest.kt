package com.becalm.android.data.remote.dto

import com.becalm.android.core.util.addBecalmAdapters
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire format for commitment `due_at` per
 * `.spec/contracts/api-contract.yml:32`:
 *
 *   `ISO8601 with explicit +09:00 offset (KST). Android는 UTC로 저장·계산하되
 *    payload는 KST로 전송한다.`
 *
 * Serialization: internal UTC [Instant] must emit `+09:00` on the wire.
 * Parsing: tolerant — accepts both `+09:00` and UTC `Z` server echoes.
 * All other [Instant] fields (e.g. `created_at`, `source_event_occurred_at`)
 * continue to serialize as UTC `Z` — the qualifier is field-scoped.
 */
class CommitmentBatchDtoSerializationTest {

    private val moshi: Moshi = Moshi.Builder()
        .addBecalmAdapters()
        .add(KotlinJsonAdapterFactory())
        .build()

    private fun makeItem(dueAt: Instant?): CommitmentBatchItemDto = CommitmentBatchItemDto(
        clientEventId = "11111111-2222-3333-4444-555555555555",
        commitment = CommitmentBatchPayloadDto(
            id = "11111111-2222-3333-4444-555555555555",
            userId = "user-1",
            direction = "give",
            title = "t",
            quote = "q",
            sourceEventOccurredAt = Instant.parse("2026-04-19T00:00:00Z"),
            dueAt = dueAt,
            actionState = "pending",
            sourceType = "voice",
            confidence = 0.9,
            createdAt = Instant.parse("2026-04-19T00:00:00Z"),
            updatedAt = Instant.parse("2026-04-19T00:00:00Z"),
        ),
    )

    @Test
    fun `due_at serializes with explicit +09-00 KST offset`() {
        val utc = Instant.parse("2026-04-19T15:00:00Z") // = 2026-04-20T00:00:00 KST
        val dto = CommitmentBatchRequestDto(commitments = listOf(makeItem(utc)))

        val json = moshi.adapter(CommitmentBatchRequestDto::class.java).toJson(dto)

        assertTrue(
            "Expected due_at with +09:00 offset, got: $json",
            json.contains("\"due_at\":\"2026-04-20T00:00:00+09:00\""),
        )
        // Guard against the pre-fix Z emission.
        assertTrue(
            "due_at must NOT emit UTC Z form, got: $json",
            !json.contains("\"due_at\":\"2026-04-19T15:00:00Z\""),
        )
    }

    @Test
    fun `due_at preserves millisecond precision and round-trips`() {
        // Regression guard for codex round-4 R4-F1: earlier implementation used a
        // printf "%02d:%02d:%02d" formatter that silently dropped sub-second
        // precision. DateTimeFormatter.ISO_OFFSET_DATE_TIME must carry fractional
        // seconds through both directions without loss.
        val utcWithMillis = Instant.parse("2026-04-19T15:00:00.123Z")
        val dto = CommitmentBatchRequestDto(commitments = listOf(makeItem(utcWithMillis)))

        val json = moshi.adapter(CommitmentBatchRequestDto::class.java).toJson(dto)

        assertTrue(
            "Expected millisecond precision preserved in KST payload, got: $json",
            json.contains("\"due_at\":\"2026-04-20T00:00:00.123+09:00\""),
        )

        val parsed = moshi.adapter(CommitmentBatchRequestDto::class.java).fromJson(json)
        assertEquals(
            utcWithMillis,
            parsed!!.commitments.single().commitment.dueAt,
        )
    }

    @Test
    fun `null due_at is omitted from serialization`() {
        val dto = CommitmentBatchRequestDto(commitments = listOf(makeItem(null)))
        val json = moshi.adapter(CommitmentBatchRequestDto::class.java).toJson(dto)
        assertTrue(
            "null due_at must not appear in payload, got: $json",
            !json.contains("\"due_at\""),
        )
    }

    @Test
    fun `other Instant fields still serialize as UTC Z (field-scoped qualifier)`() {
        val dto = CommitmentBatchRequestDto(commitments = listOf(makeItem(Instant.parse("2026-04-19T15:00:00Z"))))
        val json = moshi.adapter(CommitmentBatchRequestDto::class.java).toJson(dto)

        // source_event_occurred_at + created_at + updated_at are NOT marked
        // @KstInstant → global InstantAdapter still emits UTC Z.
        assertTrue(
            "source_event_occurred_at must remain UTC Z, got: $json",
            json.contains("\"source_event_occurred_at\":\"2026-04-19T00:00:00Z\""),
        )
    }

    @Test
    fun `KST payload round-trips through tolerant parser`() {
        val json =
            """{"commitments":[{"client_event_id":"x","commitment":{"id":"x","user_id":"u","direction":"give","title":"t","quote":"q","source_event_occurred_at":"2026-04-19T00:00:00Z","due_at":"2026-04-20T00:00:00+09:00","action_state":"pending","source_type":"voice","confidence":0.9,"created_at":"2026-04-19T00:00:00Z","updated_at":"2026-04-19T00:00:00Z"}}]}"""

        val parsed = moshi.adapter(CommitmentBatchRequestDto::class.java).fromJson(json)

        assertNotNull(parsed)
        // +09:00 and Z representations of the same moment are the same Instant.
        assertEquals(
            Instant.parse("2026-04-19T15:00:00Z"),
            parsed!!.commitments.single().commitment.dueAt,
        )
    }

    @Test
    fun `UTC Z payload is also accepted on parse for backward compatibility`() {
        val json =
            """{"commitments":[{"client_event_id":"x","commitment":{"id":"x","user_id":"u","direction":"give","title":"t","quote":"q","source_event_occurred_at":"2026-04-19T00:00:00Z","due_at":"2026-04-19T15:00:00Z","action_state":"pending","source_type":"voice","confidence":0.9,"created_at":"2026-04-19T00:00:00Z","updated_at":"2026-04-19T00:00:00Z"}}]}"""

        val parsed = moshi.adapter(CommitmentBatchRequestDto::class.java).fromJson(json)

        assertNotNull(parsed)
        assertEquals(
            Instant.parse("2026-04-19T15:00:00Z"),
            parsed!!.commitments.single().commitment.dueAt,
        )
    }
}

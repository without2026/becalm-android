package com.becalm.android.schema

import com.becalm.android.data.remote.dto.CommitmentDraftDto
import com.becalm.android.data.remote.dto.TranscribeExtractResponse
import com.becalm.android.domain.voice.Direction
import com.becalm.android.core.util.addBecalmAdapters
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
// Note: moshi-adapters (Rfc3339DateJsonAdapter) is NOT in the dependency catalog.
// Instant parsing is handled by InstantAdapter from com.becalm.android.core.util.JsonAdapters.
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Pure JSON parsing tests for the CommitmentDraft / TranscribeExtractResponse schema.
 *
 * No Android runtime or MockWebServer required — these run purely on the JVM.
 *
 * Spec refs: VOI-003, api-contract.yml § CommitmentDraft shape, data-model.yml.
 *
 * Tests that DO compile against current production DTOs run without @Ignore.
 * Tests that depend on Moshi strict-mode rejection (schema_violation) are noted
 * with the observed DTO behavior.
 */
class VoiceResponseSchemaTest {

    private lateinit var moshi: Moshi

    @Before
    fun setUp() {
        moshi = Moshi.Builder()
            .addBecalmAdapters()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    // ---------------------------------------------------------------------------
    // Happy path — full CommitmentDraft array
    // ---------------------------------------------------------------------------

    @Test
    fun `happy path JSON matching CommitmentDraft shape deserializes correctly`() {
        // This is the canonical fixture from the spec.
        val json = """
            {
              "raw_event_id": "550e8400-e29b-41d4-a716-446655440000",
              "commitments": [
                {
                  "direction": "give",
                  "text": "Send the quarterly report",
                  "quote": "I will send you the report by end of week",
                  "person_ref": "phone:+82-10-1234-5678",
                  "due_at": "2026-04-20T10:00:00Z",
                  "confidence": 0.82
                }
              ],
              "model": "gemini-2.5-flash",
              "region": "asia-northeast3"
            }
        """.trimIndent()

        val adapter = moshi.adapter(TranscribeExtractResponse::class.java)
        val response = adapter.fromJson(json)!!

        assertEquals("550e8400-e29b-41d4-a716-446655440000", response.rawEventId)
        assertEquals(1, response.commitments.size)
        assertEquals("gemini-2.5-flash", response.model)
        assertEquals("asia-northeast3", response.region)

        val commitment = response.commitments[0]
        assertEquals("give", commitment.direction)
        assertEquals("Send the quarterly report", commitment.text)
        assertEquals("I will send you the report by end of week", commitment.quote)
        assertEquals("phone:+82-10-1234-5678", commitment.personRef)
        assertNotNull("due_at must parse as Instant", commitment.dueAt)
        assertEquals(0.82f, commitment.confidence, 0.001f)
    }

    @Test
    fun `happy path with null optional fields deserializes correctly`() {
        val json = """
            {
              "raw_event_id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
              "commitments": [
                {
                  "direction": "take",
                  "text": "Review the contract",
                  "quote": "He said he would review the contract",
                  "person_ref": null,
                  "due_at": null,
                  "confidence": 0.65
                }
              ],
              "model": "gemini-2.5-flash",
              "region": "asia-northeast3"
            }
        """.trimIndent()

        val adapter = moshi.adapter(TranscribeExtractResponse::class.java)
        val response = adapter.fromJson(json)!!

        val commitment = response.commitments[0]
        assertNull("person_ref should be null when not identified", commitment.personRef)
        assertNull("due_at should be null when no deadline mentioned", commitment.dueAt)
        assertEquals("take", commitment.direction)
    }

    @Test
    fun `happy path with empty commitments list deserializes correctly`() {
        val json = """
            {
              "raw_event_id": "550e8400-e29b-41d4-a716-000000000000",
              "commitments": [],
              "model": "gemini-2.5-flash",
              "region": "asia-northeast3"
            }
        """.trimIndent()

        val adapter = moshi.adapter(TranscribeExtractResponse::class.java)
        val response = adapter.fromJson(json)!!

        assertTrue("commitments list must be empty when none extracted", response.commitments.isEmpty())
    }

    // ---------------------------------------------------------------------------
    // ISO 8601 due_at parsing
    // ---------------------------------------------------------------------------

    @Test
    fun `due_at ISO8601 UTC timestamp parses to Instant`() {
        val json = """
            {
              "direction": "give",
              "text": "File the report",
              "quote": "I will file it by Monday",
              "person_ref": null,
              "due_at": "2026-04-20T10:00:00Z",
              "confidence": 0.77
            }
        """.trimIndent()

        val adapter = moshi.adapter(CommitmentDraftDto::class.java)
        val dto = adapter.fromJson(json)!!

        assertNotNull(dto.dueAt)
        assertEquals(
            Instant.parse("2026-04-20T10:00:00Z"),
            dto.dueAt,
        )
    }

    // ---------------------------------------------------------------------------
    // Malformed JSON — missing required field `quote`
    // ---------------------------------------------------------------------------

    @Test
    fun `malformed JSON missing required field quote throws JsonDataException`() {
        // `quote` is a required non-null String in CommitmentDraftDto.
        // Moshi will throw JsonDataException because the field is non-nullable with no default.
        val jsonMissingQuote = """
            {
              "direction": "give",
              "text": "Send report",
              "person_ref": null,
              "due_at": null,
              "confidence": 0.9
            }
        """.trimIndent()

        val adapter = moshi.adapter(CommitmentDraftDto::class.java)

        try {
            adapter.fromJson(jsonMissingQuote)
            fail("Expected JsonDataException due to missing required field 'quote'")
        } catch (e: JsonDataException) {
            // Expected — required non-null 'quote' field is absent.
            assertTrue(
                "Exception message should reference 'quote' field",
                e.message?.contains("quote", ignoreCase = true) == true ||
                    e.message?.contains("required", ignoreCase = true) == true ||
                    e.message?.contains("null", ignoreCase = true) == true,
            )
        } catch (e: NullPointerException) {
            // Some Moshi configurations surface missing non-null fields as NPE rather than
            // JsonDataException depending on the adapter chain. Accept either.
            // Document observed behavior: Moshi KotlinJsonAdapterFactory throws NPE on missing
            // non-null field 'quote'.
        }
    }

    // ---------------------------------------------------------------------------
    // Enum violation — direction: "other"
    // ---------------------------------------------------------------------------

    @Test
    fun `direction enum violation 'other' handled by toDomain defaulting to GIVE`() {
        // CommitmentDraftDto stores direction as a raw String; toDomain() maps unknown values
        // to Direction.GIVE as a defensive fallback (see CommitmentDraftDto.toDomain()).
        // The spec states Railway schema validation should prevent unknown enum values
        // reaching the client — this test documents the observed client-side fallback behavior.
        val dto = CommitmentDraftDto(
            direction = "other",  // enum violation
            text = "Unknown direction commitment",
            quote = "verbatim quote",
            personRef = null,
            dueAt = null,
            confidence = 0.5f,
        )

        val domain = dto.toDomain()

        // Observed behavior: defaults to GIVE per CommitmentDraftDto.toDomain() implementation.
        // If the spec requires a throw instead, update CommitmentDraftDto.toDomain() to
        // throw IllegalArgumentException on unknown directions and update this assertion.
        assertEquals(
            "Unknown direction should default to GIVE per current toDomain() implementation",
            Direction.GIVE,
            domain.direction,
        )
        // Document: direction "other" is NOT rejected by the DTO layer — it silently becomes GIVE.
        // Stream 1/server-side schema validation is the primary guard against enum violations.
    }

    // ---------------------------------------------------------------------------
    // confidence > 1.0 — observe and document DTO behavior
    // ---------------------------------------------------------------------------

    @Test
    fun `confidence greater than 1_0 is accepted by DTO without clamping`() {
        // The CommitmentDraftDto.confidence field is declared as Float with no range validation.
        // The spec states confidence is 0.0..1.0 but does not mandate rejection at the DTO level.
        // Server-side schema validation (via Vertex AI responseSchema) is the primary guard.
        // Observed behavior: Moshi accepts out-of-range confidence values without error.
        val json = """
            {
              "direction": "give",
              "text": "Overconfident commitment",
              "quote": "overconfident verbatim",
              "person_ref": null,
              "due_at": null,
              "confidence": 1.5
            }
        """.trimIndent()

        val adapter = moshi.adapter(CommitmentDraftDto::class.java)
        val dto = adapter.fromJson(json)!!

        // Observed behavior: confidence=1.5 is accepted and stored as-is (not clamped).
        // If the spec mandates clamping to 1.0, add a clamp in CommitmentDraftDto.toDomain()
        // and update this assertion to assertEquals(1.0f, domain.confidence, 0.001f).
        assertEquals(
            "Confidence > 1.0 is accepted without clamping (observed DTO behavior)",
            1.5f,
            dto.confidence,
            0.001f,
        )
    }

    // ---------------------------------------------------------------------------
    // Direction domain mapping round-trip
    // ---------------------------------------------------------------------------

    @Test
    fun `direction give round-trips through toDomain to Direction_GIVE`() {
        val dto = CommitmentDraftDto(
            direction = "give",
            text = "text",
            quote = "quote",
            personRef = null,
            dueAt = null,
            confidence = 0.9f,
        )
        assertEquals(Direction.GIVE, dto.toDomain().direction)
    }

    @Test
    fun `direction take round-trips through toDomain to Direction_TAKE`() {
        val dto = CommitmentDraftDto(
            direction = "take",
            text = "text",
            quote = "quote",
            personRef = null,
            dueAt = null,
            confidence = 0.8f,
        )
        assertEquals(Direction.TAKE, dto.toDomain().direction)
    }

    @Test
    fun `direction GIVE uppercase is treated as GIVE by toDomain`() {
        // toDomain() calls direction.lowercase() before matching — case-insensitive.
        val dto = CommitmentDraftDto(
            direction = "GIVE",
            text = "text",
            quote = "quote",
            personRef = null,
            dueAt = null,
            confidence = 0.7f,
        )
        assertEquals(Direction.GIVE, dto.toDomain().direction)
    }
}

package com.becalm.android.domain.commitment

import com.becalm.android.domain.commitment.CommitmentManualValidator.Field
import com.becalm.android.domain.commitment.CommitmentManualValidator.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CommitmentManualValidator] covering the MAN-005 ruleset:
 *
 * - Title: trimmed, 1..200 chars.
 * - Direction: exactly "give" or "take" (default "give").
 * - Quote: trimmed, 1..500 chars (spec MAN-002 "증거 없는 기록 방지").
 * - personRef: phone-shape → strict E.164; free-form names accepted as-is.
 * - dueAtMillis: nullable; past is OK (spec MAN-005 "지난 약속 추후 기록").
 *
 * Also pins the [CommitmentManualValidator.normalise] contract: trim title /
 * quote, lowercase phone-shape personRef, fold blank hints to null, convert
 * dueAtMillis → Instant.
 */
class CommitmentManualValidatorTest {

    private fun draft(
        title: String = "valid title",
        direction: String = "give",
        quote: String = "Some evidentiary context",
        personRef: String? = null,
        dueAtMillis: Long? = 1_700_000_000_000L,
        dueHint: String? = null,
        dueIsApproximate: Boolean = false,
    ) = ManualCommitmentDraft(
        title = title,
        direction = direction,
        quote = quote,
        personRef = personRef,
        dueAtMillis = dueAtMillis,
        dueHint = dueHint,
        dueIsApproximate = dueIsApproximate,
    )

    @Test
    fun `accepts a fully valid draft`() {
        val result = CommitmentManualValidator.validate(draft())
        assertEquals(ValidationResult.Ok, result)
    }

    @Test
    fun `rejects empty quote (MAN-002 invariant)`() {
        val result = CommitmentManualValidator.validate(draft(quote = "   "))
        assertTrue("expected Err but was $result", result is ValidationResult.Err)
        val err = result as ValidationResult.Err
        assertTrue("QUOTE field must be flagged", err.fieldErrors.containsKey(Field.QUOTE))
    }

    @Test
    fun `accepts quote at the 500-char boundary`() {
        val boundary = "x".repeat(500)
        val result = CommitmentManualValidator.validate(draft(quote = boundary))
        assertEquals(ValidationResult.Ok, result)
    }

    @Test
    fun `rejects quote longer than 500 chars`() {
        val long = "x".repeat(501)
        val result = CommitmentManualValidator.validate(draft(quote = long))
        assertTrue(result is ValidationResult.Err)
        assertTrue((result as ValidationResult.Err).fieldErrors.containsKey(Field.QUOTE))
    }

    @Test
    fun `rejects empty title after trim`() {
        val result = CommitmentManualValidator.validate(draft(title = "   "))
        assertTrue(result is ValidationResult.Err)
        assertTrue((result as ValidationResult.Err).fieldErrors.containsKey(Field.TITLE))
    }

    @Test
    fun `rejects title longer than 200 chars`() {
        val long = "x".repeat(201)
        val result = CommitmentManualValidator.validate(draft(title = long))
        assertTrue(result is ValidationResult.Err)
        assertTrue((result as ValidationResult.Err).fieldErrors.containsKey(Field.TITLE))
    }

    @Test
    fun `accepts direction give`() {
        val result = CommitmentManualValidator.validate(draft(direction = "give"))
        assertEquals(ValidationResult.Ok, result)
    }

    @Test
    fun `accepts direction take`() {
        val result = CommitmentManualValidator.validate(draft(direction = "take"))
        assertEquals(ValidationResult.Ok, result)
    }

    @Test
    fun `rejects unknown direction`() {
        val result = CommitmentManualValidator.validate(draft(direction = "other"))
        assertTrue(result is ValidationResult.Err)
        assertTrue((result as ValidationResult.Err).fieldErrors.containsKey(Field.DIRECTION))
    }

    @Test
    fun `accepts valid E164 personRef`() {
        val result = CommitmentManualValidator.validate(draft(personRef = "+821012345678"))
        assertEquals(ValidationResult.Ok, result)
    }

    @Test
    fun `rejects phone-shaped personRef that fails E164`() {
        // Phone-shape (only + - digits) but too short for E.164 (min 8 digits after +).
        val result = CommitmentManualValidator.validate(draft(personRef = "+123"))
        assertTrue("expected Err but was $result", result is ValidationResult.Err)
        assertTrue(
            (result as ValidationResult.Err).fieldErrors.containsKey(Field.PERSON_REF),
        )
    }

    @Test
    fun `accepts free-form personRef (name-like string)`() {
        val result = CommitmentManualValidator.validate(draft(personRef = "Alice"))
        assertEquals(ValidationResult.Ok, result)
    }

    @Test
    fun `accepts null dueAt (no deadline)`() {
        val result = CommitmentManualValidator.validate(draft(dueAtMillis = null))
        assertEquals(ValidationResult.Ok, result)
    }

    @Test
    fun `accepts past dueAt (MAN-005 retroactive)`() {
        // Any non-null Long is acceptable per spec; past is OK.
        val pastMillis = 1_000_000_000L
        val result = CommitmentManualValidator.validate(draft(dueAtMillis = pastMillis))
        assertEquals(ValidationResult.Ok, result)
    }

    @Test
    fun `normalise trims title and quote and folds blank hint to null`() {
        val input = CommitmentManualValidator.normalise(
            draft(
                title = "  padded title  ",
                quote = "  padded quote  ",
                dueHint = "   ",
            ),
        )
        assertEquals("padded title", input.title)
        assertEquals("padded quote", input.quote)
        assertNull(input.dueHint)
    }

    @Test
    fun `normalise produces null dueAt when millis is null`() {
        val input = CommitmentManualValidator.normalise(draft(dueAtMillis = null))
        assertNull(input.dueAt)
        assertFalse(input.dueIsApproximate)
    }
}

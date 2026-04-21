package com.becalm.android.domain.commitment

import com.becalm.android.domain.commitment.CommitmentEditValidator.Field
import com.becalm.android.domain.commitment.CommitmentEditValidator.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CommitmentEditValidator] covering the EDIT-004 ruleset:
 *
 * - Title: trimmed, 1..200 chars.
 * - dueAtMillis: nullable; any non-null Long is accepted (past is OK).
 * - personRef: phone-shape → strict E.164; free-form names accepted as-is.
 * - direction: exactly "give" or "take".
 *
 * Also pins down the [CommitmentEditValidator.normalise] contract: trim title,
 * lowercase personRef, and fold blank hints to null.
 */
class CommitmentEditValidatorTest {

    private fun draft(
        title: String = "valid title",
        dueAtMillis: Long? = 1_700_000_000_000L,
        dueHint: String? = null,
        dueIsApproximate: Boolean = false,
        personRef: String? = null,
        direction: String = "give",
    ) = CommitmentEditDraft(
        title = title,
        dueAtMillis = dueAtMillis,
        dueHint = dueHint,
        dueIsApproximate = dueIsApproximate,
        personRef = personRef,
        direction = direction,
    )

    @Test
    fun `accepts a fully valid draft`() {
        val result = CommitmentEditValidator.validate(draft())
        assertEquals(ValidationResult.Ok, result)
    }

    @Test
    fun `rejects empty title after trim`() {
        val result = CommitmentEditValidator.validate(draft(title = "   "))
        assertTrue("expected Err but was $result", result is ValidationResult.Err)
        val err = result as ValidationResult.Err
        assertTrue("TITLE field must be flagged", err.fieldErrors.containsKey(Field.TITLE))
    }

    @Test
    fun `rejects title longer than 200 chars`() {
        val long = "x".repeat(201)
        val result = CommitmentEditValidator.validate(draft(title = long))
        assertTrue(result is ValidationResult.Err)
        assertTrue((result as ValidationResult.Err).fieldErrors.containsKey(Field.TITLE))
    }

    @Test
    fun `accepts null dueAt (no deadline)`() {
        val result = CommitmentEditValidator.validate(draft(dueAtMillis = null))
        assertEquals(ValidationResult.Ok, result)
    }

    @Test
    fun `accepts free-form personRef (name-like string)`() {
        // Not phone-shaped → validator passes through unchanged.
        val result = CommitmentEditValidator.validate(draft(personRef = "Alice"))
        assertEquals(ValidationResult.Ok, result)
    }

    @Test
    fun `accepts valid E164 personRef`() {
        val result = CommitmentEditValidator.validate(draft(personRef = "+821012345678"))
        assertEquals(ValidationResult.Ok, result)
    }

    @Test
    fun `rejects phone-shaped non-E164 personRef`() {
        // Phone-shape characters only but missing the leading + / wrong length.
        val result = CommitmentEditValidator.validate(draft(personRef = "010-1234-5678"))
        assertTrue("expected Err but was $result", result is ValidationResult.Err)
        assertTrue(
            (result as ValidationResult.Err).fieldErrors.containsKey(Field.PERSON_REF),
        )
    }

    @Test
    fun `rejects invalid direction`() {
        val result = CommitmentEditValidator.validate(draft(direction = "owe"))
        assertTrue(result is ValidationResult.Err)
        assertTrue((result as ValidationResult.Err).fieldErrors.containsKey(Field.DIRECTION))
    }

    @Test
    fun `normalise trims title, folds blank hint, lowercases personRef`() {
        val patch = CommitmentEditValidator.normalise(
            draft(
                title = "  Pay rent  ",
                dueHint = "   ",
                personRef = " Alice ",
                direction = "take",
            ),
        )
        assertEquals("Pay rent", patch.title)
        assertNull("blank hint must fold to null", patch.dueHint)
        assertEquals("alice", patch.personRef)
        assertEquals("take", patch.direction)
    }

    @Test
    fun `normalise converts null dueAtMillis to null Instant`() {
        val patch = CommitmentEditValidator.normalise(draft(dueAtMillis = null))
        assertNull(patch.dueAt)
    }
}

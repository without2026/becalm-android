package com.becalm.android.unit.domain.commitment

import com.becalm.android.domain.commitment.CommitmentEditDraft
import com.becalm.android.domain.commitment.CommitmentEditPatch
import com.becalm.android.domain.commitment.CommitmentEditValidator
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class CommitmentEditValidatorSpecTest {

    @Test
    fun `EDIT-004 accepts trimmed title with null deadline and free-form person ref`() {
        val draft = validDraft(
            title = "  분기 보고서 전달  ",
            dueAtMillis = null,
            personRef = "  김철수 팀장  ",
        )

        assertEquals(CommitmentEditValidator.ValidationResult.Ok, CommitmentEditValidator.validate(draft))
        assertEquals(
            CommitmentEditPatch(
                title = "분기 보고서 전달",
                dueAt = null,
                dueHint = null,
                dueIsApproximate = false,
                personRef = "김철수 팀장",
                direction = "give",
            ),
            CommitmentEditValidator.normalise(draft),
        )
    }

    @Test
    fun `EDIT-004 rejects blank title after whitespace trim`() {
        val result = CommitmentEditValidator.validate(validDraft(title = "   "))

        assertEquals(
            CommitmentEditValidator.ValidationResult.Err(
                mapOf(CommitmentEditValidator.Field.TITLE to "Title must not be empty"),
            ),
            result,
        )
    }

    @Test
    fun `EDIT-004 accepts title at 200 chars and rejects 201 chars`() {
        assertEquals(
            CommitmentEditValidator.ValidationResult.Ok,
            CommitmentEditValidator.validate(validDraft(title = "a".repeat(200))),
        )
        assertEquals(
            CommitmentEditValidator.ValidationResult.Err(
                mapOf(CommitmentEditValidator.Field.TITLE to "Title must be at most 200 characters"),
            ),
            CommitmentEditValidator.validate(validDraft(title = "a".repeat(201))),
        )
    }

    @Test
    fun `EDIT-004 accepts past deadlines and preserves epoch millis`() {
        val draft = validDraft(dueAtMillis = 1L)

        assertEquals(CommitmentEditValidator.ValidationResult.Ok, CommitmentEditValidator.validate(draft))
        assertEquals(Instant.fromEpochMilliseconds(1L), CommitmentEditValidator.normalise(draft).dueAt)
    }

    @Test
    fun `EDIT-004 normalises due hint blank to null and trims populated hint`() {
        assertEquals(
            null,
            CommitmentEditValidator.normalise(validDraft(dueHint = "   ")).dueHint,
        )
        assertEquals(
            "월말까지",
            CommitmentEditValidator.normalise(validDraft(dueHint = "  월말까지  ")).dueHint,
        )
    }

    @Test
    fun `EDIT-004 lowercases email person ref and nulls blank person ref`() {
        assertEquals(
            "lee@corp.com",
            CommitmentEditValidator.normalise(validDraft(personRef = "  LEE@CORP.COM  ")).personRef,
        )
        assertEquals(
            null,
            CommitmentEditValidator.normalise(validDraft(personRef = "   ")).personRef,
        )
    }

    @Test
    fun `EDIT-004 accepts phone-shaped E164 after whitespace and hyphen compaction`() {
        val draft = validDraft(personRef = " +82 10-1234-5678 ")

        assertEquals(CommitmentEditValidator.ValidationResult.Ok, CommitmentEditValidator.validate(draft))
        assertEquals("+82 10-1234-5678", CommitmentEditValidator.normalise(draft).personRef)
    }

    @Test
    fun `EDIT-004 rejects phone-shaped refs that are not valid E164`() {
        val result = CommitmentEditValidator.validate(validDraft(personRef = "010-1234-5678"))

        assertEquals(
            CommitmentEditValidator.ValidationResult.Err(
                mapOf(
                    CommitmentEditValidator.Field.PERSON_REF to
                        "Phone-shaped person reference must be valid E.164 (e.g. +821012345678)",
                ),
            ),
            result,
        )
    }

    @Test
    fun `EDIT-004 rejects direction outside give or take`() {
        val result = CommitmentEditValidator.validate(validDraft(direction = "pending"))

        assertEquals(
            CommitmentEditValidator.ValidationResult.Err(
                mapOf(CommitmentEditValidator.Field.DIRECTION to "Direction must be 'give' or 'take'"),
            ),
            result,
        )
    }

    private fun validDraft(
        title: String = "보고서 전달",
        dueAtMillis: Long? = 1_710_000_000_000L,
        dueHint: String? = null,
        dueIsApproximate: Boolean = false,
        personRef: String? = "lee@corp.com",
        direction: String = "give",
    ): CommitmentEditDraft = CommitmentEditDraft(
        title = title,
        dueAtMillis = dueAtMillis,
        dueHint = dueHint,
        dueIsApproximate = dueIsApproximate,
        personRef = personRef,
        direction = direction,
    )
}

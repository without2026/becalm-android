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
            counterpartyRef = "  김철수 팀장  ",
        )

        assertEquals(CommitmentEditValidator.ValidationResult.Ok, CommitmentEditValidator.validate(draft))
        assertEquals(
            CommitmentEditPatch(
                title = "분기 보고서 전달",
                dueAt = null,
                dueHint = null,
                dueIsApproximate = false,
                counterpartyRef = "김철수 팀장",
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
                mapOf(CommitmentEditValidator.Field.TITLE to CommitmentEditValidator.Error.TITLE_REQUIRED),
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
                mapOf(CommitmentEditValidator.Field.TITLE to CommitmentEditValidator.Error.TITLE_TOO_LONG),
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
            CommitmentEditValidator.normalise(validDraft(counterpartyRef = "  LEE@CORP.COM  ")).counterpartyRef,
        )
        assertEquals(
            null,
            CommitmentEditValidator.normalise(validDraft(counterpartyRef = "   ")).counterpartyRef,
        )
    }

    @Test
    fun `EDIT-004 accepts phone-shaped E164 after whitespace and hyphen compaction`() {
        val draft = validDraft(counterpartyRef = " +82 10-1234-5678 ")

        assertEquals(CommitmentEditValidator.ValidationResult.Ok, CommitmentEditValidator.validate(draft))
        assertEquals("+82 10-1234-5678", CommitmentEditValidator.normalise(draft).counterpartyRef)
    }

    @Test
    fun `EDIT-004 rejects phone-shaped refs that are not valid E164`() {
        val result = CommitmentEditValidator.validate(validDraft(counterpartyRef = "010-1234-5678"))

        assertEquals(
            CommitmentEditValidator.ValidationResult.Err(
                mapOf(
                    CommitmentEditValidator.Field.PERSON_REF to CommitmentEditValidator.Error.PERSON_REF_INVALID,
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
                mapOf(CommitmentEditValidator.Field.DIRECTION to CommitmentEditValidator.Error.DIRECTION_INVALID),
            ),
            result,
        )
    }

    private fun validDraft(
        title: String = "보고서 전달",
        dueAtMillis: Long? = 1_710_000_000_000L,
        dueHint: String? = null,
        dueIsApproximate: Boolean = false,
        counterpartyRef: String? = "lee@corp.com",
        direction: String = "give",
    ): CommitmentEditDraft = CommitmentEditDraft(
        title = title,
        dueAtMillis = dueAtMillis,
        dueHint = dueHint,
        dueIsApproximate = dueIsApproximate,
        counterpartyRef = counterpartyRef,
        direction = direction,
    )
}

package com.becalm.android.unit.domain.commitment

import com.becalm.android.domain.commitment.CommitmentManualValidator
import com.becalm.android.domain.commitment.ManualCommitmentDraft
import com.becalm.android.domain.commitment.ManualCommitmentInput
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class CommitmentManualValidatorSpecTest {

    @Test
    fun `MAN-005 accepts trimmed manual draft and normalises user input`() {
        val draft = validDraft(
            title = "  월요일 보고서 전달  ",
            quote = "  3월 15일 팀 미팅에서 월요일 오전까지 전달하기로 함  ",
            counterpartyRef = "  LEE@CORP.COM  ",
            dueHint = "  월요일 오전  ",
        )

        assertEquals(CommitmentManualValidator.ValidationResult.Ok, CommitmentManualValidator.validate(draft))
        assertEquals(
            ManualCommitmentInput(
                title = "월요일 보고서 전달",
                direction = "give",
                quote = "3월 15일 팀 미팅에서 월요일 오전까지 전달하기로 함",
                counterpartyRef = "lee@corp.com",
                dueAt = Instant.fromEpochMilliseconds(1_710_000_000_000L),
                dueHint = "월요일 오전",
                dueIsApproximate = false,
            ),
            CommitmentManualValidator.normalise(draft),
        )
    }

    @Test
    fun `MAN-005 rejects blank title and blank quote after trim`() {
        assertEquals(
            CommitmentManualValidator.ValidationResult.Err(
                mapOf(CommitmentManualValidator.Field.TITLE to "Title must not be empty"),
            ),
            CommitmentManualValidator.validate(validDraft(title = "  ")),
        )
        assertEquals(
            CommitmentManualValidator.ValidationResult.Err(
                mapOf(CommitmentManualValidator.Field.QUOTE to "Quote must not be empty"),
            ),
            CommitmentManualValidator.validate(validDraft(quote = "  ")),
        )
    }

    @Test
    fun `MAN-005 enforces 200 char title and 500 char quote boundaries`() {
        assertEquals(
            CommitmentManualValidator.ValidationResult.Ok,
            CommitmentManualValidator.validate(validDraft(title = "a".repeat(200), quote = "q".repeat(500))),
        )
        assertEquals(
            CommitmentManualValidator.ValidationResult.Err(
                mapOf(CommitmentManualValidator.Field.TITLE to "Title must be at most 200 characters"),
            ),
            CommitmentManualValidator.validate(validDraft(title = "a".repeat(201))),
        )
        assertEquals(
            CommitmentManualValidator.ValidationResult.Err(
                mapOf(CommitmentManualValidator.Field.QUOTE to "Quote must be at most 500 characters"),
            ),
            CommitmentManualValidator.validate(validDraft(quote = "q".repeat(501))),
        )
    }

    @Test
    fun `MAN-005 rejects invalid direction and invalid phone-shaped person ref`() {
        assertEquals(
            CommitmentManualValidator.ValidationResult.Err(
                mapOf(CommitmentManualValidator.Field.DIRECTION to "Direction must be 'give' or 'take'"),
            ),
            CommitmentManualValidator.validate(validDraft(direction = "other")),
        )
        assertEquals(
            CommitmentManualValidator.ValidationResult.Err(
                mapOf(
                    CommitmentManualValidator.Field.PERSON_REF to
                        "Phone-shaped person reference must be valid E.164 (e.g. +821012345678)",
                ),
            ),
            CommitmentManualValidator.validate(validDraft(counterpartyRef = "010-2222-3333")),
        )
    }

    @Test
    fun `MAN-005 accepts missing person ref missing deadline and past deadline`() {
        assertEquals(
            CommitmentManualValidator.ValidationResult.Ok,
            CommitmentManualValidator.validate(validDraft(counterpartyRef = null, dueAtMillis = null)),
        )
        assertEquals(
            CommitmentManualValidator.ValidationResult.Ok,
            CommitmentManualValidator.validate(validDraft(dueAtMillis = 1L)),
        )
    }

    private fun validDraft(
        title: String = "보고서 전달",
        direction: String = "give",
        quote: String = "김대리와 월요일 보고서를 전달하기로 약속함",
        counterpartyRef: String? = "lee@corp.com",
        dueAtMillis: Long? = 1_710_000_000_000L,
        dueHint: String? = null,
        dueIsApproximate: Boolean = false,
    ): ManualCommitmentDraft = ManualCommitmentDraft(
        title = title,
        direction = direction,
        quote = quote,
        counterpartyRef = counterpartyRef,
        dueAtMillis = dueAtMillis,
        dueHint = dueHint,
        dueIsApproximate = dueIsApproximate,
    )
}

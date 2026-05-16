package com.becalm.android.worker

import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.SelfIdentityAnchorEntity
import com.becalm.android.data.remote.dto.DecisionStatus
import com.becalm.android.data.remote.dto.SourceExtractedItemDto
import com.becalm.android.data.remote.dto.SourceExtractedParticipantDto
import com.becalm.android.data.remote.dto.SourceExtractionResponse
import com.becalm.android.data.remote.dto.SourceExtractedItemType
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.commitment.CommitmentDisplayPolicy
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Checkpoint4ExtractionPersistenceSpecTest {

    @Test
    fun `E2E-044 extracted commitments and participants map atomically onto shared source event identifiers`() {
        val item = SourceExtractedItemDto(
            type = SourceExtractedItemType.ACTION,
            text = "금요일까지 제안서 보내기",
            quote = "금요일까지 제안서 보내겠습니다.",
            counterpartyRef = "partner@acme.kr",
            dueAt = Instant.parse("2026-05-08T08:00:00Z"),
            confidence = 0.91f,
            direction = "give",
        )
        val participant = SourceExtractedParticipantDto(
            role = "recipient",
            relationToUser = "counterparty",
            identityType = "email",
            normalizedValue = "partner@acme.kr",
            email = "partner@acme.kr",
            confidence = 0.95,
        )

        val commitment = item.toTrackableCommitmentEntity(
            rawEventId = RAW_EVENT_ID,
            index = 0,
            userId = USER_ID,
            sourceRef = "gmail-message",
            sourceType = SourceType.GMAIL,
            sourceEventTitle = "제안서 follow-up",
            sourceEventOccurredAt = OCCURRED_AT,
            now = NOW,
        )
        val sourceParticipant = participant.toSourceEventParticipantEntity(
            userId = USER_ID,
            sourceEventId = RAW_EVENT_ID,
            sourceType = SourceType.GMAIL,
            sourceRef = "gmail-message",
            index = 0,
            now = NOW,
        )
        val commitmentParticipant = commitment.toCommitmentParticipantEntity(
            userId = USER_ID,
            index = 0,
            fallbackPersonId = sourceParticipant.personId,
            now = NOW,
        )

        assertEquals(CommitmentItemType.ACTION, commitment.itemType)
        assertEquals("give", commitment.direction)
        assertEquals(SourceType.GMAIL, commitment.sourceType)
        assertEquals("gmail-message", commitment.sourceRef)
        assertEquals(RAW_EVENT_ID, sourceParticipant.sourceEventId)
        assertEquals("resolved", sourceParticipant.resolutionStatus)
        assertNotNull(commitmentParticipant)
        assertEquals("give", commitmentParticipant?.role)
        assertEquals(sourceParticipant.personId, commitmentParticipant?.personId)
    }

    @Test
    fun `E2E-045 no-action extraction response still preserves source participant matching signals`() {
        val response = SourceExtractionResponse(
            rawEventId = RAW_EVENT_ID,
            items = emptyList(),
            sourceEventParticipants = listOf(
                SourceExtractedParticipantDto(
                    role = "sender",
                    relationToUser = "counterparty",
                    identityType = "email",
                    normalizedValue = "partner@acme.kr",
                    email = "partner@acme.kr",
                    confidence = 0.94,
                ),
            ),
            model = "gemini-test",
            region = "us-central1",
        )

        val participant = response.sourceEventParticipants.single().toSourceEventParticipantEntity(
            userId = USER_ID,
            sourceEventId = response.rawEventId,
            sourceType = SourceType.GMAIL,
            sourceRef = "gmail-message",
            index = 0,
            now = NOW,
        )

        assertTrue(response.items.isEmpty())
        assertEquals(RAW_EVENT_ID, participant.sourceEventId)
        assertEquals("counterparty", participant.relationToUser)
        assertEquals("resolved", participant.resolutionStatus)
    }

    @Test
    fun `name-only extracted participants stay unresolved for user confirmation`() {
        val participant = SourceExtractedParticipantDto(
            role = "speaker",
            relationToUser = "participant",
            identityType = "name",
            normalizedValue = "김민홍",
            displayName = "김민홍",
            organization = "Acme",
            title = "PM",
            evidence = "김민홍 PM이 renewal 범위를 확인했습니다.",
            confidence = 0.74,
        ).toSourceEventParticipantEntity(
            userId = USER_ID,
            sourceEventId = RAW_EVENT_ID,
            sourceType = SourceType.MEETING,
            sourceRef = "meeting-audio",
            index = 0,
            now = NOW,
        )

        assertEquals(null, participant.personId)
        assertEquals("unresolved", participant.resolutionStatus)
        assertEquals("name", participant.identityType)
        assertEquals("김민홍", participant.displayNameRaw)
    }

    @Test
    fun `self identity email anchor prevents extracted participant from becoming counterparty person`() {
        val participant = SourceExtractedParticipantDto(
            role = "sender",
            relationToUser = "counterparty",
            identityType = "email",
            normalizedValue = "me@example.com",
            email = "Me <ME@example.com>",
            displayName = "Me",
            confidence = 0.96,
        ).toSourceEventParticipantEntity(
            userId = USER_ID,
            sourceEventId = RAW_EVENT_ID,
            sourceType = SourceType.GMAIL,
            sourceRef = "gmail-message",
            index = 0,
            now = NOW,
            selfIdentityAnchors = listOf(
                selfAnchor(
                    id = "anchor-email",
                    anchorType = "provider_email",
                    normalizedValue = "me@example.com",
                ),
            ),
        )

        assertEquals(null, participant.personId)
        assertEquals("self", participant.relationToUser)
        assertEquals("self_resolved", participant.resolutionStatus)
    }

    @Test
    fun `self speaker anchor prevents meeting label from becoming participant person`() {
        val participant = SourceExtractedParticipantDto(
            role = "speaker",
            relationToUser = "participant",
            identityType = "speaker_label",
            normalizedValue = "SPEAKER_01",
            displayName = "SPEAKER_01",
            confidence = 0.9,
        ).toSourceEventParticipantEntity(
            userId = USER_ID,
            sourceEventId = RAW_EVENT_ID,
            sourceType = SourceType.MEETING,
            sourceRef = "meeting-audio",
            index = 0,
            now = NOW,
            selfIdentityAnchors = listOf(
                selfAnchor(
                    id = "anchor-speaker",
                    anchorType = "speaker_label",
                    normalizedValue = "speaker_01",
                ),
            ),
        )

        assertEquals(null, participant.personId)
        assertEquals("self", participant.relationToUser)
        assertEquals("self_resolved", participant.resolutionStatus)
    }

    @Test
    fun `self alias anchor stays suggested for user confirmation without creating person`() {
        val participant = SourceExtractedParticipantDto(
            role = "sender",
            relationToUser = "counterparty",
            identityType = "name",
            normalizedValue = "jake",
            displayName = "Jake",
            confidence = 0.78,
        ).toSourceEventParticipantEntity(
            userId = USER_ID,
            sourceEventId = RAW_EVENT_ID,
            sourceType = SourceType.GMAIL,
            sourceRef = "gmail-message",
            index = 0,
            now = NOW,
            selfIdentityAnchors = listOf(
                selfAnchor(
                    id = "anchor-alias",
                    anchorType = "alias",
                    normalizedValue = "jake",
                ),
            ),
        )

        assertEquals(null, participant.personId)
        assertEquals("counterparty", participant.relationToUser)
        assertEquals("suggested_self", participant.resolutionStatus)
    }

    @Test
    fun `self identity email anchor prevents commitment counterparty participant creation`() {
        val commitment = SourceExtractedItemDto(
            type = SourceExtractedItemType.ACTION,
            text = "내가 제안서 보내기",
            quote = "제가 제안서 보내겠습니다.",
            counterpartyRef = "me@example.com",
            dueAt = null,
            confidence = 0.91f,
            direction = "give",
        ).toTrackableCommitmentEntity(
            rawEventId = RAW_EVENT_ID,
            index = 0,
            userId = USER_ID,
            sourceRef = "gmail-message",
            sourceType = SourceType.GMAIL,
            sourceEventTitle = "제안서",
            sourceEventOccurredAt = OCCURRED_AT,
            now = NOW,
        )

        val participant = commitment.toCommitmentParticipantEntity(
            userId = USER_ID,
            index = 0,
            fallbackPersonId = "fallback-counterparty",
            now = NOW,
            selfIdentityAnchors = listOf(
                selfAnchor(
                    id = "anchor-email",
                    anchorType = "provider_email",
                    normalizedValue = "me@example.com",
                ),
            ),
        )

        assertEquals(null, participant)
    }

    @Test
    fun `E2E-051 decisions are retained as context items but excluded from primary UI feed loops`() {
        val decision = SourceExtractedItemDto(
            type = SourceExtractedItemType.DECISION,
            text = "A안을 진행하기로 합의",
            quote = "그럼 A안으로 진행하죠.",
            counterpartyRef = "partner@acme.kr",
            dueAt = null,
            confidence = 0.88f,
            direction = null,
            decisionStatus = DecisionStatus.APPROVED,
        ).toTrackableCommitmentEntity(
            rawEventId = RAW_EVENT_ID,
            index = 1,
            userId = USER_ID,
            sourceRef = "gmail-message",
            sourceType = SourceType.GMAIL,
            sourceEventTitle = "A안 결정",
            sourceEventOccurredAt = OCCURRED_AT,
            now = NOW,
        )

        assertEquals(CommitmentItemType.DECISION, decision.itemType)
        assertTrue(CommitmentDisplayPolicy.isDecisionContextItem(decision.itemType))
        assertFalse(CommitmentDisplayPolicy.isPrimaryFeedItem(decision.itemType))
        assertFalse(CommitmentDisplayPolicy.countsAsOpenPersonLoop(decision.itemType, decision.actionState))
    }

    private fun selfAnchor(
        id: String,
        anchorType: String,
        normalizedValue: String,
    ): SelfIdentityAnchorEntity =
        SelfIdentityAnchorEntity(
            id = id,
            userId = USER_ID,
            anchorType = anchorType,
            normalizedValue = normalizedValue,
            displayValue = normalizedValue,
            source = "test",
            scope = "global",
            sourceConnectionId = null,
            sourceEventId = null,
            trust = "verified",
            status = "active",
            createdAt = NOW,
            updatedAt = NOW,
        )

    private companion object {
        const val USER_ID = "user-1"
        const val RAW_EVENT_ID = "raw-event-1"
        val NOW: Instant = Instant.parse("2026-05-05T00:00:00Z")
        val OCCURRED_AT: Instant = Instant.parse("2026-05-04T02:00:00Z")
    }
}

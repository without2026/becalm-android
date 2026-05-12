package com.becalm.android.unit.domain.person

import com.becalm.android.domain.person.PersonMemoryCommitment
import com.becalm.android.domain.person.PersonMemoryIdentity
import com.becalm.android.domain.person.PersonMemoryInput
import com.becalm.android.domain.person.PersonMemoryInteraction
import com.becalm.android.domain.person.PersonMemoryParticipant
import com.becalm.android.domain.person.PersonMemorySemanticIndexBuilder
import kotlinx.datetime.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonMemorySemanticIndexBuilderSpecTest {

    @Test
    fun `builds bounded semantic terms from structured memory input without raw text blobs`() {
        val index = PersonMemorySemanticIndexBuilder.build(input())

        assertTrue(index.displayNameTerms.contains("jane"))
        assertTrue(index.aliases.contains("jane"))
        assertTrue(index.organizations.contains("acme"))
        assertTrue(index.titles.contains("ceo"))
        assertTrue(index.workTerms.contains("renewal"))
        assertTrue(index.openCommitmentTerms.contains("revised"))
        assertTrue(index.decisionTerms.contains("discount"))
        assertTrue(index.confirmedPatterns.any { it.contains("jane") && it.contains("renewal") })
        assertTrue(index.rejectedPatterns.any { it.contains("legacy") })
        assertTrue(index.recentSourceTypes.contains("gmail"))
        assertFalse(index.workTerms.any { it.contains("full transcript line") })
    }

    private fun input(): PersonMemoryInput =
        PersonMemoryInput(
            userId = "user-1",
            personId = "person-1",
            displayName = "Jane Kim",
            generatedAt = Instant.parse("2026-05-06T00:00:00Z"),
            identities = listOf(
                PersonMemoryIdentity(
                    identityType = "alias",
                    value = "Jane",
                    verified = true,
                    sourceRef = "raw:raw-1",
                ),
            ),
            participants = listOf(
                PersonMemoryParticipant(
                    sourceRef = "raw:raw-1",
                    sourceType = "gmail",
                    role = "sender",
                    relationToUser = "counterparty",
                    displayName = "Jane Kim",
                    organization = "Acme",
                    title = "CEO",
                    evidence = "Jane from Acme discussed renewal pricing. This is a full transcript line that should not be stored as one phrase.",
                    occurredAt = Instant.parse("2026-05-05T00:00:00Z"),
                ),
            ),
            interactions = listOf(
                PersonMemoryInteraction(
                    sourceRef = "raw:raw-1",
                    sourceType = "gmail",
                    interactionKind = "email",
                    title = "Pricing renewal",
                    snippet = "Jane asked for revised terms",
                    occurredAt = Instant.parse("2026-05-05T00:00:00Z"),
                ),
            ),
            commitments = listOf(
                PersonMemoryCommitment(
                    commitmentId = "commitment-1",
                    sourceRef = "commitment:commitment-1",
                    itemType = "action",
                    title = "Send revised terms",
                    status = "pending",
                    quote = "Please send revised terms by Friday",
                    occurredAt = Instant.parse("2026-05-05T00:00:00Z"),
                ),
                PersonMemoryCommitment(
                    commitmentId = "commitment-decision",
                    sourceRef = "commitment:commitment-decision",
                    itemType = "decision",
                    title = "Renewal discount approved",
                    status = "approved",
                    quote = "We approved the renewal discount",
                    occurredAt = Instant.parse("2026-05-05T00:00:00Z"),
                ),
            ),
            matchingNotes = listOf(
                "Confirmed semantic match Jane Kim Acme renewal",
                "Rejected legacy partner pattern",
            ),
        )
}

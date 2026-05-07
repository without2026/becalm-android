package com.becalm.android.unit.domain.person

import com.becalm.android.domain.person.PersonMemoryCommitment
import com.becalm.android.domain.person.PersonMemoryHash
import com.becalm.android.domain.person.PersonMemoryIdentity
import com.becalm.android.domain.person.PersonMemoryInput
import com.becalm.android.domain.person.PersonMemoryInteraction
import com.becalm.android.domain.person.PersonMemoryMarkdownBuilder
import com.becalm.android.domain.person.PersonMemoryMarkdownValidator
import com.becalm.android.domain.person.PersonMemoryParticipant
import com.becalm.android.domain.person.PersonMemoryPathResolver
import com.becalm.android.domain.person.PersonMemoryValidationError
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonMemoryMarkdownSpecTest {

    @Test
    fun `builder emits required frontmatter sections hash and source refs`() {
        val markdown = PersonMemoryMarkdownBuilder.build(input())
        val hash = PersonMemoryHash.sha256(markdown)

        assertTrue(markdown.startsWith("---\n"))
        assertTrue(markdown.contains("schema_version: 1"))
        assertTrue(markdown.contains("user_id: user-1"))
        assertTrue(markdown.contains("person_id: person-1"))
        assertTrue(markdown.contains("content_hash: $hash"))
        assertRequiredSections(markdown)
        assertTrue(markdown.contains("- email: jane@acme.com"))
        assertTrue(markdown.contains("### Decisions"))
        assertTrue(markdown.contains("- Renewal discount approved (decision, approved). [commitment:commitment-decision, 2026-05-05]"))
        assertFalse(markdown.contains("Acme CEO\n-")) // role/title must not appear as identity alias.

        val result = PersonMemoryMarkdownValidator.validate(
            markdown = markdown,
            expectedUserId = "user-1",
            expectedPersonId = "person-1",
        )
        assertEquals(emptyList<PersonMemoryValidationError>(), result.errors)
    }

    @Test
    fun `validator rejects person id mismatch`() {
        val markdown = PersonMemoryMarkdownBuilder.build(input()).replace(
            "person_id: person-1",
            "person_id: person-2",
        )

        val result = PersonMemoryMarkdownValidator.validate(
            markdown = markdown,
            expectedUserId = "user-1",
            expectedPersonId = "person-1",
        )

        assertTrue(result.errors.contains(PersonMemoryValidationError.PersonIdMismatch))
    }

    @Test
    fun `validator rejects hash mismatch`() {
        val markdown = PersonMemoryMarkdownBuilder.build(input()).replace(
            Regex("content_hash: [a-f0-9]{64}"),
            "content_hash: ${"0".repeat(64)}",
        )

        val result = PersonMemoryMarkdownValidator.validate(
            markdown = markdown,
            expectedUserId = "user-1",
            expectedPersonId = "person-1",
        )

        assertTrue(result.errors.contains(PersonMemoryValidationError.HashMismatch))
    }

    @Test
    fun `validator rejects factual bullets without source refs`() {
        val markdown = PersonMemoryMarkdownBuilder.build(input()).replace(
            "- Works with Acme as CEO. [raw:raw-1, 2026-05-05]",
            "- Works with Acme as CEO.",
        )

        val result = PersonMemoryMarkdownValidator.validate(
            markdown = markdown,
            expectedUserId = "user-1",
            expectedPersonId = "person-1",
        )

        assertTrue(result.errors.contains(PersonMemoryValidationError.MissingSourceRef))
    }

    @Test
    fun `validator rejects large original-like text blocks`() {
        val markdown = PersonMemoryMarkdownBuilder.build(input()).replace(
            "## Evidence References\n",
            "## Evidence References\n- raw:raw-big ${"transcript ".repeat(80)}\n",
        )

        val result = PersonMemoryMarkdownValidator.validate(
            markdown = markdown,
            expectedUserId = "user-1",
            expectedPersonId = "person-1",
        )

        assertTrue(result.errors.contains(PersonMemoryValidationError.EvidenceTooLong))
        assertTrue(result.errors.contains(PersonMemoryValidationError.ForbiddenOriginalLeak))
    }

    @Test
    fun `validator rejects markdown over size limit`() {
        val markdown = PersonMemoryMarkdownBuilder.build(input()).replace(
            "## Evidence References\n",
            "## Evidence References\n${"a".repeat(70_000)}",
        )

        val result = PersonMemoryMarkdownValidator.validate(
            markdown = markdown,
            expectedUserId = "user-1",
            expectedPersonId = "person-1",
        )

        assertTrue(result.errors.contains(PersonMemoryValidationError.TooLarge))
    }

    @Test
    fun `path resolver returns user hashed person scoped memory path`() {
        val path = PersonMemoryPathResolver.localRelativePath(
            userId = "user-1",
            personId = "person-1",
        )

        assertTrue(path.startsWith("person_memory/c6c289e49e9c05b2/person-1/"))
        assertEquals("memory.md", path.substringAfterLast('/'))
    }

    private fun assertRequiredSections(markdown: String) {
        listOf(
            "# Person Memory",
            "## Identity",
            "## Profile",
            "## Work Context",
            "## Recent Interactions",
            "## Matching Notes",
            "## Evidence References",
        ).forEach { section ->
            assertTrue("missing $section", markdown.contains(section))
        }
    }

    private fun input(): PersonMemoryInput =
        PersonMemoryInput(
            userId = "user-1",
            personId = "person-1",
            displayName = "Jane Kim",
            generatedAt = Instant.parse("2026-05-06T00:00:00Z"),
            identities = listOf(
                PersonMemoryIdentity(
                    identityType = "email",
                    value = "jane@acme.com",
                    verified = true,
                    sourceRef = "raw:raw-1",
                ),
                PersonMemoryIdentity(
                    identityType = "name",
                    value = "Acme CEO",
                    verified = false,
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
                    evidence = "Jane Kim, Acme CEO",
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
                "Confirmed semantic match: Jane Kim + Acme + renewal [raw:raw-1]",
            ),
        )
}

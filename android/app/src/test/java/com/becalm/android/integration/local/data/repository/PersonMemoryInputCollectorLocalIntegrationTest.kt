package com.becalm.android.integration.local.data.repository

import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.CommitmentParticipantEntity
import com.becalm.android.data.local.db.entity.PersonEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.repository.PersonMemoryInputCollector
import com.becalm.android.domain.person.PersonMemoryMarkdownBuilder
import com.becalm.android.domain.person.PersonMemoryMarkdownValidator
import com.becalm.android.domain.person.PersonMemoryValidationError
import com.becalm.android.integration.local.LocalIntegrationSupport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PersonMemoryInputCollectorLocalIntegrationTest {

    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val dispatcher = UnconfinedTestDispatcher()

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `collect builds memory input from canonical person graph only`() = runTest {
        db.personIndexDao().upsertPersons(
            listOf(
                person(id = PERSON_ID, displayName = "Jane Kim"),
                person(id = OTHER_PERSON_ID, displayName = "Other Person"),
            ),
        )
        db.personIndexDao().upsertIdentities(
            listOf(
                identity(id = "identity-email", personId = PERSON_ID, type = "email", rawValue = "Jane@Acme.com"),
                identity(id = "identity-name", personId = PERSON_ID, type = "name", rawValue = "Acme CEO", verified = false),
                identity(id = "identity-other", personId = OTHER_PERSON_ID, type = "email", rawValue = "other@example.com"),
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-1",
                    sourceEventId = "raw-1",
                    personId = PERSON_ID,
                    displayName = "Jane Kim",
                    organization = "Acme",
                ),
                sourceParticipant(
                    id = "participant-speaker",
                    sourceEventId = "meeting-1",
                    personId = PERSON_ID,
                    displayName = "SPEAKER_02",
                    organization = "",
                    sourceType = "meeting",
                    role = "speaker",
                    identityType = "speaker_label",
                    normalizedValue = "SPEAKER_02",
                    email = null,
                    title = null,
                    evidence = "금요일까지 자료를 공유하겠습니다.",
                ),
                sourceParticipant(
                    id = "participant-other",
                    sourceEventId = "raw-other",
                    personId = OTHER_PERSON_ID,
                    displayName = "Other Person",
                    organization = "Other Co",
                ),
            ),
        )
        db.personIndexDao().upsertInteractions(
            listOf(
                interaction(id = "interaction-raw", personId = PERSON_ID, sourceRef = "raw:raw-1", kind = "email"),
                interaction(
                    id = "interaction-commitment",
                    personId = PERSON_ID,
                    sourceRef = "commitment:commitment-1",
                    kind = "commitment",
                    title = "Send revised terms",
                ),
                interaction(
                    id = "interaction-decision",
                    personId = PERSON_ID,
                    sourceRef = "commitment:commitment-decision",
                    kind = "commitment",
                    title = "Renewal discount approved",
                ),
                interaction(id = "interaction-other", personId = OTHER_PERSON_ID, sourceRef = "raw:raw-other", kind = "email"),
            ),
        )
        db.personIndexDao().upsertCommitmentParticipants(
            listOf(
                commitmentParticipant(id = "commitment-participant-1", commitmentId = "commitment-1", personId = PERSON_ID),
                commitmentParticipant(
                    id = "commitment-participant-decision",
                    commitmentId = "commitment-decision",
                    personId = PERSON_ID,
                ),
                commitmentParticipant(id = "commitment-participant-other", commitmentId = "commitment-other", personId = OTHER_PERSON_ID),
            ),
        )
        db.commitmentDao().insertAll(
            listOf(
                commitment(id = "commitment-1", title = "Send revised terms", deletedAt = null),
                commitment(id = "commitment-decision", title = "Renewal discount approved", deletedAt = null).copy(
                    itemType = CommitmentItemType.DECISION,
                    direction = null,
                    decisionStatus = "approved",
                    actionState = "pending",
                    quote = "We approved the renewal discount",
                ),
                commitment(id = "commitment-deleted", title = "Deleted work", deletedAt = Instant.parse("2026-05-05T00:00:00Z")),
                commitment(id = "commitment-other", title = "Other work", deletedAt = null),
            ),
        )

        val input = requireNotNull(
            collector().collect(
                userId = USER_ID,
                personId = PERSON_ID,
                generatedAt = GENERATED_AT,
            ),
        )

        assertEquals("Jane Kim", input.displayName)
        assertEquals(listOf("email", "name"), input.identities.map { it.identityType })
        assertEquals(setOf("raw:raw-1", "raw:meeting-1"), input.participants.map { it.sourceRef }.toSet())
        assertTrue(input.participants.any { it.organization == "Acme" })
        assertTrue(input.participants.any { it.title == "CEO" })
        assertEquals(listOf("SPEAKER_02"), input.voiceEvidence.map { it.speakerLabel })
        assertTrue(input.voiceEvidence.single().chunkFileName.startsWith("voice_chunk_"))
        assertTrue(input.voiceEvidence.single().chunkFileName.endsWith(".m4a"))
        assertEquals(
            setOf("raw:raw-1", "commitment:commitment-1", "commitment:commitment-decision"),
            input.interactions.map { it.sourceRef }.toSet(),
        )
        assertEquals(setOf("commitment-1", "commitment-decision"), input.commitments.map { it.commitmentId }.toSet())
        assertEquals("approved", input.commitments.single { it.commitmentId == "commitment-decision" }.status)
        assertTrue(input.commitments.none { it.title == "Deleted work" || it.title == "Other work" })

        val markdown = PersonMemoryMarkdownBuilder.build(input)
        val validation = PersonMemoryMarkdownValidator.validate(
            markdown = markdown,
            expectedUserId = USER_ID,
            expectedPersonId = PERSON_ID,
        )
        assertEquals(emptyList<PersonMemoryValidationError>(), validation.errors)
    }

    @Test
    fun `collect returns null when person has no local evidence`() = runTest {
        assertNull(
            collector().collect(
                userId = USER_ID,
                personId = "missing-person",
                generatedAt = GENERATED_AT,
            ),
        )
    }

    @Test
    fun `collect falls back to identity display name when person row is absent`() = runTest {
        db.personIndexDao().upsertIdentities(
            listOf(
                identity(
                    id = "identity-fallback",
                    personId = PERSON_ID,
                    type = "email",
                    rawValue = "jane@acme.com",
                    displayNameHint = "Jane from Acme",
                ),
            ),
        )

        val input = collector().collect(
            userId = USER_ID,
            personId = PERSON_ID,
            generatedAt = GENERATED_AT,
        )

        assertNotNull(input)
        assertEquals("Jane from Acme", input?.displayName)
    }

    private fun collector(): PersonMemoryInputCollector =
        PersonMemoryInputCollector(
            personIndexDao = db.personIndexDao(),
            commitmentDao = db.commitmentDao(),
            ioDispatcher = dispatcher,
        )

    private fun person(id: String, displayName: String): PersonEntity =
        PersonEntity(
            id = id,
            userId = USER_ID,
            displayName = displayName,
            kind = "person",
            primaryEmail = null,
            primaryPhone = null,
            confidence = 0.95,
            createdAt = GENERATED_AT,
            updatedAt = GENERATED_AT,
            archivedAt = null,
        )

    private fun identity(
        id: String,
        personId: String,
        type: String,
        rawValue: String,
        verified: Boolean = true,
        displayNameHint: String? = "Jane Kim",
    ): PersonIdentityEntity {
        val normalized = rawValue.lowercase()
        return PersonIdentityEntity(
            id = id,
            userId = USER_ID,
            personId = personId,
            identityKey = "$type:$normalized",
            identityType = type,
            rawValue = rawValue,
            displayNameHint = displayNameHint,
            identityValue = rawValue,
            normalizedValue = normalized,
            displayName = displayNameHint,
            sourceType = "gmail",
            sourceRef = "raw:raw-1",
            confidence = if (verified) 0.95 else 0.45,
            isPrimary = verified,
            verified = verified,
            lastSeenAt = GENERATED_AT,
            createdAt = GENERATED_AT,
            updatedAt = GENERATED_AT,
        )
    }

    private fun sourceParticipant(
        id: String,
        sourceEventId: String,
        personId: String,
        displayName: String,
        organization: String,
        sourceType: String = "gmail",
        role: String = "sender",
        identityType: String? = "email",
        normalizedValue: String? = "jane@acme.com",
        email: String? = "jane@acme.com",
        title: String? = "CEO",
        evidence: String = "$displayName, $organization",
    ): SourceEventParticipantEntity =
        SourceEventParticipantEntity(
            id = id,
            userId = USER_ID,
            sourceEventId = sourceEventId,
            sourceType = sourceType,
            sourceRef = "$sourceType-$sourceEventId",
            personId = personId,
            role = role,
            relationToUser = "counterparty",
            identityType = identityType,
            normalizedValue = normalizedValue,
            displayNameRaw = displayName,
            emailRaw = email,
            phoneRaw = null,
            organizationRaw = organization,
            titleRaw = title,
            evidence = evidence,
            confidence = 0.9,
            resolutionStatus = "resolved",
            createdAt = GENERATED_AT,
        )

    private fun interaction(
        id: String,
        personId: String,
        sourceRef: String,
        kind: String,
        title: String = "Pricing renewal",
    ): PersonInteractionEntity =
        PersonInteractionEntity(
            id = id,
            userId = USER_ID,
            personId = personId,
            sourceType = "gmail",
            sourceRef = sourceRef,
            interactionKind = kind,
            role = "sender",
            direction = "inbound",
            status = "active",
            occurredAt = GENERATED_AT,
            title = title,
            snippet = "Jane asked for revised terms",
            confidence = 0.9,
            createdAt = GENERATED_AT,
        )

    private fun commitmentParticipant(id: String, commitmentId: String, personId: String): CommitmentParticipantEntity =
        CommitmentParticipantEntity(
            id = id,
            userId = USER_ID,
            commitmentId = commitmentId,
            personId = personId,
            role = "counterparty",
            evidence = "Please send revised terms",
            confidence = 0.9,
            createdAt = GENERATED_AT,
        )

    private fun commitment(id: String, title: String, deletedAt: Instant?): CommitmentEntity =
        CommitmentEntity(
            id = id,
            userId = USER_ID,
            itemType = CommitmentItemType.ACTION,
            direction = "give",
            scheduleStatus = null,
            decisionStatus = null,
            counterpartyRaw = "Jane Kim",
            counterpartyRef = "jane@acme.com",
            title = title,
            description = null,
            quote = "Please send revised terms by Friday",
            sourceEventTitle = "Pricing renewal",
            sourceEventOccurredAt = GENERATED_AT,
            dueAt = null,
            dueHint = null,
            dueIsApproximate = false,
            actionState = "pending",
            sourceType = "gmail",
            sourceRef = "raw:raw-1",
            confidence = 0.9,
            commitmentState = CommitmentLifecycleLegacy.DRAFT,
            syncStatus = "synced",
            createdAt = GENERATED_AT,
            updatedAt = GENERATED_AT,
            lastEditedBy = null,
            lastEditedAt = null,
            quoteDisputed = false,
            quoteDisputedAt = null,
            deletedAt = deletedAt,
            supersedesCommitmentId = null,
        )

    private companion object {
        const val USER_ID = "user-1"
        const val PERSON_ID = "person-1"
        const val OTHER_PERSON_ID = "person-other"
        val GENERATED_AT: Instant = Instant.parse("2026-05-06T00:00:00Z")
    }
}

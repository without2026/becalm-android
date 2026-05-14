package com.becalm.android.unit.domain.person

import com.becalm.android.domain.person.PersonMatchCandidate
import com.becalm.android.domain.person.PersonMatchDecision
import com.becalm.android.domain.person.PersonMatchIdentity
import com.becalm.android.domain.person.PersonMatchParticipant
import com.becalm.android.domain.person.PersonMatchingEngine
import com.becalm.android.domain.person.PersonSemanticContext
import com.becalm.android.domain.person.StaticPersonSemanticContextProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonMatchingEngineSpecTest {

    @Test
    fun `email exact match auto matches deterministically`() {
        val decision = engine().decide(
            participant = participant(email = "JANE@Acme.com"),
            candidates = listOf(
                candidate(
                    personId = "person-jane",
                    identities = listOf(identity("email", "jane@acme.com", verified = true)),
                ),
            ),
        )

        assertAuto(decision, "person-jane", "deterministic_identity")
    }

    @Test
    fun `phone exact match auto matches deterministically`() {
        val decision = engine().decide(
            participant = participant(phone = "+82 10-1234-5678"),
            candidates = listOf(
                candidate(
                    personId = "person-phone",
                    identities = listOf(identity("phone", "+821012345678", verified = true)),
                ),
            ),
        )

        assertAuto(decision, "person-phone", "deterministic_identity")
    }

    @Test
    fun `confirmed alias auto matches when unique`() {
        val decision = engine().decide(
            participant = participant(displayName = "Jane from Acme"),
            candidates = listOf(
                candidate(
                    personId = "person-jane",
                    identities = listOf(identity("alias", "Jane from Acme", verified = true)),
                ),
            ),
        )

        assertAuto(decision, "person-jane", "confirmed_alias")
    }

    @Test
    fun `name organization and title unique semantic bundle requires confirmation`() {
        val decision = engine(
            "person-jane" to context(
                organizations = setOf("Acme"),
                titles = setOf("CEO"),
                workContext = setOf("pricing renewal"),
            ),
        ).decide(
            participant = participant(
                displayName = "Jane Kim",
                organization = "Acme",
                title = "CEO",
                evidence = "Jane Kim, Acme CEO, discussed pricing renewal",
            ),
            candidates = listOf(candidate(personId = "person-jane", displayName = "Jane Kim")),
        )

        assertConfirmation(decision, listOf("person-jane"))
    }

    @Test
    fun `organization and title without name does not auto match`() {
        val decision = engine(
            "person-jane" to context(organizations = setOf("Acme"), titles = setOf("CEO")),
        ).decide(
            participant = participant(organization = "Acme", title = "CEO"),
            candidates = listOf(candidate(personId = "person-jane", displayName = "Jane Kim")),
        )

        assertTrue(decision is PersonMatchDecision.NoMatch)
    }

    @Test
    fun `name and organization without title requires confirmation`() {
        val decision = engine(
            "person-jane" to context(organizations = setOf("Acme")),
        ).decide(
            participant = participant(displayName = "Jane Kim", organization = "Acme"),
            candidates = listOf(candidate(personId = "person-jane", displayName = "Jane Kim")),
        )

        assertConfirmation(decision, listOf("person-jane"))
    }

    @Test
    fun `competing semantic candidates require confirmation`() {
        val decision = engine(
            "person-jane-1" to context(organizations = setOf("Acme"), titles = setOf("CEO")),
            "person-jane-2" to context(organizations = setOf("Acme"), titles = setOf("CEO")),
        ).decide(
            participant = participant(displayName = "Jane Kim", organization = "Acme", title = "CEO"),
            candidates = listOf(
                candidate(personId = "person-jane-1", displayName = "Jane Kim"),
                candidate(personId = "person-jane-2", displayName = "Jane Kim"),
            ),
        )

        assertConfirmation(decision, listOf("person-jane-1", "person-jane-2"))
    }

    @Test
    fun `confirmed semantic pattern requires confirmation when unique`() {
        val decision = engine(
            "person-jane" to context(confirmedPatterns = setOf("jane kim acme renewal")),
        ).decide(
            participant = participant(
                displayName = "Jane Kim",
                organization = "Acme",
                evidence = "Jane Kim from Acme followed up on the renewal.",
            ),
            candidates = listOf(candidate(personId = "person-jane", displayName = "Jane Kim")),
        )

        assertConfirmation(decision, listOf("person-jane"))
    }

    @Test
    // spec: RUX-007
    fun `speaker label never auto matches as canonical identity`() {
        val decision = engine().decide(
            participant = participant(displayName = "SPEAKER_01", evidence = "SPEAKER_01: 확인했습니다."),
            candidates = listOf(
                candidate(
                    personId = "person-speaker",
                    displayName = "SPEAKER_01",
                    identities = listOf(identity("speaker_label", "SPEAKER_01", verified = true)),
                ),
            ),
        )

        assertTrue(decision is PersonMatchDecision.NoMatch)
    }

    @Test
    fun `negative matching note suppresses repeated semantic suggestion`() {
        val decision = engine(
            "person-jane" to context(
                organizations = setOf("Acme"),
                titles = setOf("CEO"),
                rejectedPatterns = setOf("jane kim acme ceo"),
            ),
        ).decide(
            participant = participant(displayName = "Jane Kim", organization = "Acme", title = "CEO"),
            candidates = listOf(candidate(personId = "person-jane", displayName = "Jane Kim")),
        )

        assertTrue(decision is PersonMatchDecision.RejectedByNegativeFeedback)
        assertEquals("person-jane", (decision as PersonMatchDecision.RejectedByNegativeFeedback).personId)
    }

    private fun engine(vararg contexts: Pair<String, PersonSemanticContext>): PersonMatchingEngine =
        PersonMatchingEngine(StaticPersonSemanticContextProvider(contexts.toMap()))

    private fun participant(
        displayName: String? = null,
        email: String? = null,
        phone: String? = null,
        organization: String? = null,
        title: String? = null,
        evidence: String? = null,
    ): PersonMatchParticipant =
        PersonMatchParticipant(
            displayName = displayName,
            email = email,
            phone = phone,
            organization = organization,
            title = title,
            sourceType = "gmail",
            evidence = evidence,
        )

    private fun candidate(
        personId: String,
        displayName: String = personId,
        identities: List<PersonMatchIdentity> = emptyList(),
    ): PersonMatchCandidate =
        PersonMatchCandidate(
            personId = personId,
            displayName = displayName,
            identities = identities,
        )

    private fun identity(type: String, value: String, verified: Boolean): PersonMatchIdentity =
        PersonMatchIdentity(type = type, value = value, verified = verified)

    private fun context(
        organizations: Set<String> = emptySet(),
        titles: Set<String> = emptySet(),
        workContext: Set<String> = emptySet(),
        confirmedPatterns: Set<String> = emptySet(),
        rejectedPatterns: Set<String> = emptySet(),
    ): PersonSemanticContext =
        PersonSemanticContext(
            organizations = organizations,
            titles = titles,
            workContextTerms = workContext,
            confirmedPatterns = confirmedPatterns,
            rejectedPatterns = rejectedPatterns,
        )

    private fun assertAuto(decision: PersonMatchDecision, personId: String, reason: String) {
        assertTrue(decision is PersonMatchDecision.AutoMatched)
        val auto = decision as PersonMatchDecision.AutoMatched
        assertEquals(personId, auto.personId)
        assertEquals(reason, auto.reason)
    }

    private fun assertConfirmation(decision: PersonMatchDecision, personIds: List<String>) {
        assertTrue(decision is PersonMatchDecision.NeedsUserConfirmation)
        val confirmation = decision as PersonMatchDecision.NeedsUserConfirmation
        assertEquals(personIds, confirmation.candidates.map { it.personId })
    }
}

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

class Checkpoint4PersonMatchingSpecTest {

    @Test
    fun `E2E-046 one contact person auto matches whether source supplies email or phone`() {
        val candidate = PersonMatchCandidate(
            personId = "person-jane",
            displayName = "Jane Kim",
            identities = listOf(
                PersonMatchIdentity(type = "email", value = "jane@acme.kr", verified = true),
                PersonMatchIdentity(type = "phone", value = "+821012345678", verified = true),
            ),
        )
        val engine = PersonMatchingEngine(StaticPersonSemanticContextProvider(emptyMap()))

        val emailDecision = engine.decide(
            participant = participant(email = "JANE@acme.kr"),
            candidates = listOf(candidate),
        )
        val phoneDecision = engine.decide(
            participant = participant(phone = "010-1234-5678"),
            candidates = listOf(candidate),
        )

        assertAutoMatch(emailDecision, "person-jane")
        assertAutoMatch(phoneDecision, "person-jane")
    }

    @Test
    fun `name-only participant can be recommended from memory semantic index terms`() {
        val candidate = PersonMatchCandidate(
            personId = "person-jane",
            displayName = "Jane Kim",
            identities = emptyList(),
        )
        val engine = PersonMatchingEngine(
            StaticPersonSemanticContextProvider(
                mapOf(
                    "person-jane" to PersonSemanticContext(
                        organizations = setOf("acme"),
                        titles = setOf("ceo"),
                        openCommitmentTerms = setOf("revised", "terms"),
                        decisionTerms = setOf("renewal", "discount"),
                    ),
                ),
            ),
        )

        val decision = engine.decide(
            participant = PersonMatchParticipant(
                displayName = "Jane Kim",
                organization = "Acme",
                title = "CEO",
                evidence = "Jane mentioned the renewal discount and revised terms.",
                sourceType = "meeting",
            ),
            candidates = listOf(candidate),
        )

        assertTrue(decision is PersonMatchDecision.NeedsUserConfirmation)
        val scored = (decision as PersonMatchDecision.NeedsUserConfirmation).candidates.single()
        assertEquals("person-jane", scored.personId)
        assertTrue(scored.reasons.contains("open_commitment"))
        assertTrue(scored.reasons.contains("decision_context"))
    }

    private fun participant(
        email: String? = null,
        phone: String? = null,
    ): PersonMatchParticipant =
        PersonMatchParticipant(
            email = email,
            phone = phone,
            sourceType = "gmail",
        )

    private fun assertAutoMatch(decision: PersonMatchDecision, personId: String) {
        assertTrue(decision is PersonMatchDecision.AutoMatched)
        assertEquals(personId, (decision as PersonMatchDecision.AutoMatched).personId)
        assertEquals("deterministic_identity", decision.reason)
    }
}

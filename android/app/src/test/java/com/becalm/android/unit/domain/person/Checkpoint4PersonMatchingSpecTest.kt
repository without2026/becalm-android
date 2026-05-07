package com.becalm.android.unit.domain.person

import com.becalm.android.domain.person.PersonMatchCandidate
import com.becalm.android.domain.person.PersonMatchDecision
import com.becalm.android.domain.person.PersonMatchIdentity
import com.becalm.android.domain.person.PersonMatchParticipant
import com.becalm.android.domain.person.PersonMatchingEngine
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

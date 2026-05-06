package com.becalm.android.domain.person

import java.util.Locale

public data class PersonMatchParticipant(
    val displayName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val organization: String? = null,
    val title: String? = null,
    val sourceType: String? = null,
    val evidence: String? = null,
)

public data class PersonMatchIdentity(
    val type: String,
    val value: String,
    val verified: Boolean,
)

public data class PersonMatchCandidate(
    val personId: String,
    val displayName: String,
    val identities: List<PersonMatchIdentity> = emptyList(),
)

public data class PersonSemanticContext(
    val organizations: Set<String> = emptySet(),
    val titles: Set<String> = emptySet(),
    val workContextTerms: Set<String> = emptySet(),
    val confirmedPatterns: Set<String> = emptySet(),
    val rejectedPatterns: Set<String> = emptySet(),
)

public interface PersonSemanticContextProvider {
    public fun contextFor(personId: String): PersonSemanticContext
}

public object EmptyPersonSemanticContextProvider : PersonSemanticContextProvider {
    override fun contextFor(personId: String): PersonSemanticContext = PersonSemanticContext()
}

public class StaticPersonSemanticContextProvider(
    private val contextsByPersonId: Map<String, PersonSemanticContext>,
) : PersonSemanticContextProvider {
    override fun contextFor(personId: String): PersonSemanticContext =
        contextsByPersonId[personId] ?: PersonSemanticContext()
}

public sealed interface PersonMatchDecision {
    public data class AutoMatched(
        val personId: String,
        val confidence: Double,
        val reason: String,
    ) : PersonMatchDecision

    public data class NeedsUserConfirmation(
        val candidates: List<ScoredPersonMatchCandidate>,
        val reason: String,
    ) : PersonMatchDecision

    public data class RejectedByNegativeFeedback(
        val personId: String,
        val reason: String,
    ) : PersonMatchDecision

    public data object NoMatch : PersonMatchDecision
}

public data class ScoredPersonMatchCandidate(
    val personId: String,
    val confidence: Double,
    val reasons: List<String>,
)

public class PersonMatchingEngine(
    private val semanticContextProvider: PersonSemanticContextProvider = EmptyPersonSemanticContextProvider,
) {

    public fun decide(
        participant: PersonMatchParticipant,
        candidates: List<PersonMatchCandidate>,
    ): PersonMatchDecision {
        if (candidates.isEmpty()) return PersonMatchDecision.NoMatch

        deterministicMatch(participant, candidates)?.let { return it }
        confirmedAliasMatch(participant, candidates)?.let { return it }

        val scored = candidates
            .mapNotNull { candidate ->
                val context = semanticContextProvider.contextFor(candidate.personId)
                val score = scoreSemantic(participant, candidate, context)
                score.takeIf { it.confidence > 0.0 }
            }
            .sortedWith(compareByDescending<ScoredSemanticCandidate> { it.confidence }.thenBy { it.personId })

        if (scored.isEmpty()) return PersonMatchDecision.NoMatch

        val top = scored.first()
        if (top.negativeFeedbackMatched && top.hasStrongBundle) {
            return PersonMatchDecision.RejectedByNegativeFeedback(
                personId = top.personId,
                reason = "negative_feedback",
            )
        }

        val viable = scored
            .filterNot { it.negativeFeedbackMatched }
            .filter { it.confidence >= CONFIRMATION_THRESHOLD }

        if (viable.isEmpty()) return PersonMatchDecision.NoMatch

        val best = viable.first()
        val competitors = viable.filter { best.confidence - it.confidence < COMPETITION_MARGIN }
        if (best.hasStrongBundle && best.confidence >= AUTO_SEMANTIC_THRESHOLD && competitors.size == 1) {
            return PersonMatchDecision.AutoMatched(
                personId = best.personId,
                confidence = best.confidence,
                reason = "semantic_bundle",
            )
        }

        return PersonMatchDecision.NeedsUserConfirmation(
            candidates = competitors.map { it.toPublicCandidate() },
            reason = if (competitors.size > 1) "competing_candidates" else "semantic_confirmation_required",
        )
    }

    private fun deterministicMatch(
        participant: PersonMatchParticipant,
        candidates: List<PersonMatchCandidate>,
    ): PersonMatchDecision? {
        val participantEmail = PersonIdentityResolver.normalizeEmailAnchor(participant.email)
        val participantPhone = PersonIdentityResolver.normalizePhoneAnchor(participant.phone)
        if (participantEmail == null && participantPhone == null) return null

        val matches = candidates.filter { candidate ->
            candidate.identities.any { identity ->
                identity.verified && when (identity.type.normalized()) {
                    "email" -> participantEmail != null &&
                        PersonIdentityResolver.normalizeEmailAnchor(identity.value) == participantEmail
                    "phone" -> participantPhone != null &&
                        PersonIdentityResolver.normalizePhoneAnchor(identity.value) == participantPhone
                    else -> false
                }
            }
        }
        return when (matches.size) {
            0 -> null
            1 -> PersonMatchDecision.AutoMatched(
                personId = matches.single().personId,
                confidence = 1.0,
                reason = "deterministic_identity",
            )
            else -> PersonMatchDecision.NeedsUserConfirmation(
                candidates = matches.map {
                    ScoredPersonMatchCandidate(
                        personId = it.personId,
                        confidence = 1.0,
                        reasons = listOf("deterministic_identity_conflict"),
                    )
                },
                reason = "competing_deterministic_identities",
            )
        }
    }

    private fun confirmedAliasMatch(
        participant: PersonMatchParticipant,
        candidates: List<PersonMatchCandidate>,
    ): PersonMatchDecision? {
        val name = participant.displayName.normalized() ?: return null
        val matches = candidates.filter { candidate ->
            candidate.identities.any { identity ->
                identity.verified &&
                    identity.type.normalized() in CONFIRMED_ALIAS_TYPES &&
                    identity.value.normalized() == name
            }
        }
        return when (matches.size) {
            0 -> null
            1 -> PersonMatchDecision.AutoMatched(
                personId = matches.single().personId,
                confidence = 0.95,
                reason = "confirmed_alias",
            )
            else -> PersonMatchDecision.NeedsUserConfirmation(
                candidates = matches.map {
                    ScoredPersonMatchCandidate(
                        personId = it.personId,
                        confidence = 0.95,
                        reasons = listOf("confirmed_alias_conflict"),
                    )
                },
                reason = "competing_confirmed_aliases",
            )
        }
    }

    private fun scoreSemantic(
        participant: PersonMatchParticipant,
        candidate: PersonMatchCandidate,
        context: PersonSemanticContext,
    ): ScoredSemanticCandidate {
        val reasons = mutableListOf<String>()
        var confidence = 0.0

        val nameMatched = matchesName(participant.displayName, candidate).also {
            if (it) {
                confidence += 0.42
                reasons += "name"
            }
        }
        val organizationMatched = matchesAny(participant.organization, context.organizations).also {
            if (it) {
                confidence += 0.24
                reasons += "organization"
            }
        }
        val titleMatched = matchesAny(participant.title, context.titles).also {
            if (it) {
                confidence += 0.22
                reasons += "title"
            }
        }
        val workContextMatched = matchesEvidence(participant.evidence, context.workContextTerms).also {
            if (it) {
                confidence += 0.08
                reasons += "work_context"
            }
        }
        val confirmedPatternMatched = matchesPattern(participant, context.confirmedPatterns).also {
            if (it) {
                confidence = maxOf(confidence, 0.90)
                reasons += "confirmed_pattern"
            }
        }

        if (!nameMatched && (organizationMatched || titleMatched || workContextMatched)) {
            confidence = minOf(confidence, 0.44)
        }

        val strongBundle = (nameMatched && organizationMatched && titleMatched) || confirmedPatternMatched
        val negativeMatched = matchesPattern(participant, context.rejectedPatterns)

        return ScoredSemanticCandidate(
            personId = candidate.personId,
            confidence = confidence.coerceIn(0.0, 1.0),
            reasons = reasons,
            hasStrongBundle = strongBundle,
            negativeFeedbackMatched = negativeMatched,
        )
    }

    private fun matchesName(displayName: String?, candidate: PersonMatchCandidate): Boolean {
        val name = displayName.normalized() ?: return false
        if (candidate.displayName.normalized() == name) return true
        return candidate.identities.any { identity ->
            identity.verified &&
                identity.type.normalized() in CONFIRMED_ALIAS_TYPES &&
                identity.value.normalized() == name
        }
    }

    private fun matchesAny(value: String?, knownValues: Set<String>): Boolean {
        val normalizedValue = value.normalized() ?: return false
        return knownValues.any { known ->
            val normalizedKnown = known.normalized() ?: return@any false
            normalizedKnown == normalizedValue ||
                normalizedKnown.contains(normalizedValue) ||
                normalizedValue.contains(normalizedKnown)
        }
    }

    private fun matchesEvidence(evidence: String?, terms: Set<String>): Boolean {
        val normalizedEvidence = evidence.normalized() ?: return false
        return terms.any { term ->
            val normalizedTerm = term.normalized() ?: return@any false
            normalizedEvidence.contains(normalizedTerm)
        }
    }

    private fun matchesPattern(participant: PersonMatchParticipant, patterns: Set<String>): Boolean =
        patterns.any { pattern ->
            participant.semanticFingerprint().containsAllTokens(pattern.normalized(), minTokens = 3)
        }

    private data class ScoredSemanticCandidate(
        val personId: String,
        val confidence: Double,
        val reasons: List<String>,
        val hasStrongBundle: Boolean,
        val negativeFeedbackMatched: Boolean,
    ) {
        fun toPublicCandidate(): ScoredPersonMatchCandidate =
            ScoredPersonMatchCandidate(
                personId = personId,
                confidence = confidence,
                reasons = reasons,
            )
    }

    private companion object {
        private const val AUTO_SEMANTIC_THRESHOLD = 0.84
        private const val CONFIRMATION_THRESHOLD = 0.60
        private const val COMPETITION_MARGIN = 0.15
        private val CONFIRMED_ALIAS_TYPES = setOf("alias", "name")
    }
}

private fun String?.normalized(): String? =
    this
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("[^a-z0-9가-힣+@. ]"), " ")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.length >= 2 }

private fun PersonMatchParticipant.semanticFingerprint(): String =
    listOf(displayName, organization, title, evidence)
        .mapNotNull { it.normalized() }
        .joinToString(" ")

private fun String?.containsAllTokens(pattern: String?, minTokens: Int): Boolean {
    val base = this ?: return false
    val tokens = pattern?.split(' ')?.filter { it.length >= 2 }.orEmpty()
    return tokens.size >= minTokens && tokens.all { token -> base.contains(token) }
}

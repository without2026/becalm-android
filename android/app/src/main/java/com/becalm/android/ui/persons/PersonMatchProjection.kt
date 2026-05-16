package com.becalm.android.ui.persons

import com.becalm.android.data.local.db.dao.PersonIndexAggregateRow
import com.becalm.android.data.local.db.PersonMemorySemanticIndexJson
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonMemorySemanticIndexEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.local.db.entity.UnmatchedPersonInteractionEntity
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.domain.person.PersonMatchCandidate
import com.becalm.android.domain.person.PersonMatchDecision
import com.becalm.android.domain.person.PersonMatchIdentity
import com.becalm.android.domain.person.PersonMatchParticipant
import com.becalm.android.domain.person.PersonMatchingEngine
import com.becalm.android.domain.person.PersonSemanticContext
import com.becalm.android.domain.person.PersonSemanticContextProvider
import com.becalm.android.domain.person.ScoredPersonMatchCandidate

internal data class MatchingProjectionContext(
    val candidates: List<MatchCandidateProjection>,
    val blockedPersonRefs: Set<String>,
)

internal data class MatchCandidateProjection(
    val personId: String,
    val displayName: String,
    val detail: String?,
    val matchCandidate: PersonMatchCandidate,
    val semanticContext: PersonSemanticContext,
)

private class MutableMatchCandidateProjection(
    val personId: String,
) {
    var displayName: String? = null
    var nickname: String? = null
    var companyName: String? = null
    var jobTitle: String? = null
    val identities: MutableList<PersonMatchIdentity> = mutableListOf()
    val workTerms: MutableSet<String> = linkedSetOf()
    val decisionTerms: MutableSet<String> = linkedSetOf()
    val openCommitmentTerms: MutableSet<String> = linkedSetOf()
    val confirmedPatterns: MutableSet<String> = linkedSetOf()
    val rejectedPatterns: MutableSet<String> = linkedSetOf()

    fun build(): MatchCandidateProjection {
        val name = displayName ?: nickname ?: identities.firstOrNull()?.value ?: personId
        val detail = listOfNotNull(
            jobTitle?.takeIf { it.isNotBlank() },
            companyName?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").takeIf { it.isNotBlank() }
        return MatchCandidateProjection(
            personId = personId,
            displayName = name,
            detail = detail,
            matchCandidate = PersonMatchCandidate(
                personId = personId,
                displayName = name,
                identities = identities.distinct(),
            ),
            semanticContext = PersonSemanticContext(
                organizations = setOfNotNull(companyName?.takeIf { it.isNotBlank() }),
                titles = setOfNotNull(jobTitle?.takeIf { it.isNotBlank() }),
                workContextTerms = workTerms,
                decisionTerms = decisionTerms,
                openCommitmentTerms = openCommitmentTerms,
                confirmedPatterns = confirmedPatterns,
                rejectedPatterns = rejectedPatterns,
            ),
        )
    }
}

internal fun buildMatchingContext(
    userId: String,
    enrichmentRows: List<PersonEnrichmentEntity>,
    aggregateRows: List<PersonIndexAggregateRow>,
    identityRows: List<PersonIdentityEntity>,
    semanticIndexRows: List<PersonMemorySemanticIndexEntity> = emptyList(),
    blockedPersonRefs: Set<String>,
): MatchingProjectionContext {
    val builders = linkedMapOf<String, MutableMatchCandidateProjection>()
    fun builder(personId: String): MutableMatchCandidateProjection =
        builders.getOrPut(personId) { MutableMatchCandidateProjection(personId) }

    aggregateRows.forEach { row ->
        if (
            PersonIdentityResolver.isBlocked(row.primaryIdentityKey, blockedPersonRefs) ||
            PersonIdentityResolver.isLikelyAutomated(row.primaryIdentityKey) ||
            PersonIdentityResolver.isLikelyAutomated(row.displayNameHint)
        ) {
            return@forEach
        }
        builder(row.personId).apply {
            displayName = displayName ?: row.displayNameHint
            addWorkTerms(row.lastInteractionSnippet)
        }
    }
    enrichmentRows.forEach { row ->
        if (
            PersonIdentityResolver.isBlocked(row.personRef, blockedPersonRefs) ||
            PersonIdentityResolver.isLikelyAutomated(row.personRef) ||
            PersonIdentityResolver.isLikelyAutomated(row.displayName)
        ) {
            return@forEach
        }
        val resolved = PersonIdentityResolver.resolve(userId, row.personRef)
        val personId = resolved?.personId ?: row.personRef
        builder(personId).apply {
            displayName = preferredDisplayName(displayName, row.displayName)
            nickname = nickname ?: row.nickname
            companyName = companyName ?: row.company
            jobTitle = jobTitle ?: row.title
            resolved?.let {
                identities += PersonMatchIdentity(
                    type = it.identityType,
                    value = it.rawValue,
                    verified = it.verified,
                )
            }
        }
    }
    identityRows.forEach { identity ->
        if (
            PersonIdentityResolver.isBlocked(identity.rawValue, blockedPersonRefs) ||
            PersonIdentityResolver.isLikelyAutomated(identity.rawValue)
        ) {
            return@forEach
        }
        builder(identity.personId).apply {
            displayName = displayName ?: identity.displayName ?: identity.displayNameHint
            identities += PersonMatchIdentity(
                type = identity.identityType,
                value = identity.rawValue.ifBlank { identity.normalizedValue },
                verified = identity.verified,
            )
        }
    }
    semanticIndexRows.forEach { row ->
        if (row.userId != userId || row.personId !in builders.keys) return@forEach
        builder(row.personId).apply {
            workTerms += PersonMemorySemanticIndexJson.decode(row.workTermsJson)
            decisionTerms += PersonMemorySemanticIndexJson.decode(row.decisionTermsJson)
            openCommitmentTerms += PersonMemorySemanticIndexJson.decode(row.openCommitmentTermsJson)
            confirmedPatterns += PersonMemorySemanticIndexJson.decode(row.confirmedPatternsJson)
            rejectedPatterns += PersonMemorySemanticIndexJson.decode(row.rejectedPatternsJson)
            val organizations = PersonMemorySemanticIndexJson.decode(row.organizationsJson)
            val titles = PersonMemorySemanticIndexJson.decode(row.titlesJson)
            companyName = companyName ?: organizations.firstOrNull()
            jobTitle = jobTitle ?: titles.firstOrNull()
            displayName = displayName ?: PersonMemorySemanticIndexJson.decode(row.displayNameTermsJson).firstOrNull()
        }
    }
    return MatchingProjectionContext(
        candidates = builders.values.map { it.build() },
        blockedPersonRefs = blockedPersonRefs,
    )
}

internal fun SourceEventParticipantEntity.toRecommendedCandidateSummaries(
    event: UnmatchedPersonInteractionEntity,
    matchingContext: MatchingProjectionContext,
): List<PersonMatchCandidateSummary> {
    if (resolutionStatus == "suggested_self") return emptyList()
    if (matchingContext.candidates.isEmpty()) return emptyList()
    val participant = PersonMatchParticipant(
        displayName = displayNameRaw ?: normalizedValue,
        email = emailRaw,
        phone = phoneRaw,
        organization = organizationRaw,
        title = titleRaw,
        sourceType = sourceType,
        evidence = listOfNotNull(evidence, event.snippet, event.title)
            .joinToString(" ")
            .takeIf { it.isNotBlank() },
    )
    val decision = PersonMatchingEngine(ProjectionSemanticContextProvider(matchingContext.candidates)).decide(
        participant = participant,
        candidates = matchingContext.candidates.map { it.matchCandidate },
    )
    val scored = when (decision) {
        is PersonMatchDecision.NeedsUserConfirmation -> decision.candidates
        is PersonMatchDecision.AutoMatched -> listOf(
            ScoredPersonMatchCandidate(
                personId = decision.personId,
                confidence = decision.confidence,
                reasons = listOf(decision.reason),
            ),
        )
        PersonMatchDecision.NoMatch,
        is PersonMatchDecision.RejectedByNegativeFeedback,
        -> emptyList()
    }
    if (scored.isEmpty()) return emptyList()
    val byId = matchingContext.candidates.associateBy { it.personId }
    return scored.mapNotNull { candidate ->
        val projection = byId[candidate.personId] ?: return@mapNotNull null
        PersonMatchCandidateSummary(
            anchor = projection.personId,
            displayName = projection.displayName,
            detail = projection.detail,
            role = role,
            evidence = evidence ?: event.snippet,
            confidence = candidate.confidence,
            recommended = true,
            reasons = candidate.reasons.map(::localizedReason).distinct(),
            isSelfSuggestion = false,
        )
    }
}

private class ProjectionSemanticContextProvider(
    candidates: List<MatchCandidateProjection>,
) : PersonSemanticContextProvider {
    private val contextByPersonId = candidates.associate { it.personId to it.semanticContext }

    override fun contextFor(personId: String): PersonSemanticContext =
        contextByPersonId[personId] ?: PersonSemanticContext()
}

private fun MutableMatchCandidateProjection.addWorkTerms(text: String?) {
    val normalized = text?.trim()?.takeIf { it.isNotEmpty() } ?: return
    workTerms += normalized
    normalized.split(Regex("[^a-zA-Z0-9가-힣]+"))
        .map { it.trim() }
        .filter { it.length >= 3 }
        .take(12)
        .forEach { workTerms += it }
}

private fun localizedReason(reason: String): String =
    when (reason) {
        "deterministic_identity" -> "확인된 연락처"
        "confirmed_alias" -> "이전 확인 이름"
        "name" -> "이름 일치"
        "organization" -> "회사 일치"
        "title" -> "직함 일치"
        "work_context" -> "업무 맥락 유사"
        "open_commitment" -> "진행 중인 약속과 관련"
        "decision_context" -> "이전 결정과 관련"
        "confirmed_pattern" -> "이전 확인 패턴"
        else -> reason
    }

private fun preferredDisplayName(current: String?, incoming: String?): String? {
    val next = incoming?.trim()?.takeIf { it.isNotEmpty() } ?: return current
    val existing = current?.trim()?.takeIf { it.isNotEmpty() } ?: return next
    return if (isTechnicalAnchor(existing) && !isTechnicalAnchor(next)) next else existing
}

private fun isTechnicalAnchor(value: String): Boolean =
    value.contains("@") || value.startsWith("+") || value.all { it.isDigit() || it == '-' || it == ' ' }

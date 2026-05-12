package com.becalm.android.domain.person

public data class PersonMemorySemanticIndex(
    val userId: String,
    val personId: String,
    val displayNameTerms: Set<String>,
    val aliases: Set<String>,
    val organizations: Set<String>,
    val titles: Set<String>,
    val workTerms: Set<String>,
    val decisionTerms: Set<String>,
    val openCommitmentTerms: Set<String>,
    val confirmedPatterns: Set<String>,
    val rejectedPatterns: Set<String>,
    val recentSourceTypes: Set<String>,
    val contentHash: String,
)

public object PersonMemorySemanticIndexBuilder {
    public fun build(input: PersonMemoryInput): PersonMemorySemanticIndex {
        val displayNameTerms = termsFromValues(input.displayName)
        val aliases = input.identities
            .filter { it.verified && it.identityType.lowercase() in ALIAS_IDENTITY_TYPES }
            .flatMapTo(linkedSetOf()) { termsFromValues(it.value) }
            .limited()
        val organizations = input.participants
            .mapNotNullTo(linkedSetOf()) { it.organization.safePhrase() }
            .limited()
        val titles = input.participants
            .mapNotNullTo(linkedSetOf()) { it.title.safePhrase() }
            .limited()
        val openCommitments = input.commitments
            .filter { it.itemType.lowercase() != DECISION_ITEM_TYPE }
            .filter { it.status?.lowercase() !in TERMINAL_WORK_STATUSES }
        val decisions = input.commitments
            .filter { it.itemType.lowercase() == DECISION_ITEM_TYPE }

        val openCommitmentTerms = openCommitments
            .flatMapTo(linkedSetOf()) { commitment ->
                termsFromValues(commitment.title, commitment.quote)
            }
            .limited()
        val decisionTerms = decisions
            .flatMapTo(linkedSetOf()) { commitment ->
                termsFromValues(commitment.title, commitment.quote)
            }
            .limited()
        val workTerms = (
            input.participants.flatMap { termsFromValues(it.organization, it.title, it.evidence) } +
                input.interactions.flatMap { termsFromValues(it.title, it.snippet) } +
                openCommitmentTerms +
                decisionTerms
            )
            .toCollection(linkedSetOf())
            .limited()
        val confirmedPatterns = input.matchingNotes
            .filterNot { it.contains("reject", ignoreCase = true) || it.contains("거절", ignoreCase = true) }
            .mapNotNullTo(linkedSetOf()) { it.patternPhrase() }
            .limited(PATTERN_LIMIT)
        val rejectedPatterns = input.matchingNotes
            .filter { it.contains("reject", ignoreCase = true) || it.contains("거절", ignoreCase = true) }
            .mapNotNullTo(linkedSetOf()) { it.patternPhrase() }
            .limited(PATTERN_LIMIT)
        val recentSourceTypes = input.interactions
            .sortedByDescending { it.occurredAt }
            .mapNotNullTo(linkedSetOf()) { it.sourceType.safePhrase() }
            .limited(SOURCE_TYPE_LIMIT)

        val hashInput = listOf(
            input.userId,
            input.personId,
            displayNameTerms.joinToString("|"),
            aliases.joinToString("|"),
            organizations.joinToString("|"),
            titles.joinToString("|"),
            workTerms.joinToString("|"),
            decisionTerms.joinToString("|"),
            openCommitmentTerms.joinToString("|"),
            confirmedPatterns.joinToString("|"),
            rejectedPatterns.joinToString("|"),
            recentSourceTypes.joinToString("|"),
        ).joinToString("\n")

        return PersonMemorySemanticIndex(
            userId = input.userId,
            personId = input.personId,
            displayNameTerms = displayNameTerms,
            aliases = aliases,
            organizations = organizations,
            titles = titles,
            workTerms = workTerms,
            decisionTerms = decisionTerms,
            openCommitmentTerms = openCommitmentTerms,
            confirmedPatterns = confirmedPatterns,
            rejectedPatterns = rejectedPatterns,
            recentSourceTypes = recentSourceTypes,
            contentHash = PersonMemoryHash.sha256(hashInput),
        )
    }

    private fun termsFromValues(vararg values: String?): Set<String> {
        val terms = linkedSetOf<String>()
        values.forEach { value ->
            val phrase = value.safePhrase() ?: return@forEach
            if (!phrase.looksLikeRawText()) terms += phrase
            phrase.split(TOKEN_SPLIT_REGEX)
                .mapNotNullTo(terms) { token -> token.safeToken() }
        }
        return terms.limited()
    }

    private fun String.patternPhrase(): String? =
        split(TOKEN_SPLIT_REGEX)
            .mapNotNull { it.safeToken() }
            .take(12)
            .joinToString(" ")
            .takeIf { it.split(' ').size >= 3 }

    private fun String?.safePhrase(): String? =
        this
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9가-힣+@. ]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.length in 2..MAX_PHRASE_CHARS }

    private fun String.safeToken(): String? =
        lowercase()
            .replace(Regex("[^a-z0-9가-힣+@.]"), "")
            .trim()
            .takeIf { it.length >= MIN_TOKEN_CHARS && it !in STOPWORDS && it.length <= MAX_TOKEN_CHARS }

    private fun String.looksLikeRawText(): Boolean =
        length > MAX_PHRASE_CHARS / 2 && split(' ').size > MAX_PHRASE_WORDS

    private fun Set<String>.limited(limit: Int = TERM_LIMIT): Set<String> =
        asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit)
            .toCollection(linkedSetOf())

    private const val MIN_TOKEN_CHARS = 2
    private const val MAX_TOKEN_CHARS = 32
    private const val MAX_PHRASE_CHARS = 80
    private const val MAX_PHRASE_WORDS = 8
    private const val TERM_LIMIT = 40
    private const val PATTERN_LIMIT = 12
    private const val SOURCE_TYPE_LIMIT = 12
    private const val DECISION_ITEM_TYPE = "decision"
    private val TERMINAL_WORK_STATUSES = setOf("completed", "cancelled")
    private val ALIAS_IDENTITY_TYPES = setOf("alias", "name")
    private val STOPWORDS = setOf(
        "the",
        "and",
        "for",
        "with",
        "this",
        "that",
        "please",
        "thanks",
        "감사",
        "합니다",
        "입니다",
        "그리고",
    )
    private val TOKEN_SPLIT_REGEX = Regex("[^a-zA-Z0-9가-힣+@.]+")
}

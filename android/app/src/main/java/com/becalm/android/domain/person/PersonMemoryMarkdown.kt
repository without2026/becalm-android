package com.becalm.android.domain.person

import java.security.MessageDigest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

public data class PersonMemoryInput(
    val userId: String,
    val personId: String,
    val displayName: String,
    val generatedAt: Instant,
    val identities: List<PersonMemoryIdentity> = emptyList(),
    val participants: List<PersonMemoryParticipant> = emptyList(),
    val interactions: List<PersonMemoryInteraction> = emptyList(),
    val commitments: List<PersonMemoryCommitment> = emptyList(),
    val voiceEvidence: List<PersonMemoryVoiceEvidence> = emptyList(),
    val matchingNotes: List<String> = emptyList(),
)

public data class PersonMemoryIdentity(
    val identityType: String,
    val value: String,
    val verified: Boolean,
    val sourceRef: String?,
)

public data class PersonMemoryParticipant(
    val sourceRef: String,
    val sourceType: String,
    val role: String,
    val relationToUser: String,
    val displayName: String?,
    val organization: String?,
    val title: String?,
    val evidence: String?,
    val occurredAt: Instant,
)

public data class PersonMemoryInteraction(
    val sourceRef: String,
    val sourceType: String,
    val interactionKind: String,
    val title: String?,
    val snippet: String?,
    val occurredAt: Instant,
)

public data class PersonMemoryCommitment(
    val commitmentId: String,
    val sourceRef: String,
    val itemType: String,
    val title: String,
    val status: String?,
    val quote: String,
    val occurredAt: Instant,
    val direction: String? = null,
    val dueAt: Instant? = null,
    val dueHint: String? = null,
)

public data class PersonMemoryVoiceEvidence(
    val sourceRef: String,
    val sourceType: String,
    val speakerLabel: String,
    val chunkFileName: String,
    val evidence: String?,
    val occurredAt: Instant,
)

public object PersonMemoryMarkdownBuilder {
    public fun build(input: PersonMemoryInput): String {
        val body = buildBody(input, contentHash = HASH_PLACEHOLDER)
        return buildBody(input, contentHash = PersonMemoryHash.sha256(body))
    }

    private fun buildBody(input: PersonMemoryInput, contentHash: String): String =
        buildString {
            appendLine("---")
            appendLine("schema_version: 1")
            appendLine("user_id: ${input.userId}")
            appendLine("person_id: ${input.personId}")
            appendLine("display_name: ${input.displayName.escapeYamlScalar()}")
            appendLine("generated_at: ${input.generatedAt}")
            appendLine("source_window: {}")
            appendLine("content_hash: $contentHash")
            appendLine("---")
            appendLine()
            appendLine("# Person Memory")
            appendLine()
            appendIdentity(input)
            appendOpenLoops(input)
            appendWaitingOn(input)
            appendUpcomingTouchpoints(input)
            appendStaleRelationshipSignal(input)
            appendNextActionContext(input)
            appendCommunicationPreferences(input)
            appendDoNotInferBeyond(input)
            appendHighSignalHistory(input)
            appendMatchingNotes(input)
            appendLocalVoiceEvidence(input)
            appendEvidenceReferences(input)
        }

    private fun StringBuilder.appendIdentity(input: PersonMemoryInput) {
        appendLine("## Identity")
        val deterministicIdentities = input.identities
            .filter { it.verified && it.identityType in DETERMINISTIC_IDENTITY_TYPES }
            .distinctBy { it.identityType to it.value.lowercase() }
        if (deterministicIdentities.isEmpty()) {
            appendLine("- No verified deterministic identity anchors.")
        } else {
            deterministicIdentities.forEach { identity ->
                appendLine("- ${identity.identityType}: ${identity.value}${identity.sourceRef?.let { " [$it]" }.orEmpty()}")
            }
        }
        val profileFacts = input.participants
            .filter { !it.organization.isNullOrBlank() || !it.title.isNullOrBlank() }
            .distinctBy { listOf(it.organization, it.title, it.sourceRef).joinToString("|") }
        if (profileFacts.isNotEmpty()) {
            appendLine()
            profileFacts.forEach { participant ->
                val org = participant.organization?.trim().orEmpty()
                val title = participant.title?.trim().orEmpty()
                val label = when {
                    org.isNotBlank() && title.isNotBlank() -> "Works with $org as $title."
                    org.isNotBlank() -> "Works with $org."
                    else -> "Role/title observed: $title."
                }
                appendLine("- $label [${participant.sourceRef}, ${participant.occurredAt.datePart()}]")
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendOpenLoops(input: PersonMemoryInput) {
        appendLine("## Open Loops")
        val openCommitments = input.openCommitments().take(8)
        if (openCommitments.isEmpty()) {
            appendLine("- No open loop currently selected for this person.")
        } else {
            openCommitments.forEach { commitment ->
                appendCommitmentBullet(commitment)
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendWaitingOn(input: PersonMemoryInput) {
        appendLine("## Waiting On")
        val waiting = input.openCommitments()
            .filter { it.direction.equals("take", ignoreCase = true) }
            .take(6)
        if (waiting.isEmpty()) {
            appendLine("- No explicit waiting-on item is currently selected.")
        } else {
            waiting.forEach { commitment ->
                appendLine(
                    "- Waiting on them: ${commitment.title.safeInline(160)}" +
                        commitment.dueLabel()?.let { " due $it" }.orEmpty() +
                        ". [${commitment.sourceRef}, ${commitment.occurredAt.datePart()}]",
                )
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendUpcomingTouchpoints(input: PersonMemoryInput) {
        appendLine("## Upcoming Touchpoints")
        val touchpoints = mutableListOf<String>()
        input.openCommitments()
            .filter { it.dueAt != null || !it.dueHint.isNullOrBlank() }
            .take(8)
            .forEach { commitment ->
                touchpoints += "- ${commitment.dueLabel()}: ${commitment.title.safeInline(150)} [${commitment.sourceRef}, ${commitment.occurredAt.datePart()}]"
            }
        input.interactions
            .filter { it.interactionKind.equals("calendar", ignoreCase = true) || it.interactionKind.equals("meeting", ignoreCase = true) }
            .sortedByDescending { it.occurredAt }
            .take(8 - touchpoints.size)
            .forEach { interaction ->
                val summary = interaction.snippet ?: interaction.title ?: interaction.interactionKind
                touchpoints += "- ${interaction.occurredAt.datePart()}: ${summary.safeInline(150)} [${interaction.sourceRef}]"
            }
        if (touchpoints.isEmpty()) {
            appendLine("- No upcoming touchpoint is currently selected.")
        } else {
            touchpoints.take(8).forEach(::appendLine)
        }
        appendLine()
    }

    private fun StringBuilder.appendStaleRelationshipSignal(input: PersonMemoryInput) {
        appendLine("## Stale Relationship Signal")
        val latest = input.interactions.maxByOrNull { it.occurredAt }?.occurredAt
        if (latest == null) {
            appendLine("- No recent interaction available; relationship freshness is unknown.")
        } else {
            val daysSince = latest.toLocalDateTime(TimeZone.UTC).date
                .daysUntil(input.generatedAt.toLocalDateTime(TimeZone.UTC).date)
                .coerceAtLeast(0)
            appendLine("- Last meaningful interaction: $latest")
            appendLine("- Days since last interaction: $daysSince")
            appendLine("- Freshness: ${if (daysSince >= 21) "stale" else "recent"}")
        }
        appendLine()
    }

    private fun StringBuilder.appendNextActionContext(input: PersonMemoryInput) {
        appendLine("## Next Action Context")
        val candidate = input.openCommitments()
            .sortedWith(compareBy<PersonMemoryCommitment> { it.dueSortGroup() }.thenBy { it.dueAt ?: Instant.DISTANT_FUTURE })
            .firstOrNull()
        if (candidate == null) {
            appendLine("- No active next action candidate is currently selected.")
        } else {
            appendLine("- Highest priority candidate: ${candidate.directionLabelPrefix()}${candidate.title.safeInline(160)} [${candidate.sourceRef}, ${candidate.occurredAt.datePart()}]")
            appendLine("- Why now: ${candidate.nextActionReason(input.generatedAt)}")
            appendLine("- Suggested action: ${candidate.nextActionSuggestion()}")
            candidate.dueLabel()?.let { appendLine("- Due signal: $it") }
            appendLine("- Evidence anchor: ${candidate.sourceRef}")
        }
        appendLine()
    }

    private fun StringBuilder.appendCommunicationPreferences(input: PersonMemoryInput) {
        appendLine("## Communication Preferences")
        val counts = linkedMapOf<String, Int>()
        input.interactions.take(20).forEach { interaction ->
            val channel = interaction.channelLabel()
            counts[channel] = (counts[channel] ?: 0) + 1
        }
        if (counts.isEmpty()) {
            appendLine("- No communication channel preference can be inferred from recent interactions.")
        } else {
            val ordered = counts.entries.sortedByDescending { it.value }
            appendLine("- Observed channel mix: ${ordered.take(4).joinToString { "${it.key} ${it.value}" }}")
            appendLine("- Most recent observed channel: ${input.interactions.maxByOrNull { it.occurredAt }?.channelLabel() ?: ordered.first().key}")
            appendLine("- Preference signal: treat this as an observed channel pattern, not a durable preference.")
        }
        appendLine()
    }

    private fun StringBuilder.appendDoNotInferBeyond(input: PersonMemoryInput) {
        appendLine("## Do Not Infer Beyond")
        val lines = mutableListOf<String>()
        if (input.identities.none { it.verified && it.identityType in DETERMINISTIC_IDENTITY_TYPES }) {
            lines += "- Do not assume a stable email/phone identity; no verified deterministic identity row is attached."
        }
        input.openCommitments()
            .firstOrNull { it.dueAt == null && it.dueHint.isNullOrBlank() }
            ?.let { commitment ->
                lines += "- Do not invent timing for ${commitment.title.safeInline(120)}; no due signal is attached. [${commitment.sourceRef}, ${commitment.occurredAt.datePart()}]"
            }
        if (input.interactions.isEmpty()) {
            lines += "- Do not infer recent relationship tone; no interaction summary is available."
        }
        if (lines.isEmpty()) {
            appendLine("- No major uncertainty boundary is currently selected.")
        } else {
            lines.take(6).forEach(::appendLine)
        }
        appendLine()
    }

    private fun StringBuilder.appendHighSignalHistory(input: PersonMemoryInput) {
        appendLine("## High Signal History")
        val recent = input.interactions
            .sortedByDescending { it.occurredAt }
            .take(10)
        if (recent.isEmpty()) {
            appendLine("- No high-signal interaction summary is available.")
        } else {
            recent.forEach { interaction ->
                val summary = interaction.snippet ?: interaction.title ?: interaction.interactionKind
                appendLine(
                    "- ${interaction.occurredAt.datePart()} ${interaction.sourceType}/${interaction.interactionKind}: " +
                        "${summary.safeInline(180)} [${interaction.sourceRef}]",
                )
            }
        }
        val decisions = input.commitments
            .filter { it.itemType == DECISION_ITEM_TYPE }
            .take(8)
        if (decisions.isNotEmpty()) {
            appendLine()
            appendLine("### Decisions")
            decisions.forEach { decision ->
                appendCommitmentBullet(decision)
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendCommitmentBullet(commitment: PersonMemoryCommitment) {
        appendLine(
            "- ${commitment.directionLabelPrefix()}${commitment.title.safeInline(160)} (${commitment.itemType}" +
                commitment.status?.let { ", $it" }.orEmpty() +
                commitment.dueLabel()?.let { ", due $it" }.orEmpty() +
                "). [${commitment.sourceRef}, ${commitment.occurredAt.datePart()}]",
        )
    }

    private fun StringBuilder.appendMatchingNotes(input: PersonMemoryInput) {
        appendLine("## Matching Notes")
        if (input.matchingNotes.isEmpty()) {
            appendLine("- No user-confirmed semantic matching notes.")
        } else {
            input.matchingNotes.take(12).forEach { note ->
                appendLine("- ${note.safeInline(220)}")
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendLocalVoiceEvidence(input: PersonMemoryInput) {
        appendLine("## Local Voice Evidence")
        val evidenceRows = input.voiceEvidence
            .sortedWith(compareByDescending<PersonMemoryVoiceEvidence> { it.occurredAt }.thenBy { it.speakerLabel })
            .take(12)
        if (evidenceRows.isEmpty()) {
            appendLine("- No confirmed local voice evidence.")
        } else {
            evidenceRows.forEach { evidence ->
                appendLine(
                    "- ${evidence.occurredAt.datePart()} ${evidence.sourceType} speaker `${evidence.speakerLabel.safeInline(48)}`: " +
                        "[voice chunk](voice://chunk/${evidence.chunkFileName.sanitizePathSegment()})." +
                        evidence.evidence?.takeIf { it.isNotBlank() }?.let { " Evidence: ${it.safeInline(120)}." }.orEmpty() +
                        " [${evidence.sourceRef}]",
                )
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendEvidenceReferences(input: PersonMemoryInput) {
        appendLine("## Evidence References")
        val refs = linkedMapOf<String, String>()
        input.participants.forEach { participant ->
            refs[participant.sourceRef] = "${participant.sourceType}, ${participant.role}, ${participant.occurredAt}"
        }
        input.interactions.forEach { interaction ->
            refs[interaction.sourceRef] = "${interaction.sourceType}, ${interaction.interactionKind}, ${interaction.occurredAt}"
        }
        input.commitments.forEach { commitment ->
            refs[commitment.sourceRef] = "${commitment.itemType}, ${commitment.status ?: "unknown"}, ${commitment.occurredAt}"
        }
        if (refs.isEmpty()) {
            appendLine("- No evidence references.")
        } else {
            refs.forEach { (sourceRef, label) ->
                appendLine("- $sourceRef: $label")
            }
        }
    }

    private fun PersonMemoryInput.openCommitments(): List<PersonMemoryCommitment> =
        commitments
            .filter { it.itemType != DECISION_ITEM_TYPE }
            .filter { it.status?.lowercase() !in TERMINAL_WORK_STATUSES }

    private fun PersonMemoryCommitment.dueLabel(): String? =
        dueAt?.toString() ?: dueHint?.trim()?.takeIf { it.isNotEmpty() }

    private fun PersonMemoryCommitment.directionLabelPrefix(): String =
        when (direction?.lowercase()) {
            "take" -> "They owe me: "
            "give" -> "I owe them: "
            else -> ""
        }

    private fun PersonMemoryCommitment.dueSortGroup(): Int =
        when {
            dueAt != null -> 0
            !dueHint.isNullOrBlank() -> 1
            else -> 2
        }

    private fun PersonMemoryCommitment.nextActionReason(generatedAt: Instant): String {
        val due = dueAt
        if (due != null) {
            val delta = generatedAt.toLocalDateTime(TimeZone.UTC).date
                .daysUntil(due.toLocalDateTime(TimeZone.UTC).date)
            return when {
                delta < 0 -> "due date passed on ${due.datePart()}"
                delta == 0 -> "due today"
                delta <= 7 -> "due in $delta days"
                else -> "due ${due.datePart()}"
            }
        }
        val hint = dueHint?.trim()?.takeIf { it.isNotEmpty() }
        return if (hint != null) "due hint says $hint" else "active open loop with no due signal"
    }

    private fun PersonMemoryCommitment.nextActionSuggestion(): String =
        when (direction?.lowercase()) {
            "take" -> "Ask for an update or confirm whether they are still blocked."
            "give" -> "Prepare the owed follow-up or confirm a realistic handoff time."
            else -> "Clarify owner, timing, and next step before acting."
        }

    private fun PersonMemoryInteraction.channelLabel(): String {
        val text = listOf(sourceType, interactionKind).joinToString(" ").lowercase()
        return when {
            "mail" in text || "email" in text || "gmail" in text -> "email"
            "calendar" in text || "meeting" in text -> "calendar"
            "call" in text || "phone" in text -> "call"
            "sms" in text || "message" in text || "chat" in text -> "message"
            else -> sourceType.lowercase().ifBlank { interactionKind.lowercase() }
        }
    }

    private val DETERMINISTIC_IDENTITY_TYPES = setOf("email", "phone", "alias")
    private const val DECISION_ITEM_TYPE = "decision"
    private val TERMINAL_WORK_STATUSES = setOf("completed", "cancelled")
    private val HASH_PLACEHOLDER = "0".repeat(64)
}

public object PersonMemoryMarkdownValidator {
    public fun validate(
        markdown: String,
        expectedUserId: String,
        expectedPersonId: String,
    ): PersonMemoryValidationResult {
        val errors = mutableListOf<PersonMemoryValidationError>()
        if (markdown.toByteArray(Charsets.UTF_8).size > MAX_MARKDOWN_BYTES) {
            errors += PersonMemoryValidationError.TooLarge
        }

        val frontmatter = parseFrontmatter(markdown)
        REQUIRED_FRONTMATTER_KEYS.forEach { key ->
            if (frontmatter[key].isNullOrBlank()) errors += PersonMemoryValidationError.MissingFrontmatterField
        }
        if (frontmatter["user_id"] != expectedUserId) errors += PersonMemoryValidationError.UserIdMismatch
        if (frontmatter["person_id"] != expectedPersonId) errors += PersonMemoryValidationError.PersonIdMismatch
        if (frontmatter["content_hash"] != PersonMemoryHash.sha256(markdown)) {
            errors += PersonMemoryValidationError.HashMismatch
        }

        REQUIRED_SECTIONS.forEach { section ->
            if (!markdown.contains(section)) errors += PersonMemoryValidationError.MissingRequiredSection
        }
        if (hasFactualBulletWithoutSourceRef(markdown)) {
            errors += PersonMemoryValidationError.MissingSourceRef
        }
        if (markdown.lines().any { it.length > MAX_EVIDENCE_LINE_CHARS && SOURCE_REF_REGEX.containsMatchIn(it) }) {
            errors += PersonMemoryValidationError.EvidenceTooLong
        }
        if (FORBIDDEN_ORIGINAL_MARKERS.any { marker -> markdown.contains(marker, ignoreCase = true) }) {
            errors += PersonMemoryValidationError.ForbiddenOriginalLeak
        }
        if (hasForbiddenLocalVoiceReference(markdown)) {
            errors += PersonMemoryValidationError.ForbiddenLocalVoiceReference
        }
        return PersonMemoryValidationResult(errors.distinct())
    }

    private fun parseFrontmatter(markdown: String): Map<String, String> {
        if (!markdown.startsWith("---\n")) return emptyMap()
        val end = markdown.indexOf("\n---", startIndex = 4)
        if (end < 0) return emptyMap()
        return markdown.substring(4, end)
            .lineSequence()
            .mapNotNull { line ->
                val key = line.substringBefore(':', missingDelimiterValue = "").trim()
                val value = line.substringAfter(':', missingDelimiterValue = "").trim().trim('"')
                if (key.isBlank()) null else key to value
            }
            .toMap()
    }

    private fun hasFactualBulletWithoutSourceRef(markdown: String): Boolean {
        val sections = listOf(
            "## Open Loops",
            "## Waiting On",
            "## Upcoming Touchpoints",
            "## High Signal History",
            "## Matching Notes",
        )
        return sections.any { section ->
            markdown.sectionLines(section)
                .filter { it.startsWith("- ") }
                .filterNot { it in GENERIC_ALLOWED_BULLETS }
                .any { !SOURCE_REF_REGEX.containsMatchIn(it) }
        }
    }

    private fun String.sectionLines(section: String): List<String> {
        val start = indexOf(section)
        if (start < 0) return emptyList()
        val rest = substring(start + section.length)
        val next = rest.indexOf("\n## ")
        return (if (next < 0) rest else rest.substring(0, next))
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun hasForbiddenLocalVoiceReference(markdown: String): Boolean =
        markdown.sectionLines("## Local Voice Evidence")
            .filter { it.startsWith("- ") }
            .filterNot { it == "- No confirmed local voice evidence." }
            .any { line ->
                LOCAL_VOICE_FORBIDDEN_REFERENCES.any { token -> line.contains(token, ignoreCase = true) } ||
                    !line.contains("voice://chunk/")
            }

    private val REQUIRED_SECTIONS = listOf(
        "# Person Memory",
        "## Identity",
        "## Open Loops",
        "## Waiting On",
        "## Upcoming Touchpoints",
        "## Stale Relationship Signal",
        "## Next Action Context",
        "## Communication Preferences",
        "## Do Not Infer Beyond",
        "## High Signal History",
        "## Matching Notes",
        "## Local Voice Evidence",
        "## Evidence References",
    )
    private val REQUIRED_FRONTMATTER_KEYS = setOf(
        "schema_version",
        "user_id",
        "person_id",
        "display_name",
        "generated_at",
        "source_window",
        "content_hash",
    )
    private val SOURCE_REF_REGEX = Regex("(raw|commitment|participant):[A-Za-z0-9._:-]+")
    private val GENERIC_ALLOWED_BULLETS = setOf(
        "- No open loop currently selected for this person.",
        "- No explicit waiting-on item is currently selected.",
        "- No upcoming touchpoint is currently selected.",
        "- No high-signal interaction summary is available.",
        "- No user-confirmed semantic matching notes.",
        "- No confirmed local voice evidence.",
    )
    private val FORBIDDEN_ORIGINAL_MARKERS = setOf(
        "full transcript",
        "raw transcript",
        "transcript transcript transcript",
        "original email body",
        "raw_model_text",
    )
    private val LOCAL_VOICE_FORBIDDEN_REFERENCES = setOf(
        "content://",
        "file://",
        "/data/",
        "/storage/",
        "http://",
        "https://",
    )
    private const val MAX_MARKDOWN_BYTES = 65_536
    private const val MAX_EVIDENCE_LINE_CHARS = 240
}

public data class PersonMemoryValidationResult(
    val errors: List<PersonMemoryValidationError>,
)

public enum class PersonMemoryValidationError {
    UserIdMismatch,
    PersonIdMismatch,
    MissingFrontmatterField,
    HashMismatch,
    MissingRequiredSection,
    MissingSourceRef,
    EvidenceTooLong,
    ForbiddenOriginalLeak,
    ForbiddenLocalVoiceReference,
    TooLarge,
}

public object PersonMemoryHash {
    public fun sha256(markdown: String): String =
        sha256Bytes(markdown.withCanonicalHashPlaceholder().toByteArray(Charsets.UTF_8))

    public fun sha256Bytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }

    private fun String.withCanonicalHashPlaceholder(): String =
        replace(Regex("content_hash: [a-fA-F0-9]{64}"), "content_hash: ${"0".repeat(64)}")
}

public object PersonMemoryPathResolver {
    public fun localRelativePath(userId: String, personId: String): String =
        "person_memory/${PersonMemoryHash.sha256Bytes(userId.toByteArray(Charsets.UTF_8)).take(16)}/" +
            "${personId.sanitizePathSegment()}/memory.md"
}

public object PersonVoiceChunkReference {
    public fun fileName(
        userId: String,
        personId: String,
        sourceEventId: String,
        speakerLabel: String,
    ): String {
        val digest = PersonMemoryHash.sha256Bytes(
            listOf(userId, personId, sourceEventId, speakerLabel)
                .joinToString(separator = "|")
                .toByteArray(Charsets.UTF_8),
        ).take(24)
        return "voice_chunk_$digest.m4a"
    }
}

private fun String.safeInline(maxChars: Int): String =
    replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .let { if (it.length <= maxChars) it else it.take(maxChars).trimEnd() }

private fun String.escapeYamlScalar(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

private fun Instant.datePart(): String = toString().substringBefore('T')

private fun String.sanitizePathSegment(): String =
    replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }

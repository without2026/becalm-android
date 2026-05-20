package com.becalm.android.data.repository

import com.becalm.android.data.local.db.entity.PersonEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.domain.person.PersonIdentityTypes

internal fun SourceEventParticipantEntity.toPersonEntityOrNull(): PersonEntity? {
    val id = personId ?: return null
    if (identityType?.let(PersonIdentityTypes::isSourceLocal) == true) return null
    return PersonEntity(
        id = id,
        userId = userId,
        displayName = displayNameRaw ?: organizationRaw ?: normalizedValue ?: emailRaw ?: phoneRaw ?: id,
        kind = if (identityType == "organization") "organization" else "person",
        primaryEmail = emailRaw ?: normalizedValue.takeIf { identityType == "email" },
        primaryPhone = phoneRaw ?: normalizedValue.takeIf { identityType == "phone" },
        confidence = confidence.coerceIn(0.0, 1.0),
        createdAt = createdAt,
        updatedAt = createdAt,
        archivedAt = null,
    )
}

internal fun List<PersonEntity>.preferStrongestPersonRows(): List<PersonEntity> =
    groupBy(PersonEntity::id).values.map { rows ->
        rows.maxWith(
            compareBy<PersonEntity> { displayQualityScore(it.displayName) }
                .thenBy { it.primaryEmail?.isNotBlank() == true }
                .thenBy { it.primaryPhone?.isNotBlank() == true }
                .thenBy { it.confidence }
                .thenBy { it.updatedAt.toEpochMilliseconds() },
        )
    }

internal fun List<SourceEventParticipantEntity>.coalesceSourceEventParticipantPersons(): List<SourceEventParticipantEntity> {
    if (size < 2) return this
    val canonicalByEmail = canonicalPersonByIdentity(
        identityValue = { participant ->
            PersonIdentityResolver.normalizeRelationEmailAnchor(participant.emailRaw)
                ?: participant.normalizedValue
                    .takeIf { participant.identityType == "email" }
                    ?.let(PersonIdentityResolver::normalizeRelationEmailAnchor)
        },
    )
    val canonicalByPhone = canonicalPersonByIdentity(
        identityValue = { participant ->
            PersonIdentityResolver.normalizePhoneAnchor(participant.phoneRaw)
                ?: participant.normalizedValue
                    .takeIf { participant.identityType == "phone" }
                    ?.let(PersonIdentityResolver::normalizePhoneAnchor)
        },
    )
    val emailPersonByEventName = asSequence()
        .filter { it.resolutionStatus.isPersonResolvedStatus() }
        .mapNotNull { participant ->
            val nameKey = participant.sourceEventNameKey() ?: return@mapNotNull null
            val personId = participant.personId ?: return@mapNotNull null
            val email = PersonIdentityResolver.normalizeRelationEmailAnchor(participant.emailRaw)
                ?: participant.normalizedValue
                    .takeIf { participant.identityType == "email" }
                    ?.let(PersonIdentityResolver::normalizeRelationEmailAnchor)
                ?: return@mapNotNull null
            nameKey to (email to personId)
        }
        .groupBy({ it.first }, { it.second })
        .mapNotNull { (nameKey, emailPeople) ->
            val personIds = emailPeople.map { it.second }.distinct()
            if (personIds.size == 1) nameKey to personIds.single() else null
        }
        .toMap()

    return map { participant ->
        if (!participant.resolutionStatus.isPersonResolvedStatus()) {
            return@map participant
        }
        val email = PersonIdentityResolver.normalizeRelationEmailAnchor(participant.emailRaw)
            ?: participant.normalizedValue
                .takeIf { participant.identityType == "email" }
                ?.let(PersonIdentityResolver::normalizeRelationEmailAnchor)
        val phone = PersonIdentityResolver.normalizePhoneAnchor(participant.phoneRaw)
            ?: participant.normalizedValue
                .takeIf { participant.identityType == "phone" }
                ?.let(PersonIdentityResolver::normalizePhoneAnchor)
        val nameBridgePersonId = participant.sourceEventNameKey()?.let(emailPersonByEventName::get)
        val canonicalPersonId = when {
            email != null -> canonicalByEmail[email]
            nameBridgePersonId != null -> nameBridgePersonId
            phone != null -> canonicalByPhone[phone]
            else -> null
        }
        if (canonicalPersonId != null && canonicalPersonId != participant.personId) {
            participant.copy(personId = canonicalPersonId)
        } else {
            participant
        }
    }
}

internal fun SourceEventParticipantEntity.toPersonIdentityEntities(): List<PersonIdentityEntity> {
    val ownerPersonId = personId ?: return emptyList()
    val candidates = buildList {
        PersonIdentityResolver.normalizeRelationEmailAnchor(emailRaw)
            ?.let { add(IdentityProjection("email", it, emailRaw ?: it, displayNameRaw ?: emailRaw ?: it)) }
        if (identityType == "email") {
            PersonIdentityResolver.normalizeRelationEmailAnchor(normalizedValue)
                ?.let { add(IdentityProjection("email", it, emailRaw ?: normalizedValue ?: it, displayNameRaw ?: emailRaw ?: it)) }
        }
        PersonIdentityResolver.normalizePhoneAnchor(phoneRaw)
            ?.let { add(IdentityProjection("phone", it, phoneRaw ?: it, displayNameRaw ?: phoneRaw ?: it)) }
        if (identityType == "phone") {
            PersonIdentityResolver.normalizePhoneAnchor(normalizedValue)
                ?.let { add(IdentityProjection("phone", it, phoneRaw ?: normalizedValue ?: it, displayNameRaw ?: phoneRaw ?: it)) }
        }
        if (identityType == "organization") {
            normalizedValue
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { add(IdentityProjection("organization", it, organizationRaw ?: it, organizationRaw ?: displayNameRaw ?: it)) }
        }
        if (isEmpty()) {
            val type = identityType ?: return@buildList
            val normalized = normalizedValue ?: return@buildList
            val raw = when (type) {
                "organization" -> organizationRaw ?: normalized
                "name" -> displayNameRaw ?: normalized
                else -> normalized
            }
            add(IdentityProjection(type, normalized, raw, displayNameRaw ?: organizationRaw ?: raw))
        }
    }.distinctBy { "${it.type}:${it.normalized}" }
    if (candidates.isEmpty()) return emptyList()
    return candidates
        .filterNot { PersonIdentityTypes.isSourceLocal(it.type) }
        .map { candidate ->
            PersonIdentityEntity(
                id = PersonIdentityResolver.stableIdentityId(
                    userId = userId,
                    identityKey = "${candidate.type}:${candidate.normalized}",
                ),
                userId = userId,
                personId = ownerPersonId,
                identityKey = "${candidate.type}:${candidate.normalized}",
                identityType = candidate.type,
                rawValue = candidate.raw,
                displayNameHint = candidate.displayNameHint,
                identityValue = candidate.raw,
                normalizedValue = candidate.normalized,
                displayName = displayNameRaw,
                sourceType = sourceType,
                sourceRef = sourceRef,
                confidence = confidence.coerceIn(0.0, 1.0),
                isPrimary = true,
                verified = resolutionStatus == "resolved" || resolutionStatus == "person_resolved",
                lastSeenAt = createdAt,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        }
}

internal fun List<PersonIdentityEntity>.preferStrongestIdentityRows(): List<PersonIdentityEntity> =
    groupBy(PersonIdentityEntity::id).values.map { rows ->
        rows.maxWith(
            compareBy<PersonIdentityEntity> { displayQualityScore(it.displayNameHint) }
                .thenBy { displayQualityScore(it.displayName) }
                .thenBy { it.verified }
                .thenBy { it.confidence }
                .thenBy { it.updatedAt.toEpochMilliseconds() },
        )
    }

private fun List<SourceEventParticipantEntity>.canonicalPersonByIdentity(
    identityValue: (SourceEventParticipantEntity) -> String?,
): Map<String, String> =
    asSequence()
        .filter { it.resolutionStatus.isPersonResolvedStatus() }
        .mapNotNull { participant ->
            val value = identityValue(participant) ?: return@mapNotNull null
            val personId = participant.personId ?: return@mapNotNull null
            value to (personId to participant.resolutionStatus)
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, personRows) ->
            personRows
                .sortedWith(
                    compareByDescending<Pair<String, String>> { it.second == "resolved" }
                        .thenBy { it.first },
                )
                .first()
                .first
        }

private fun SourceEventParticipantEntity.sourceEventNameKey(): SourceEventNameKey? {
    val normalizedName = PersonIdentityResolver.normalizeAlias(displayNameRaw)
        ?.takeUnless { it in GENERIC_DISPLAY_NAMES }
        ?: return null
    return SourceEventNameKey(
        userId = userId,
        sourceEventId = sourceEventId,
        normalizedName = normalizedName,
    )
}

private fun String.isPersonResolvedStatus(): Boolean =
    this == "resolved" || this == "person_resolved"

private data class SourceEventNameKey(
    val userId: String,
    val sourceEventId: String,
    val normalizedName: String,
)

private data class IdentityProjection(
    val type: String,
    val normalized: String,
    val raw: String,
    val displayNameHint: String,
)

private fun displayQualityScore(value: String?): Int {
    val normalized = value?.trim().orEmpty()
    if (normalized.isEmpty()) return 0
    if (normalized.contains('@')) return 1
    if (ORGANIZATION_DISPLAY_MARKERS.any { it in normalized }) return 2
    if (normalized in GENERIC_DISPLAY_NAMES) return 2
    return 3
}

private val GENERIC_DISPLAY_NAMES: Set<String> = setOf(
    "담당자",
    "담당자님",
    "고객",
    "고객님",
)

private val ORGANIZATION_DISPLAY_MARKERS: Set<String> = setOf(
    "대학교",
    "대학",
    "재단",
    "지원단",
    "센터",
    "협회",
    "회사",
    "팀",
    "본부",
    "기관",
    "organization",
    "company",
    "team",
    "foundation",
)

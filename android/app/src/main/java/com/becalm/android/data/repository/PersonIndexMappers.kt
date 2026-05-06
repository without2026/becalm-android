package com.becalm.android.data.repository

import com.becalm.android.data.local.db.entity.PersonEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.domain.person.PersonIdentityResolver

internal fun SourceEventParticipantEntity.toPersonEntityOrNull(): PersonEntity? {
    val id = personId ?: return null
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

internal fun SourceEventParticipantEntity.toPersonIdentityEntityOrNull(): PersonIdentityEntity? {
    val ownerPersonId = personId ?: return null
    val type = identityType ?: return null
    val normalized = normalizedValue ?: return null
    val raw = when (type) {
        "email" -> emailRaw ?: normalized
        "phone" -> phoneRaw ?: normalized
        "organization" -> organizationRaw ?: normalized
        "name" -> displayNameRaw ?: normalized
        else -> normalized
    }
    return PersonIdentityEntity(
        id = PersonIdentityResolver.stableIdentityId(
            userId = userId,
            identityKey = "$type:$normalized",
        ),
        userId = userId,
        personId = ownerPersonId,
        identityKey = "$type:$normalized",
        identityType = type,
        rawValue = raw,
        displayNameHint = displayNameRaw ?: organizationRaw ?: raw,
        identityValue = raw,
        normalizedValue = normalized,
        displayName = displayNameRaw,
        sourceType = sourceType,
        sourceRef = sourceRef,
        confidence = confidence.coerceIn(0.0, 1.0),
        isPrimary = true,
        verified = resolutionStatus == "resolved",
        lastSeenAt = createdAt,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}

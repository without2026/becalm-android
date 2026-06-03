package com.becalm.android.worker

import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.remote.dto.SourceExtractedParticipantDto
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.domain.person.PersonIdentityTypes

internal object SourceParticipantReviewPolicy {
    private val REVIEWABLE_STATUSES = setOf("unresolved", "suggested_self")
    private val USER_CONFIRMED_PERSON_SOURCES = setOf(
        SourceType.VOICE,
        SourceType.CALL_RECORDING,
        SourceType.MEETING,
        SourceType.MESSAGE_SCREENSHOT,
        SourceType.GMAIL,
        SourceType.OUTLOOK_MAIL,
        SourceType.NAVER_IMAP,
        SourceType.DAUM_IMAP,
        SourceType.GOOGLE_CALENDAR,
        SourceType.OUTLOOK_CALENDAR,
    )

    fun requiresUserConfirmedPerson(sourceType: String): Boolean =
        sourceType in USER_CONFIRMED_PERSON_SOURCES

    fun isReviewableForManualReview(participant: SourceEventParticipantEntity): Boolean {
        if (participant.resolutionStatus !in REVIEWABLE_STATUSES) return false
        return isReviewableForManualReview(
            sourceType = participant.sourceType,
            role = participant.role,
            relationToUser = participant.relationToUser,
            identityType = participant.identityType,
            normalizedValue = participant.normalizedValue,
            displayName = participant.displayNameRaw,
            email = participant.emailRaw,
            phone = participant.phoneRaw,
            organization = participant.organizationRaw,
            rawValue = null,
            evidence = participant.evidence,
        )
    }

    fun isSourceLocalSpeakerReviewCandidate(participant: SourceEventParticipantEntity): Boolean =
        isSourceLocalSpeakerReviewCandidate(
            sourceType = participant.sourceType,
            role = participant.role,
            relationToUser = participant.relationToUser,
            identityType = participant.identityType,
            normalizedValue = participant.normalizedValue,
            displayName = participant.displayNameRaw,
            rawValue = null,
            evidence = participant.evidence,
        )

    fun shouldAttemptAutomaticPersonResolution(
        sourceType: String,
        participant: SourceExtractedParticipantDto,
    ): Boolean =
        shouldAttemptAutomaticPersonResolution(
            sourceType = sourceType,
            role = participant.role,
            relationToUser = participant.relationToUser,
            identityType = participant.identityType,
            normalizedValue = participant.normalizedValue,
            displayName = participant.displayName,
            email = participant.email,
            phone = participant.phone,
            organization = participant.organization,
            rawValue = participant.rawValue,
            evidence = participant.evidence,
        )

    fun isReviewableForManualReview(
        sourceType: String,
        participant: SourceExtractedParticipantDto,
    ): Boolean =
        isReviewableForManualReview(
            sourceType = sourceType,
            role = participant.role,
            relationToUser = participant.relationToUser,
            identityType = participant.identityType,
            normalizedValue = participant.normalizedValue,
            displayName = participant.displayName,
            email = participant.email,
            phone = participant.phone,
            organization = participant.organization,
            rawValue = participant.rawValue,
            evidence = participant.evidence,
        )

    private fun shouldAttemptAutomaticPersonResolution(
        sourceType: String,
        role: String,
        relationToUser: String,
        identityType: String?,
        normalizedValue: String?,
        displayName: String?,
        email: String?,
        phone: String?,
        organization: String?,
        rawValue: String?,
        evidence: String?,
    ): Boolean {
        if (!hasHumanIdentity(identityType, normalizedValue, displayName, email, phone, organization, rawValue, evidence)) {
            return false
        }
        if (isSourceLocalSpeakerLabel(identityType, normalizedValue, displayName, rawValue, evidence)) return false
        if (isOrganizationOnly(identityType, normalizedValue, displayName, email, phone, organization, rawValue)) return false
        if (requiresUserConfirmedPerson(sourceType)) return false
        return !isReferencedOnly(role, relationToUser)
    }

    private fun isReviewableForManualReview(
        sourceType: String,
        role: String,
        relationToUser: String,
        identityType: String?,
        normalizedValue: String?,
        displayName: String?,
        email: String?,
        phone: String?,
        organization: String?,
        rawValue: String?,
        evidence: String?,
    ): Boolean {
        if (relationToUser.equals("self", ignoreCase = true)) return false
        if (
            isSourceLocalSpeakerReviewCandidate(
                sourceType = sourceType,
                role = role,
                relationToUser = relationToUser,
                identityType = identityType,
                normalizedValue = normalizedValue,
                displayName = displayName,
                rawValue = rawValue,
                evidence = evidence,
            )
        ) {
            return true
        }
        if (!hasHumanIdentity(identityType, normalizedValue, displayName, email, phone, organization, rawValue, evidence)) {
            return false
        }
        if (isOrganizationOnly(identityType, normalizedValue, displayName, email, phone, organization, rawValue)) return false
        if (isSourceLocalSpeakerLabel(identityType, normalizedValue, displayName, rawValue, evidence)) return false
        if (isMeetingNonCounterpartySpeakerMention(sourceType, role, relationToUser)) return false
        if (requiresUserConfirmedPerson(sourceType)) return !isReferencedOnly(role, relationToUser)
        if (!shouldAttemptAutomaticPersonResolution(
                sourceType = sourceType,
                role = role,
                relationToUser = relationToUser,
                identityType = identityType,
                normalizedValue = normalizedValue,
                displayName = displayName,
                email = email,
                phone = phone,
                organization = organization,
                rawValue = rawValue,
                evidence = evidence,
            )
        ) {
            return false
        }
        return true
    }

    private fun isSourceLocalSpeakerReviewCandidate(
        sourceType: String,
        role: String,
        relationToUser: String,
        identityType: String?,
        normalizedValue: String?,
        displayName: String?,
        rawValue: String?,
        evidence: String?,
    ): Boolean {
        if (sourceType !in setOf(SourceType.CALL_RECORDING, SourceType.MEETING)) return false
        if (!relationToUser.equals("counterparty", ignoreCase = true)) return false
        if (role.equals("self", ignoreCase = true)) return false
        return isSourceLocalSpeakerLabel(identityType, normalizedValue, displayName, rawValue, evidence)
    }

    private fun hasHumanIdentity(
        identityType: String?,
        normalizedValue: String?,
        displayName: String?,
        email: String?,
        phone: String?,
        organization: String?,
        rawValue: String?,
        evidence: String?,
    ): Boolean =
        hasStrongIdentity(email, phone) ||
            personNameValue(identityType, normalizedValue, displayName, rawValue) != null ||
            !organization.isNullOrBlank() ||
            !evidence.isNullOrBlank()

    private fun hasStrongIdentity(email: String?, phone: String?): Boolean =
        !email.isNullOrBlank() || !phone.isNullOrBlank()

    private fun personNameValue(
        identityType: String?,
        normalizedValue: String?,
        displayName: String?,
        rawValue: String?,
    ): String? {
        if (identityType == PersonIdentityTypes.SPEAKER_LABEL) return null
        if (identityType == "organization") return null
        return listOf(displayName, rawValue, normalizedValue)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull { !PersonIdentityResolver.isSpeakerLabelValue(it) }
    }

    private fun isOrganizationOnly(
        identityType: String?,
        normalizedValue: String?,
        displayName: String?,
        email: String?,
        phone: String?,
        organization: String?,
        rawValue: String?,
    ): Boolean {
        if (hasStrongIdentity(email, phone)) return false
        if (personNameValue(identityType, normalizedValue, displayName, rawValue) != null) return false
        return identityType == "organization" || !organization.isNullOrBlank()
    }

    private fun isMeetingNonCounterpartySpeakerMention(sourceType: String, role: String, relationToUser: String): Boolean =
        sourceType == SourceType.MEETING &&
            role.equals("speaker", ignoreCase = true) &&
            relationToUser.equals("participant", ignoreCase = true)

    private fun isSourceLocalSpeakerLabel(
        identityType: String?,
        normalizedValue: String?,
        displayName: String?,
        rawValue: String?,
        evidence: String?,
    ): Boolean =
        identityType == PersonIdentityTypes.SPEAKER_LABEL ||
            listOf(normalizedValue, displayName, rawValue, evidence)
                .filterNotNull()
                .any(PersonIdentityResolver::isSpeakerLabelValue)

    private fun isReferencedOnly(role: String, relationToUser: String): Boolean =
        role.equals("mentioned", ignoreCase = true) &&
            relationToUser.equals("referenced", ignoreCase = true)
}

package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.datetime.Instant

@JsonClass(generateAdapter = true)
public data class UserProfileDto(
    @field:Json(name = "user_id") val userId: String,
    @field:Json(name = "display_name") val displayName: String? = null,
    @field:Json(name = "display_name_override") val displayNameOverride: String? = null,
    @field:Json(name = "display_name_source") val displayNameSource: String? = null,
    @field:Json(name = "phone_e164_self") val phoneE164Self: String? = null,
    @field:Json(name = "timezone") val timezone: String = "Asia/Seoul",
    @field:Json(name = "preferred_locale") val preferredLocale: String = "ko",
    @field:Json(name = "onboarding_completed_at") val onboardingCompletedAt: Instant? = null,
    @field:Json(name = "created_at") val createdAt: Instant? = null,
    @field:Json(name = "updated_at") val updatedAt: Instant? = null,
)

@JsonClass(generateAdapter = true)
public data class UserProfileResponseDto(
    @field:Json(name = "data") val data: UserProfileDto,
)

@JsonClass(generateAdapter = true)
public data class UserProfilePatchRequestDto(
    @field:Json(name = "display_name") val displayName: String? = null,
    @field:Json(name = "display_name_source") val displayNameSource: String? = null,
    @field:Json(name = "phone_e164_self") val phoneE164Self: String? = null,
    @field:Json(name = "timezone") val timezone: String? = null,
    @field:Json(name = "preferred_locale") val preferredLocale: String? = null,
    @field:Json(name = "onboarding_completed_at") val onboardingCompletedAt: String? = null,
)

@JsonClass(generateAdapter = true)
public data class OnboardingSelfIdentityCommitRequestDto(
    @field:Json(name = "display_name") val displayName: String,
    @field:Json(name = "display_name_source") val displayNameSource: String? = null,
    @field:Json(name = "display_name_read_only") val displayNameReadOnly: Boolean = false,
    @field:Json(name = "email") val email: String? = null,
    @field:Json(name = "email_read_only") val emailReadOnly: Boolean = false,
    @field:Json(name = "phone_e164") val phoneE164: String? = null,
    @field:Json(name = "phone_read_only") val phoneReadOnly: Boolean = false,
    @field:Json(name = "phone_verified") val phoneVerified: Boolean = false,
    @field:Json(name = "alias") val alias: String? = null,
    @field:Json(name = "auth_provider") val authProvider: String? = null,
    @field:Json(name = "timezone") val timezone: String? = null,
    @field:Json(name = "preferred_locale") val preferredLocale: String? = null,
)

@JsonClass(generateAdapter = true)
public data class OnboardingSelfIdentityCommitResponseDto(
    @field:Json(name = "data") val data: OnboardingSelfIdentityCommitDataDto,
)

@JsonClass(generateAdapter = true)
public data class OnboardingSelfIdentityCommitDataDto(
    @field:Json(name = "profile") val profile: UserProfileDto,
    @field:Json(name = "anchors") val anchors: List<SelfIdentityAnchorDto> = emptyList(),
    @field:Json(name = "email_connection_recommendation")
    val emailConnectionRecommendation: EmailConnectionRecommendationDto? = null,
)

@JsonClass(generateAdapter = true)
public data class EmailConnectionRecommendationDto(
    @field:Json(name = "provider") val provider: String,
    @field:Json(name = "reason") val reason: String,
)

@JsonClass(generateAdapter = true)
public data class SelfIdentityAnchorDto(
    @field:Json(name = "id") val id: String,
    @field:Json(name = "user_id") val userId: String,
    @field:Json(name = "anchor_type") val anchorType: String,
    @field:Json(name = "normalized_value") val normalizedValue: String,
    @field:Json(name = "display_value") val displayValue: String? = null,
    @field:Json(name = "source") val source: String,
    @field:Json(name = "scope") val scope: String,
    @field:Json(name = "source_connection_id") val sourceConnectionId: String? = null,
    @field:Json(name = "source_event_id") val sourceEventId: String? = null,
    @field:Json(name = "trust") val trust: String,
    @field:Json(name = "status") val status: String,
    @field:Json(name = "created_at") val createdAt: Instant? = null,
    @field:Json(name = "updated_at") val updatedAt: Instant? = null,
)

@JsonClass(generateAdapter = true)
public data class SelfIdentityAnchorsResponseDto(
    @field:Json(name = "data") val data: List<SelfIdentityAnchorDto>,
)

@JsonClass(generateAdapter = true)
public data class SelfIdentityAnchorCreateRequestDto(
    @field:Json(name = "anchor_type") val anchorType: String,
    @field:Json(name = "value") val value: String,
    @field:Json(name = "display_value") val displayValue: String? = null,
    @field:Json(name = "source") val source: String = "user_profile",
    @field:Json(name = "scope") val scope: String = "global",
    @field:Json(name = "trust") val trust: String = "user_confirmed",
    @field:Json(name = "status") val status: String = "active",
    @field:Json(name = "source_connection_id") val sourceConnectionId: String? = null,
    @field:Json(name = "source_event_id") val sourceEventId: String? = null,
)

@JsonClass(generateAdapter = true)
public data class SelfIdentityAnchorPatchRequestDto(
    @field:Json(name = "display_value") val displayValue: String? = null,
    @field:Json(name = "trust") val trust: String? = null,
    @field:Json(name = "status") val status: String? = null,
)

@JsonClass(generateAdapter = true)
public data class SelfIdentityAnchorResponseDto(
    @field:Json(name = "data") val data: SelfIdentityAnchorDto,
)

@JsonClass(generateAdapter = true)
public data class SourceConnectionDto(
    @field:Json(name = "id") val id: String,
    @field:Json(name = "user_id") val userId: String? = null,
    @field:Json(name = "provider") val provider: String,
    @field:Json(name = "capability") val capability: String,
    @field:Json(name = "account_identifier") val accountIdentifier: String? = null,
    @field:Json(name = "account_display_name") val accountDisplayName: String? = null,
    @field:Json(name = "ownership") val ownership: String,
    @field:Json(name = "status") val status: String,
    @field:Json(name = "linked_self_anchor_id") val linkedSelfAnchorId: String? = null,
    @field:Json(name = "last_sync_at") val lastSyncAt: Instant? = null,
    @field:Json(name = "last_error") val lastError: String? = null,
    @field:Json(name = "deleted_at") val deletedAt: Instant? = null,
)

@JsonClass(generateAdapter = true)
public data class SourceConnectionsResponseDto(
    @field:Json(name = "data") val data: List<SourceConnectionDto>,
)

@JsonClass(generateAdapter = true)
public data class SourceConnectionResponseDto(
    @field:Json(name = "data") val data: SourceConnectionDto,
)

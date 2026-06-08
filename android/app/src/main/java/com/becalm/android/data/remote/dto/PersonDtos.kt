package com.becalm.android.data.remote.dto

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Json
import kotlinx.datetime.Instant

/**
 * Summary of a canonical person as returned by GET /v1/persons.
 *
 * Backend stores first-class `persons` rows. [personId] is the only wire key.
 */
@JsonClass(generateAdapter = true)
public data class PersonSummaryDto(
    /** Canonical person id. Primary navigation/key field. */
    @field:Json(name = "person_id") val personId: String? = null,

    /** Server-side canonical display name. */
    @field:Json(name = "display_name") val displayName: String? = null,

    /** `person` or `organization`. */
    @field:Json(name = "kind") val kind: String? = null,

    /** Primary email identity when one exists. */
    @field:Json(name = "primary_email") val primaryEmail: String? = null,

    /** Primary phone identity when one exists. */
    @field:Json(name = "primary_phone") val primaryPhone: String? = null,

    /**
     * Timestamp of the most recent interaction associated with this person.
     * Used for sorting in the Persons list screen.
     */
    @field:Json(name = "last_contact_at") val lastContactAt: Instant? = null,

    /**
     * Count of open persisted trackable items for this person.
     *
     * Action rows are excluded when terminal (`completed` / `cancelled`); schedule and
     * decision rows count as open until a future dedicated lifecycle is introduced.
     */
    @field:Json(name = "open_commitments_count") val openCommitmentsCount: Int? = null,
) {
    public val stablePersonId: String
        get() = personId.orEmpty()
}

/**
 * Paginated list response for GET /v1/persons.
 *
 * Wire format: { data: Person[], cursor: string, has_more: boolean }
 *
 * Supports substring search via the `q` query parameter
 * (matches display_name, email, phone). Pass [cursor] when [hasMore] is true.
 */
@JsonClass(generateAdapter = true)
public data class PersonListResponse(
    @field:Json(name = "data") val data: List<PersonSummaryDto>,

    /**
     * Opaque pagination cursor. Pass as `cursor` query param on next request.
     */
    @field:Json(name = "cursor") val cursor: String,

    /** True when additional pages exist beyond this response. */
    @field:Json(name = "has_more") val hasMore: Boolean,
)

/**
 * Bounded person-detail recall row returned by GET /v1/persons/{person_id}/events.
 *
 * Backend rows are intentionally source-of-truth recall projections, not a full local
 * Room dump. Optional fields are consumed when present and have local fallbacks so older
 * backend deployments keep rendering safely.
 */
@JsonClass(generateAdapter = true)
public data class PersonEventDto(
    @field:Json(name = "id") val id: String,
    @field:Json(name = "user_id") val userId: String? = null,
    @field:Json(name = "person_id") val personId: String? = null,
    @field:Json(name = "source_event_id") val sourceEventId: String? = null,
    @field:Json(name = "commitment_id") val commitmentId: String? = null,
    @field:Json(name = "interaction_key") val interactionKey: String? = null,
    @field:Json(name = "interaction_type") val interactionType: String? = null,
    @field:Json(name = "source_type") val sourceType: String,
    @field:Json(name = "source_ref") val sourceRef: String? = null,
    @field:Json(name = "provider_event_id") val providerEventId: String? = null,
    @field:Json(name = "source_connection_id") val sourceConnectionId: String? = null,
    @field:Json(name = "event_kind") val eventKind: String? = null,
    @field:Json(name = "role") val role: String? = null,
    @field:Json(name = "direction") val direction: String? = null,
    @field:Json(name = "status") val status: String? = null,
    @field:Json(name = "occurred_at") val occurredAt: Instant,
    @field:Json(name = "title") val title: String? = null,
    @field:Json(name = "snippet") val snippet: String? = null,
    @field:Json(name = "confidence") val confidence: Double? = null,
    @field:Json(name = "created_at") val createdAt: Instant? = null,
    @field:Json(name = "updated_at") val updatedAt: Instant? = null,
)

/**
 * Paginated list response for GET /v1/persons/{person_id}/events.
 */
@JsonClass(generateAdapter = true)
public data class PersonEventsResponse(
    @field:Json(name = "data") val data: List<PersonEventDto>,
    @field:Json(name = "cursor") val cursor: String,
    @field:Json(name = "has_more") val hasMore: Boolean,
)

/**
 * Paginated list response for GET /v1/persons/{person_id}/commitments.
 *
 * Wire format: { data: Commitment[], cursor: string, has_more: boolean }
 */
@JsonClass(generateAdapter = true)
public data class PersonCommitmentsResponse(
    @field:Json(name = "data") val data: List<CommitmentDto>,
    @field:Json(name = "cursor") val cursor: String,
    @field:Json(name = "has_more") val hasMore: Boolean,
)

@JsonClass(generateAdapter = true)
public data class PersonMemoryUploadRequestDto(
    @field:Json(name = "schema_version") val schemaVersion: Int,
    @field:Json(name = "content_markdown") val contentMarkdown: String,
    @field:Json(name = "content_hash") val contentHash: String,
    @field:Json(name = "generated_at") val generatedAt: Instant,
    @field:Json(name = "source_window_from") val sourceWindowFrom: Instant? = null,
    @field:Json(name = "source_window_to") val sourceWindowTo: Instant? = null,
)

@JsonClass(generateAdapter = true)
public data class PersonMemoryUploadResponseDto(
    @field:Json(name = "bucket") val bucket: String,
    @field:Json(name = "object_path") val objectPath: String,
    @field:Json(name = "person_id") val personId: String,
    @field:Json(name = "content_hash") val contentHash: String,
    @field:Json(name = "generated_at") val generatedAt: Instant,
)

@JsonClass(generateAdapter = true)
public data class PersonMemoryDownloadResponseDto(
    @field:Json(name = "bucket") val bucket: String,
    @field:Json(name = "object_path") val objectPath: String,
    @field:Json(name = "person_id") val personId: String,
    @field:Json(name = "content_markdown") val contentMarkdown: String,
    @field:Json(name = "content_hash") val contentHash: String,
    @field:Json(name = "generated_at") val generatedAt: Instant,
)

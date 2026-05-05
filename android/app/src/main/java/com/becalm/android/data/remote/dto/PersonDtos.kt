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
 * Paginated list response for GET /v1/persons/{person_id}/events.
 *
 * Wire format: { data: RawIngestionEvent[], cursor: string, has_more: boolean }
 *
 * Returns the timeline of raw ingestion events associated with the given person_id.
 * Returns 404 when no events exist for the given person_id.
 */
@JsonClass(generateAdapter = true)
public data class PersonEventsResponse(
    @field:Json(name = "data") val data: List<RawIngestionEventDto>,

    /**
     * Opaque pagination cursor. Pass as `cursor` query param on next request.
     */
    @field:Json(name = "cursor") val cursor: String,

    /** True when additional pages exist beyond this response. */
    @field:Json(name = "has_more") val hasMore: Boolean,
)

/**
 * Paginated list response for GET /v1/persons/{person_id}/commitments.
 *
 * Wire format: { data: Commitment[], cursor: string, has_more: boolean }
 *
 * Returns commitments linked to the given person_id through commitment_participants.
 * Returns 404 when no commitments exist for the given person_id.
 */
@JsonClass(generateAdapter = true)
public data class PersonCommitmentsResponse(
    @field:Json(name = "data") val data: List<CommitmentDto>,

    /**
     * Opaque pagination cursor. Pass as `cursor` query param on next request.
     */
    @field:Json(name = "cursor") val cursor: String,

    /** True when additional pages exist beyond this response. */
    @field:Json(name = "has_more") val hasMore: Boolean,
)

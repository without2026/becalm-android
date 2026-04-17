package com.becalm.android.data.remote.dto

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Json
import kotlinx.datetime.Instant

/**
 * Summary of a virtual "person" as returned by GET /v1/persons.
 *
 * In the MVP there is no separate `persons` Supabase table. Person records are
 * virtual — grouped by `person_ref` across `raw_ingestion_events` and `commitments`.
 * (data-model.yml migration_notes: "persons_enrichment: Room only — never uploaded".)
 *
 * The api-contract.yml declares a `Person[]` response shape but does not enumerate
 * its fields explicitly (the endpoint was added for spec_refs SRC-001/003/006).
 * The fields below are derived from the virtual grouping semantics: the server
 * aggregates across `raw_ingestion_events` and `commitments` on `person_ref`.
 *
 * Note: `display_name` here is the server's best guess (from event data only).
 * The richer [persons_enrichment] data lives in Room and is never sent to Railway.
 */
@JsonClass(generateAdapter = true)
public data class PersonSummaryDto(
    /**
     * Canonicalized person identifier. Primary key for virtual grouping.
     * Precedence: E.164 phone > lowercase email > normalized display name.
     */
    @field:Json(name = "person_ref") val personRef: String,

    /**
     * Server-side display name inferred from event titles or counterparty_raw fields.
     * May be null when no display name can be inferred. The authoritative display name
     * is in Room's [persons_enrichment] table and is never sent to Railway.
     */
    @field:Json(name = "display_name") val displayName: String? = null,

    /**
     * Timestamp of the most recent raw ingestion event associated with this person.
     * Used for sorting in the Persons list screen.
     */
    @field:Json(name = "last_contact_at") val lastContactAt: Instant? = null,

    /**
     * Count of commitments for this person where action_state is not "completed".
     * Used for the badge indicator in PersonCard.
     */
    @field:Json(name = "open_commitments_count") val openCommitmentsCount: Int? = null,
)

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
 * Returns the timeline of raw ingestion events associated with the given person_ref.
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
 * Returns commitments where person_ref matches the given person_id.
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

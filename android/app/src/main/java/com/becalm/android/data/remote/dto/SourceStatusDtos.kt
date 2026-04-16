package com.becalm.android.data.remote.dto

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Json
import kotlinx.datetime.Instant

/**
 * Per-source connection state.
 *
 * CONTRACT GAP: There is no `/v1/source_status` endpoint declared in api-contract.yml.
 * These DTOs are defined speculatively for future use but are not currently wired to
 * any Railway endpoint. SP-05 (RailwayApi.kt) MUST NOT create a source_status endpoint
 * until it is added to the contract.
 *
 * @property sourceType Valid values: see [SourceType] constants.
 * @property status Expected values: "connected" | "disconnected" | "error" | "pending_auth"
 */
@JsonClass(generateAdapter = true)
public data class SourceStatusDto(
    /**
     * Source type identifier.
     * Valid values: see [SourceType] constants (e.g. "gmail", "google_calendar").
     */
    @field:Json(name = "source_type") val sourceType: String,

    /**
     * Current connection status for this source.
     * Expected values: "connected" | "disconnected" | "error" | "pending_auth"
     * (Not formally defined in api-contract.yml v1 — update when endpoint is added.)
     */
    @field:Json(name = "status") val status: String,

    /**
     * Timestamp of the most recent successful sync for this source.
     * Null if the source has never successfully synced.
     */
    @field:Json(name = "last_synced_at") val lastSyncedAt: Instant? = null,

    /**
     * Human-readable error message when [status] is "error".
     * Null otherwise.
     */
    @field:Json(name = "error_message") val errorMessage: String? = null,
)

/**
 * Response wrapper for a hypothetical GET /v1/source_status endpoint.
 *
 * CONTRACT GAP: Not declared in api-contract.yml v1. Do not use until the
 * endpoint is formally added to the contract.
 *
 * Wire format (anticipated): { data: SourceStatus[] }
 */
@JsonClass(generateAdapter = true)
public data class SourceStatusResponse(
    @field:Json(name = "data") val data: List<SourceStatusDto>,
)

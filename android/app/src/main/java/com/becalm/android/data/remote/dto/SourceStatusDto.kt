package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.datetime.Instant

/**
 * Single item in [SourceStatusResponseDto.sources] returned by GET /v1/source_status.
 *
 * Wire shape (api-contract.yml § GET /v1/source_status):
 * ```
 * {
 *   source_type: "voice" | "gmail" | "outlook_mail" | "naver_imap" |
 *                "daum_imap" | "google_calendar" | "outlook_calendar",
 *   state:       "idle" | "syncing" | "synced" | "error",
 *   last_sync_at?: datetime,
 *   last_error?: string
 * }
 * ```
 *
 * The server may omit sources it cannot observe directly. Android merges returned rows into
 * the local DataStore status cache and preserves existing local-derived rows for omissions
 * such as voice runtime state.
 */
@JsonClass(generateAdapter = true)
public data class SourceStatusItemDto(
    /** One of the product-facing source type constants. */
    @field:Json(name = "source_type") val sourceType: String,

    /** "idle" | "syncing" | "synced" | "error" */
    @field:Json(name = "state") val state: String,

    /** Instant of the most-recently completed successful sync; null when never synced. */
    @field:Json(name = "last_sync_at") val lastSyncAt: Instant? = null,

    /** Human-readable error text; null unless [state] == "error". */
    @field:Json(name = "last_error") val lastError: String? = null,
)

/**
 * Response body for GET /v1/source_status (api-contract.yml).
 *
 * Spec refs: TDY-003, SMG-001.
 */
@JsonClass(generateAdapter = true)
public data class SourceStatusResponseDto(
    @field:Json(name = "sources") val sources: List<SourceStatusItemDto>,
)

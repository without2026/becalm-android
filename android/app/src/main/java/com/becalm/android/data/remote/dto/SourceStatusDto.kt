package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.datetime.Instant

/**
 * Single item in [SourceStatusResponseDto.sources] returned by GET /v1/source_status.
 *
 * Wire shape (api-contract.yml lines 212-226):
 * ```
 * {
 *   source_type: "gmail" | "outlook_mail" | "naver_imap" | "daum_imap" |
 *                "google_calendar" | "outlook_calendar",
 *   state:       "idle" | "syncing" | "synced" | "error",
 *   last_sync_at?: datetime,
 *   last_error?: string
 * }
 * ```
 *
 * Exactly six items are returned (voice excluded — TDY-003 chip strip is 6 ingestion sources).
 */
@JsonClass(generateAdapter = true)
public data class SourceStatusItemDto(
    /** One of the six [SourceType] constants (voice excluded). */
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

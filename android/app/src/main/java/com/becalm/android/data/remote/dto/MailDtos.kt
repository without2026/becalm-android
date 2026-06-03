package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response body for `POST /v1/mail_sources:sync`.
 */
@JsonClass(generateAdapter = true)
public data class MailSyncResponse(
    @field:Json(name = "synced") val synced: Int,
    @field:Json(name = "job_id") val jobId: String? = null,
    @field:Json(name = "status") val status: String? = null,
    @field:Json(name = "accepted") val accepted: Boolean = false,
    @field:Json(name = "retry_after_seconds") val retryAfterSeconds: Long? = null,
    @field:Json(name = "error_code") val errorCode: String? = null,
    @field:Json(name = "error_message") val errorMessage: String? = null,
    @field:Json(name = "stage") val stage: String? = null,
    @field:Json(name = "progress") val progress: Double? = null,
    @field:Json(name = "message") val message: String? = null,
    @field:Json(name = "sync_mode") val syncMode: String? = null,
    @field:Json(name = "has_more_pages") val hasMorePages: Boolean = false,
    @field:Json(name = "backfill_complete") val backfillComplete: Boolean? = null,
)

/**
 * Response body for `GET /v1/oauth/mail/{provider}:start`.
 */
@JsonClass(generateAdapter = true)
public data class MailOAuthStartResponse(
    @field:Json(name = "provider") val provider: String,
    @field:Json(name = "authorization_url") val authorizationUrl: String,
    @field:Json(name = "redirect_uri") val redirectUri: String,
    @field:Json(name = "state") val state: String,
)

/**
 * Response body for `GET /v1/oauth/mail/{provider}:status`.
 */
@JsonClass(generateAdapter = true)
public data class MailOAuthStatusResponse(
    @field:Json(name = "provider") val provider: String,
    @field:Json(name = "connected") val connected: Boolean,
    @field:Json(name = "account_email") val accountEmail: String? = null,
    @field:Json(name = "display_name") val displayName: String? = null,
    @field:Json(name = "connections") val connections: List<SourceConnectionDto> = emptyList(),
)
